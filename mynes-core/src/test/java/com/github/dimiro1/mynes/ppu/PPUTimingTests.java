package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where the beam is, and when the status flags move.
 * <p>
 * The blargg ROMs cover this to a single PPU clock, but only once the whole machine works well
 * enough to run one. These pin the same numbers down directly, so a break shows up as one failing
 * assertion rather than as a ROM printing a code.
 */
class PPUTimingTests extends PPUFixture {
    private static final int VBLANK_FLAG = 0x80;

    @BeforeEach
    void setUp() {
        createPPU();
    }

    @Nested
    @DisplayName("where the beam is")
    class BeamPosition {
        @Test
        void startsAtTheTopLeft() {
            assertEquals(0, ppu.getScanline());
            assertEquals(0, ppu.getDot());
        }

        @Test
        void countsDotsAlongAScanline() {
            run(200);

            assertEquals(0, ppu.getScanline());
            assertEquals(200, ppu.getDot());
        }

        @Test
        void wrapsToTheNextScanlineAfter341Dots() {
            run(341);

            assertEquals(1, ppu.getScanline());
            assertEquals(0, ppu.getDot());
        }

        @Test
        void wrapsToTheNextFrameAfter262Scanlines() {
            run(341 * 262);

            assertEquals(0, ppu.getScanline());
            assertEquals(0, ppu.getDot());
            assertEquals(1, ppu.getFrame());
        }
    }

    @Nested
    @DisplayName("the VBlank flag")
    class VBlankFlag {
        @Test
        void goesUpOnDotOneOfLine241() {
            runTo(241, 1);
            assertFalse(vblankSet(), "dot 1 has not done its work yet");

            ppu.tick();
            assertTrue(vblankSet(), "and now it has");
        }

        @Test
        void comesDownOnDotOneOfThePreRenderLine() {
            runTo(261, 1);
            assertTrue(vblankSet(), "still up right to the end of VBlank");

            ppu.tick();
            assertFalse(vblankSet());
        }

        @Test
        void staysUpForTheWholeOfVBlank() {
            runTo(241, 1);
            ppu.tick();

            // Every dot from (241,2) up to and including (261,0).
            for (var i = 0; i < 20 * 341 - 1; i++) {
                assertTrue(vblankSet(), "should still be up at " + ppu.getScanline() + "," + ppu.getDot());
                ppu.tick();
            }

            assertEquals(261, ppu.getScanline());
            assertEquals(1, ppu.getDot());
        }

        private boolean vblankSet() {
            return (ppu.peek(PPUSTATUS) & VBLANK_FLAG) != 0;
        }
    }

    @Nested
    @DisplayName("frame length")
    class FrameLength {
        @Test
        void isAlways89342DotsWithRenderingOff() {
            assertEquals(
                    List.of(DOTS_PER_FRAME, DOTS_PER_FRAME, DOTS_PER_FRAME, DOTS_PER_FRAME),
                    List.of(measureFrame(), measureFrame(), measureFrame(), measureFrame())
            );
        }

        @Test
        void dropsOneDotOnEveryOtherFrameWithRenderingOn() {
            startRenderingOnTheFirstOddFrame();

            assertEquals(
                    List.of(DOTS_PER_FRAME - 1, DOTS_PER_FRAME, DOTS_PER_FRAME - 1, DOTS_PER_FRAME),
                    List.of(measureFrame(), measureFrame(), measureFrame(), measureFrame())
            );
        }

        @Test
        void keepsTheFullLengthWhenRenderingIsSwitchedOffBeforeTheSkippedDot() {
            startRenderingOnTheFirstOddFrame();

            var dots = 0;

            while (!(ppu.getScanline() == 240 && ppu.getDot() == 0)) {
                ppu.tick();
                dots++;
            }

            ppu.write(PPUMASK, 0x00);

            do {
                ppu.tick();
                dots++;
            } while (!(ppu.getScanline() == 0 && ppu.getDot() == 0));

            assertEquals(DOTS_PER_FRAME, dots, "nothing is rendering, so there is no dot to skip");
        }

        /**
         * Turns rendering on and leaves the beam at the start of frame 1, the first odd one.
         * <p>
         * $2001 is ignored until the PPU has worked the first dot of the pre-render line, so the
         * write has to wait until then -- which is also the last moment it can be made without
         * the frame it lands in being the one measured.
         */
        private void startRenderingOnTheFirstOddFrame() {
            runTo(261, 1);
            ppu.write(PPUMASK, 0x08);
            runTo(0, 0);
        }
    }

    /**
     * Where the colour subcarrier has got to, which the chip carries because the chip is what
     * drifts: a scanline is 227 and a third colour cycles, so the alignment of pixel to cycle
     * slips a third of a cycle every line and the picture takes three lines to come back to itself.
     */
    @Nested
    @DisplayName("the colour phase")
    class ColourPhase {
        @Test
        void startsAtZero() {
            assertEquals(0, ppu.getFramePhase());
        }

        @Test
        void movesOnByOneEveryScanline() {
            // 262 lines with nothing rendering, and 262 mod 3 is 1.
            run(DOTS_PER_FRAME);

            assertEquals(1, ppu.getFramePhase());

            run(DOTS_PER_FRAME);

            assertEquals(2, ppu.getFramePhase());

            run(DOTS_PER_FRAME);

            assertEquals(0, ppu.getFramePhase(), "and three frames bring it back");
        }

        /**
         * The skipped dot is eight fewer samples of signal, and eight fewer out of twelve is four
         * more of them once the cycle wraps -- so the short frame moves the phase on by two where
         * every other one moves it by one. That is the whole of why the artefacts cycle every two
         * frames with rendering on and every three with it off.
         */
        @Test
        void movesOnByTwoAcrossTheSkippedDot() {
            startRenderingOnTheFirstOddFrame();

            var before = ppu.getFramePhase();

            do {
                ppu.tick();
            } while (!(ppu.getScanline() == 0 && ppu.getDot() == 0));

            assertEquals((before + 2) % 3, ppu.getFramePhase());
        }

        /**
         * A frame the beam runs extra lines of is a frame the subcarrier drifts further through, and
         * counting per line rather than per frame is what makes that fall out rather than need a
         * second table.
         */
        @Test
        void countsTheExtraLinesAnOverclockAdds() {
            ppu.setOverclock(new com.github.dimiro1.mynes.Overclock(3, 0));

            var dots = 0;

            do {
                ppu.tick();
                dots++;
            } while (!(ppu.getScanline() == 0 && ppu.getDot() == 0));

            assertEquals(DOTS_PER_FRAME + 3 * 341, dots, "three lines run twice");
            assertEquals((262 + 3) % 3, ppu.getFramePhase());
        }

        /**
         * Turns rendering on and leaves the beam at the start of frame 1, the first odd one. The
         * same trick the frame length test above uses, and here for the same reason.
         */
        private void startRenderingOnTheFirstOddFrame() {
            runTo(261, 1);
            ppu.write(PPUMASK, 0x08);
            runTo(0, 0);
        }
    }

    /**
     * The 2C07, which differs from everything above in three ways: fifty more scanlines, all of
     * them vertical blank, and no odd frame ever losing a dot.
     */
    @Nested
    @DisplayName("a PAL machine")
    class PALTiming {
        @BeforeEach
        void setUp() {
            createPPU(Region.PAL);
        }

        @Test
        void wrapsToTheNextFrameAfter312Scanlines() {
            run(PAL_DOTS_PER_FRAME);

            assertEquals(0, ppu.getScanline());
            assertEquals(0, ppu.getDot());
            assertEquals(1, ppu.getFrame());
        }

        @Test
        void startsVBlankOnTheSameLineAsAnNTSCMachine() {
            // The picture is the same 240 lines and the blanking after it is what grew, so the
            // flag goes up where it always did and stays up three and a half times as long.
            runTo(241, 1);
            assertFalse(vblankSet(), "dot 1 has not done its work yet");

            ppu.tick();
            assertTrue(vblankSet(), "and now it has");
        }

        @Test
        void clearsTheStatusFlagsOnLine311() {
            runTo(311, 1);
            assertTrue(vblankSet(), "still up right to the end of a much longer VBlank");

            ppu.tick();
            assertFalse(vblankSet());
        }

        @Test
        void isAlways106392DotsWhetherOrNotAnythingIsRendering() {
            assertEquals(
                    List.of(PAL_DOTS_PER_FRAME, PAL_DOTS_PER_FRAME, PAL_DOTS_PER_FRAME),
                    List.of(measureFrame(), measureFrame(), measureFrame()),
                    "with rendering off");

            runTo(311, 1);
            ppu.write(PPUMASK, 0x08);
            runTo(0, 0);

            // The 2C02 would drop a dot from every other one of these. PAL corrects its burst
            // phase by alternating it line by line instead, so there is nothing to compensate for.
            assertEquals(
                    List.of(PAL_DOTS_PER_FRAME, PAL_DOTS_PER_FRAME, PAL_DOTS_PER_FRAME),
                    List.of(measureFrame(), measureFrame(), measureFrame()),
                    "and with it on");
        }

        private boolean vblankSet() {
            return (ppu.peek(PPUSTATUS) & VBLANK_FLAG) != 0;
        }
    }
}
