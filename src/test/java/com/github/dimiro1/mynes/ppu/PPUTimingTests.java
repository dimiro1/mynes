package com.github.dimiro1.mynes.ppu;

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
}
