package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The /NMI line between the PPU and the CPU.
 * <p>
 * The line is a level, not a pulse: the PPU holds it asserted for as long as the VBlank flag and
 * the NMI enable bit are both set. Everything interesting follows from that -- enabling NMI in
 * the middle of VBlank produces an interrupt straight away, toggling the enable bit produces a
 * second one, and reading $2002 at the right moment produces none at all.
 *
 * @see <a href="https://www.nesdev.org/wiki/NMI">NESdev: NMI</a>
 */
class PPUNMITests extends PPUFixture {
    private static final int NMI_ENABLE = 0x80;

    @BeforeEach
    void setUp() {
        createWarmPPU();
    }

    @Nested
    @DisplayName("the line itself")
    class Line {
        @Test
        void staysReleasedWhenNMIIsDisabled() {
            runTo(245, 0);

            assertFalse(bus.level());
            assertEquals(List.of(), bus.edges(), "nothing should ever have moved it");
        }

        @Test
        void isAssertedWhenVBlankStartsWithNMIEnabled() {
            ppu.write(PPUCTRL, NMI_ENABLE);
            runTo(241, 1);

            assertFalse(bus.level(), "not until the flag actually goes up");

            ppu.tick();
            assertTrue(bus.level());
            assertEquals(List.of(true), bus.edges());
        }

        @Test
        void isReleasedWhenVBlankEnds() {
            ppu.write(PPUCTRL, NMI_ENABLE);
            runTo(261, 1);
            bus.reset();

            ppu.tick();

            assertFalse(bus.level());
            assertEquals(List.of(false), bus.edges());
        }

        @Test
        void isAssertedImmediatelyWhenEnabledMidVBlank() {
            runTo(245, 0);
            assertFalse(bus.level(), "the flag is up but nobody asked for an interrupt");

            ppu.write(PPUCTRL, NMI_ENABLE);

            assertTrue(bus.level());
            assertEquals(1, bus.assertions());
        }

        @Test
        void isAssertedAgainEachTimeItIsToggledDuringVBlank() {
            runTo(245, 0);

            ppu.write(PPUCTRL, NMI_ENABLE);
            ppu.write(PPUCTRL, 0x00);
            ppu.write(PPUCTRL, NMI_ENABLE);

            assertEquals(
                    List.of(true, false, true), bus.edges(),
                    "each fresh assertion is an edge a CPU would take an interrupt on"
            );
        }

        @Test
        void staysAssertedWhenTheEnableBitIsWrittenAgain() {
            runTo(245, 0);

            ppu.write(PPUCTRL, NMI_ENABLE);
            ppu.write(PPUCTRL, NMI_ENABLE | 0x03);

            assertEquals(List.of(true), bus.edges(), "one assertion, not two");
        }

        @Test
        void isReleasedByReadingStatus() {
            ppu.write(PPUCTRL, NMI_ENABLE);
            runTo(245, 0);
            assertTrue(bus.level());

            ppu.read(PPUSTATUS);

            assertFalse(bus.level());
            assertEquals(List.of(true, false), bus.edges());
        }

        @Test
        void neverMovesWhenTheFlagIsSuppressedOnItsOwnDot() {
            ppu.write(PPUCTRL, NMI_ENABLE);
            runTo(241, 1);
            ppu.read(PPUSTATUS);
            bus.reset();

            runTo(250, 0);

            assertEquals(List.of(), bus.edges(), "no flag means no interrupt for the whole frame");
        }
    }

    /**
     * One case wired up for real, so that the interleave in {@link NES#tick()} is pinned down and
     * not just the PPU's half of it. Everything above talks to the PPU directly and would go on
     * passing if the CPU stopped being told about the line at the right moment.
     */
    @Nested
    @DisplayName("through a real CPU")
    class Integration {
        private static final int NMI_HANDLER = 0xC100;
        private static final int SPIN_LOOP = 0xC015;

        @Test
        void vectorsThroughTheHandlerAFixedNumberOfCyclesAfterVBlank() {
            // SEI, wait for three VBlanks, then enable NMI and spin. Two of the waits are the
            // ones every game does, because the PPU ignores $2000 until it has warmed up. The
            // third is for this test: three dots to a CPU cycle means (241,1) can only be
            // observed from here on every third frame, and waiting one more VBlank puts the frame
            // being measured on one of them. Whichever wait ends last leaves the VBlank flag
            // cleared behind it, so enabling NMI produces no interrupt on the spot, and the spin
            // loop is a three cycle JMP, so the one that follows is taken at a boundary within
            // three cycles of being requested.
            var nes = new NES(Cart.load(rom(
                    0x78,              // SEI
                    0x2C, 0x02, 0x20,  // BIT $2002
                    0x10, 0xFB,        // BPL back to the BIT
                    0x2C, 0x02, 0x20,  // BIT $2002
                    0x10, 0xFB,        // BPL back to the BIT
                    0x2C, 0x02, 0x20,  // BIT $2002
                    0x10, 0xFB,        // BPL back to the BIT
                    0xA9, 0x80,        // LDA #$80
                    0x8D, 0x00, 0x20,  // STA $2000
                    0x4C, 0x15, 0xC0   // JMP $C015, a one instruction spin
            ), "nmi.nes"));

            var cpu = nes.getCPU();
            var ppu = nes.getPPU();

            // Get into the spin loop, which takes the best part of three frames.
            for (var steps = 0; cpu.getState().pc() != SPIN_LOOP; steps++) {
                if (steps > 100_000) {
                    throw new AssertionError("the ROM never reached its spin loop");
                }

                nes.step();
            }

            // Run up to the dot the flag goes up on, then find out how long the CPU takes.
            for (var dots = 0; !(ppu.getScanline() == 241 && ppu.getDot() == 1); dots += 3) {
                if (dots > 3 * DOTS_PER_FRAME) {
                    throw new AssertionError("the beam never landed on (241,1) between two cycles");
                }

                nes.tick();
            }

            var raisedAt = cpu.getState().cycles();

            while (cpu.getState().pc() != NMI_HANDLER) {
                nes.tick();

                if (cpu.getState().cycles() - raisedAt > 100) {
                    throw new AssertionError("the CPU never took the interrupt");
                }
            }

            // One cycle to finish sampling the line, up to three to finish the JMP, then the
            // seven cycle interrupt sequence.
            var latency = cpu.getState().cycles() - raisedAt;

            assertTrue(
                    latency >= 8 && latency <= 11,
                    "expected the vector fetch 8 to 11 cycles after VBlank, got " + latency
            );
        }

        /**
         * A mapper 0 image with {@code code} at $C000, the reset vector pointing at it and the
         * NMI vector pointing at {@link #NMI_HANDLER}.
         */
        private byte[] rom(final int... code) {
            var image = new byte[16 + 0x4000];

            image[0] = 'N';
            image[1] = 'E';
            image[2] = 'S';
            image[3] = 0x1A;
            image[4] = 1;  // one PRG bank, mirrored into both $8000 and $C000

            for (var i = 0; i < code.length; i++) {
                image[16 + i] = (byte) code[i];
            }

            // The handler is never reached as code, only as an address to check for.
            image[16 + 0x3FFA] = (byte) (NMI_HANDLER & 0xFF);
            image[16 + 0x3FFB] = (byte) (NMI_HANDLER >> 8);
            image[16 + 0x3FFC] = 0x00;
            image[16 + 0x3FFD] = (byte) 0xC0;

            return image;
        }
    }
}
