package com.github.dimiro1.mynes.ppu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The window the PPU ignores half of its registers in after power on or reset.
 * <p>
 * A 2C02 starts rendering straight away but holds an internal reset signal over $2000, $2001,
 * $2005 and $2006 until the beam first reaches the pre-render line -- around 29658 CPU cycles.
 * Everything else works from the first cycle. This is the whole reason a game's startup code waits
 * for two VBlanks before it does anything, and the reason one that skipped the wait would come up
 * with no scroll position and no NMI.
 * <p>
 * The fixture is deliberately cold here. Every other PPU test warms the chip up first, because
 * every other PPU test is about what happens afterwards.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_power_up_state">NESdev: PPU power up state</a>
 */
class PPUPowerUpTests extends PPUFixture {
    /**
     * The scanline the internal reset signal is released on.
     */
    private static final int PRE_RENDER_LINE = 261;

    @BeforeEach
    void setUp() {
        createPPU();
    }

    @Nested
    @DisplayName("while the PPU is warming up")
    class WhileWarmingUp {
        @Test
        void controlWritesAreIgnored() {
            ppu.write(PPUCTRL, 0x03);

            assertEquals(0, ppu.getT(), "the nametable bits never reached the staging register");
        }

        @Test
        void maskWritesAreIgnored() {
            ppu.write(PPUMASK, 0x1E);
            run(4);

            assertFalse(ppu.isRenderingEnabled(), "rendering cannot be turned on this early");
        }

        @Test
        void scrollWritesAreIgnored() {
            ppu.write(PPUSCROLL, 0x7D);
            ppu.write(PPUSCROLL, 0x5E);

            assertEquals(0, ppu.getT());
            assertEquals(0, ppu.getFineX());
        }

        @Test
        void addressWritesAreIgnored() {
            ppu.write(PPUADDR, 0x21);
            ppu.write(PPUADDR, 0x08);
            run(ADDRESS_UPDATE_DOTS);

            assertEquals(0, ppu.getT());
            assertEquals(0, ppu.getV());
        }

        @Test
        void theSharedWriteLatchDoesNotToggleEither() {
            ppu.write(PPUSCROLL, 0x7D);
            assertFalse(ppu.isWriteLatchSet(), "an ignored write is not half of a pair");

            ppu.write(PPUADDR, 0x21);
            assertFalse(ppu.isWriteLatchSet());
        }

        @ParameterizedTest(name = "an ignored write to register {0} still charges the open bus")
        @ValueSource(ints = {PPUCTRL, PPUMASK, PPUSCROLL, PPUADDR})
        void anIgnoredWriteStillPutsTheByteOnTheDataPins(final int register) {
            ppu.write(register, 0x5A);

            assertEquals(0x5A, ppu.read(PPUCTRL), "the byte is on the pins whatever the PPU does");
        }

        @Test
        void objectAttributeMemoryWorksFromTheFirstCycle() {
            ppu.write(OAMADDR, 0x10);
            ppu.write(OAMDATA, 0xAA);

            ppu.write(OAMADDR, 0x10);
            assertEquals(0xAA, ppu.read(OAMDATA));
        }

        @Test
        void dataWritesWorkFromTheFirstCycle() {
            // The address register cannot be set yet, so this lands wherever the PPU powers up
            // pointing, which is the bottom of the pattern tables.
            ppu.write(PPUDATA, 0x3C);

            assertEquals(0x3C, mapper.charRead(0x0000));
        }
    }

    @Nested
    @DisplayName("once the PPU has warmed up")
    class OnceWarm {
        @Test
        void controlWritesLand() {
            warmToThePreRenderLine();
            ppu.write(PPUCTRL, 0x03);

            assertEquals(0x0C00, ppu.getT());
        }

        @Test
        void maskWritesLand() {
            warmToThePreRenderLine();
            ppu.write(PPUMASK, 0x1E);
            run(4);

            assertTrue(ppu.isRenderingEnabled());
        }

        @Test
        void scrollWritesLand() {
            warmToThePreRenderLine();
            ppu.write(PPUSCROLL, 0x7D);

            assertEquals(0x000F, ppu.getT());
            assertEquals(5, ppu.getFineX());
            assertTrue(ppu.isWriteLatchSet());
        }

        @Test
        void addressWritesLand() {
            warmToThePreRenderLine();
            setVRAMAddress(0x2108);

            assertEquals(0x2108, ppu.getV());
        }

        @Test
        void theSignalGoesWithTheFirstDotOfThePreRenderLine() {
            runTo(PRE_RENDER_LINE, 0);
            ppu.write(PPUCTRL, 0x03);

            assertEquals(0, ppu.getT(), "the beam is there, but the dot has not been worked yet");

            ppu.tick();
            ppu.write(PPUCTRL, 0x03);

            assertEquals(0x0C00, ppu.getT(), "and working it is what releases the signal");
        }

        /**
         * Stops one dot into the pre-render line, which is the first dot the four registers the
         * internal reset signal covers are alive on.
         */
        private void warmToThePreRenderLine() {
            runTo(PRE_RENDER_LINE, 1);
        }
    }

    @Nested
    @DisplayName("what a reset does")
    class Reset {
        @Test
        void armsTheWindowAgain() {
            warmUp();
            ppu.reset();

            ppu.write(PPUCTRL, 0x03);
            ppu.write(PPUSCROLL, 0x7D);

            assertEquals(0, ppu.getT(), "the registers are dead again");
            assertEquals(0, ppu.getFineX());
        }

        @Test
        void clearsTheRegistersTheResetSignalReaches() {
            warmUp();

            ppu.write(PPUMASK, 0x1E);
            ppu.write(PPUSCROLL, 0x7D);  // takes the write latch, and leaves it taken

            runTo(245, 0);
            ppu.write(PPUCTRL, 0x83);    // NMI enabled mid VBlank, so the line goes down at once
            assertTrue(bus.level());

            ppu.reset();

            assertEquals(0, ppu.getT(), "the staging address goes");
            assertEquals(0, ppu.getFineX(), "and the fine X scroll with it");
            assertFalse(ppu.isWriteLatchSet(), "and the half finished write");
            assertFalse(ppu.isRenderingEnabled(), "and rendering is off again");
            assertFalse(bus.level(), "and the NMI enable bit, so the line is released");
        }

        @Test
        void emptiesTheReadBuffer() {
            warmUp();
            writeVRAM(0x2000, 0xAA);

            setVRAMAddress(0x2000);
            ppu.read(PPUDATA);  // leaves the buffer holding the byte at $2000

            ppu.reset();

            assertEquals(0x00, ppu.peek(PPUDATA), "the buffer came up empty");
        }

        @Test
        void putsTheBeamBackAtTheTopLeft() {
            warmUp();
            runTo(120, 200);

            ppu.reset();

            assertEquals(0, ppu.getScanline());
            assertEquals(0, ppu.getDot());
        }

        @Test
        void leavesAloneWhatTheResetSignalDoesNotReach() {
            warmUp();

            setVRAMAddress(0x2108);
            ppu.write(OAMADDR, 0x10);
            ppu.write(OAMDATA, 0xAA);
            ppu.write(OAMADDR, 0x10);

            var frame = ppu.getFrame();

            ppu.reset();

            assertEquals(0x2108, ppu.getV(), "the VRAM address survives");
            assertEquals(0xAA, ppu.read(OAMDATA), "and OAM, at the address OAMADDR still holds");
            assertEquals(frame, ppu.getFrame(), "and the frame counter, which is a clock");
        }

        @Test
        void leavesTheVBlankFlagAlone() {
            warmUp();
            runTo(245, 0);

            ppu.reset();

            assertEquals(0x80, ppu.peek(PPUSTATUS) & 0x80);
        }

        @Test
        void leavesPaletteRamAlone() {
            warmUp();
            writeVRAM(0x3F01, 0x25);

            ppu.reset();
            warmUp();

            setVRAMAddress(0x3F01);
            assertEquals(0x25, ppu.read(PPUDATA) & 0x3F);
        }
    }
}
