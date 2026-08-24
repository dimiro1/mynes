package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The extra scanlines, and the four things about them that are easy to get wrong.
 * <p>
 * A frame has to grow by exactly the lines asked for; the lines have to land where the beam is
 * already idle, so nothing drawn moves; the scanline counter must never name a line the chip does
 * not have, because a great deal of this class keys on 240, 241 and the pre-render line by number;
 * and the dot clock must <em>not</em> grow with the frame, because OAM decay is measured against it
 * and a frame's blanking that outlasted the charge would wipe every sprite in the game.
 *
 * @see Overclock
 */
class PPUOverclockTests extends PPUFixture {
    private static final int VBLANK_FLAG = 0x80;

    @BeforeEach
    void setUp() {
        createPPU();
    }

    @Nested
    @DisplayName("how long a frame becomes")
    class FrameLength {
        @Test
        void aFrameIsLongerByExactlyThatManyLines() {
            ppu.setOverclock(new Overclock(131, 0));

            assertEquals(DOTS_PER_FRAME + 131 * 341, measureFrame());
            assertEquals(DOTS_PER_FRAME + 131 * 341, measureFrame(), "and every frame after it");
        }

        @Test
        void bothHalvesCount() {
            ppu.setOverclock(new Overclock(20, 30));

            assertEquals(DOTS_PER_FRAME + 50 * 341, measureFrame());
        }

        @Test
        void anOddFrameStillDropsItsDot() {
            // The skip keys on the pre-render line, which is exactly where the repeats are not, so
            // the two are independent and a frame is the sum of both.
            ppu.setOverclock(new Overclock(7, 0));
            startRenderingOnTheFirstOddFrame();

            var extra = 7 * 341;

            assertEquals(
                    List.of(DOTS_PER_FRAME + extra - 1, DOTS_PER_FRAME + extra,
                            DOTS_PER_FRAME + extra - 1, DOTS_PER_FRAME + extra),
                    List.of(measureFrame(), measureFrame(), measureFrame(), measureFrame()));
        }

        @Test
        void switchingItOffMidRepeatStopsAtTheNextLineWrap() {
            ppu.setOverclock(new Overclock(50, 0));

            // Part way through the repeats, which is where the count is not a beam position anybody
            // can name from outside.
            runTo(240, 0);
            run(10 * 341 + 100);
            assertTrue(ppu.isOnExtraLine());

            ppu.setOverclock(Overclock.NONE);

            // The line being run finishes -- there are 341 dots in it whatever anybody says half way
            // through -- and then the beam moves on.
            run(341 - 100);

            assertFalse(ppu.isOnExtraLine());
            assertEquals(241, ppu.getScanline(), "off means off from the next wrap");
        }

        @Test
        void aResetForgetsTheRepeatsItWasIn() {
            ppu.setOverclock(new Overclock(50, 0));

            runTo(240, 0);
            run(3 * 341);
            assertTrue(ppu.isOnExtraLine());

            ppu.reset();

            assertFalse(ppu.isOnExtraLine(), "the beam is back at the top left");
            assertEquals(new Overclock(50, 0), ppu.getOverclock(),
                    "and the setting is the Hacks menu's, which the button does not reach");
        }
    }

    @Nested
    @DisplayName("where the lines go")
    class Placement {
        @Test
        void theExtraLinesSitBetweenThePostRenderLineAndTheVBlankFlag() {
            ppu.setOverclock(new Overclock(4, 0));

            runTo(240, 0);

            // Five runs of line 240 -- the real one and four repeats -- with the flag down for all
            // of them, and then 241 with the flag going up on dot 1 as it always does.
            for (var i = 0; i < 5; i++) {
                assertEquals(240, ppu.getScanline(), "run " + i + " of the post-render line");
                assertFalse(vblankSet(), "the flag cannot go up before line 241");
                run(341);
            }

            assertEquals(241, ppu.getScanline());
            assertFalse(vblankSet(), "dot 0 has not done its work yet");

            run(2);
            assertTrue(vblankSet());
        }

        @Test
        void theFlagStaysUpThroughTheLinesAfterNmiAndClearsOnThePreRenderLine() {
            ppu.setOverclock(new Overclock(0, 4));

            runTo(260, 0);

            for (var i = 0; i < 5; i++) {
                assertEquals(260, ppu.getScanline(), "run " + i + " of the last line of blanking");
                assertTrue(vblankSet(), "still vertical blank, however long it lasts");
                run(341);
            }

            assertEquals(261, ppu.getScanline());
            assertTrue(vblankSet(), "right to the end of it");

            run(2);
            assertFalse(vblankSet(), "and down on dot 1 of the pre-render line");
        }

        @Test
        void theScanlineCounterNeverNamesALineTheChipDoesNotHave() {
            // The whole design in one assertion. Everything else in PPU keys on 240, 241 and the
            // pre-render line by number, so a repeat that shifted them would be a different chip.
            ppu.setOverclock(new Overclock(30, 30));

            var seen = new boolean[262];
            var frames = ppu.getFrame() + 2;

            while (ppu.getFrame() < frames) {
                assertTrue(ppu.getScanline() >= 0 && ppu.getScanline() <= 261,
                        "the beam is on line " + ppu.getScanline());
                seen[ppu.getScanline()] = true;
                ppu.tick();
            }

            for (var line = 0; line < seen.length; line++) {
                assertTrue(seen[line], "line " + line + " never happened");
            }
        }

        @Test
        void nothingIsDrawnOnAnExtraLine() {
            // The picture is 240 lines whatever the frame's length: the repeats are of lines the
            // beam is already idle on, so the framebuffer is written exactly as often as before.
            ppu.setOverclock(new Overclock(60, 0));
            warmUp();

            ppu.write(PPUMASK, 0x1E);
            renderFrames(2);

            var before = ppu.getFrameBuffer().clone();

            runTo(240, 0);
            run(30 * 341);

            assertArrayEquals(before, ppu.getFrameBuffer(),
                    "a repeated post-render line drew something");
        }
    }

    @Nested
    @DisplayName("what the extra lines cost")
    class Cost {
        @Test
        void oamDoesNotDecayAcrossTheExtraLines() {
            // A thousand lines is 341000 dots, which is three and a half times the charge. If the
            // dot clock ran through them the row would read back as zeroes and every sprite in the
            // game would vanish once a frame -- which is why tick() skips clock++ on a repeat.
            // The warm-up first: runTo gives up after two frames' worth of dots, and two frames of
            // this are four hundred thousand.
            warmUp();
            ppu.setOverclock(new Overclock(500, 500));

            ppu.write(OAMADDR, 0x10);
            ppu.write(OAMDATA, 0xAA);

            runTo(240, 0);
            run(1000 * 341);

            ppu.write(OAMADDR, 0x10);
            assertEquals(0xAA, ppu.read(OAMDATA), "the charge leaked away in the emulator's time");
        }

        @Test
        void theMapperSeesEveryExtraDot() {
            // An MMC3 clocked by $2006 writes during vertical blank counts the idle dots between
            // them, so a line the mapper never heard about would be a line its counter lost.
            var recorder = new CountingMapper();
            createPPU(recorder);

            ppu.setOverclock(new Overclock(9, 0));
            runTo(240, 0);
            recorder.clear();

            run(10 * 341);

            assertEquals(10 * 341, recorder.dots(), "the real line and its nine repeats");
        }

        @Test
        void aSecondAddressWriteStillLandsOnAnExtraLine() {
            // The write delay is counted in dots and the dots are still happening, so the address
            // reaches the counter -- and the cartridge -- on a repeated line exactly as it would on
            // any other.
            var recorder = new CountingMapper();
            createPPU(recorder);

            ppu.setOverclock(new Overclock(9, 0));
            warmUp();
            runTo(240, 0);
            run(3 * 341);

            assertTrue(ppu.isOnExtraLine(), "on a repeat, which is the point of the test");
            recorder.clear();

            ppu.read(PPUSTATUS);
            ppu.write(PPUADDR, 0x21);
            ppu.write(PPUADDR, 0x08);
            run(ADDRESS_UPDATE_DOTS);

            assertEquals(List.of(0x2108), recorder.addresses());
        }
    }

    /**
     * The 2C07, whose extra lines go after line 310 rather than after 260 -- the last line of a much
     * longer vertical blank.
     */
    @Nested
    @DisplayName("a PAL machine")
    class PALTiming {
        @BeforeEach
        void setUp() {
            createPPU(Region.PAL);
        }

        @Test
        void onPalTheLinesGoAfterLine310() {
            ppu.setOverclock(new Overclock(0, 3));

            runTo(310, 0);

            for (var i = 0; i < 4; i++) {
                assertEquals(310, ppu.getScanline(), "run " + i + " of the last line of blanking");
                run(341);
            }

            assertEquals(311, ppu.getScanline(), "the pre-render line, which is 311 here");
        }

        @Test
        void aPalFrameGrowsByTheSameLines() {
            ppu.setOverclock(new Overclock(156, 0));

            assertEquals(PAL_DOTS_PER_FRAME + 156 * 341, measureFrame());
        }
    }

    private boolean vblankSet() {
        return (ppu.peek(PPUSTATUS) & VBLANK_FLAG) != 0;
    }

    /**
     * Turns rendering on and leaves the beam at the start of frame 1, the first odd one.
     * <p>
     * $2001 is ignored until the PPU has worked the first dot of the pre-render line, so the write
     * has to wait until then.
     */
    private void startRenderingOnTheFirstOddFrame() {
        runTo(261, 1);
        ppu.write(PPUMASK, 0x08);
        runTo(0, 0);
    }

    /**
     * A cartridge that writes down which dots and which addresses it was shown.
     */
    private static final class CountingMapper extends StubMapper {
        private final List<Integer> addresses = new ArrayList<>();
        private int dots;

        @Override
        public void ppuAddress(final int address) {
            addresses.add(address);
        }

        @Override
        public void ppuTick() {
            dots++;
        }

        List<Integer> addresses() {
            return addresses;
        }

        int dots() {
            return dots;
        }

        void clear() {
            addresses.clear();
            dots = 0;
        }
    }
}
