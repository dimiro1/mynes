package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper0;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for address decoding and OAM DMA.
 * <p>
 * Neither is reachable from the ROM based tests: the blargg and nestest ROMs never write $4014,
 * so the DMA engine has never actually run.
 */
public class MMUTests {
    private static final int OAM_DMA = 0x4014;
    private static final int OAM_DATA_REGISTER = 0x04;

    // One halt cycle plus 256 read/write pairs, and one more when the halt lands on an odd cycle.
    private static final int ALIGNED_DMA_CYCLES = 513;
    private static final int UNALIGNED_DMA_CYCLES = 514;

    private PPU ppu;
    private MMU mmu;

    @BeforeEach
    void setUp() {
        var mapper = new Mapper0(new byte[0x4000], new byte[0x2000]);
        ppu = new PPU(null, mapper);
        mmu = new MMU(ppu, mapper, null, null);
    }

    @Nested
    class AddressDecoding {
        @ParameterizedTest
        @ValueSource(ints = {0x0000, 0x0800, 0x1000, 0x1800})
        void internalRamIsMirroredEveryTwoKilobytes(final int base) {
            mmu.write(base + 0x0123, 0x5A);

            assertEquals(0x5A, mmu.read(0x0123), "should alias down to the real RAM");
            assertEquals(0x5A, mmu.read(0x0923));
            assertEquals(0x5A, mmu.read(0x1123));
            assertEquals(0x5A, mmu.read(0x1923));
        }

        @Test
        void theLastMirroredByteIsTheLastByteOfRam() {
            mmu.write(0x1FFF, 0x42);

            assertEquals(0x42, mmu.read(0x07FF));
        }

        @Test
        void ppuRegistersAreMirroredEveryEightBytes() {
            mmu.write(0x2000, 0x11);
            assertEquals(0x11, mmu.read(0x3FF8), "the last mirror of $2000");

            mmu.write(0x3FFF, 0x22);
            assertEquals(0x22, mmu.read(0x2007), "the last mirror of $2007");
        }

        @Test
        void peekDoesNotTouchRegisters() {
            mmu.write(0x0100, 0x99);

            assertEquals(0x99, mmu.peek(0x0100), "plain memory reads back normally");
            assertEquals(0, mmu.peek(0x2002), "PPU registers must not be read for real");
            assertEquals(0, mmu.peek(0x4016), "controller ports must not be clocked");
        }
    }

    @Nested
    class OamDma {
        @Test
        void transfersAPageIntoOamAndStallsFor513CyclesWhenAligned() {
            fillPage(0x02);

            mmu.write(OAM_DMA, 0x02);
            var stalled = runToCompletion(0);

            assertEquals(ALIGNED_DMA_CYCLES, stalled);
            assertFalse(mmu.isDMAInProgress(), "the transfer should have finished");
            assertEquals(
                    0xFF, ppu.read(OAM_DATA_REGISTER),
                    "the last byte of the page should be the last thing written to OAMDATA"
            );
        }

        @Test
        void stallsForOneMoreCycleWhenTheHaltLandsOnAnOddCycle() {
            fillPage(0x02);

            mmu.write(OAM_DMA, 0x02);
            var stalled = runToCompletion(1);

            assertEquals(UNALIGNED_DMA_CYCLES, stalled);
        }

        @Test
        void copiesFromTheRequestedPage() {
            // Two candidate pages, so a transfer from the wrong one is visible.
            for (var i = 0; i < 0x100; i++) {
                mmu.write(0x0200 + i, 0xAA);
                mmu.write(0x0300 + i, i);
            }

            mmu.write(OAM_DMA, 0x03);
            runToCompletion(0);

            assertEquals(0xFF, ppu.read(OAM_DATA_REGISTER));
        }

        @Test
        void doesNotStallWhenIdle() {
            assertFalse(mmu.tickDMA(0), "no transfer requested");
            assertFalse(mmu.isDMAInProgress());
        }

        private void fillPage(final int page) {
            for (var i = 0; i < 0x100; i++) {
                mmu.write((page << 8) | i, i);
            }
        }

        /**
         * Ticks the DMA engine until it lets go of the bus.
         *
         * @param firstCycle the CPU cycle counter the first stalled cycle happens on.
         * @return how many cycles the CPU was stalled for.
         */
        private int runToCompletion(final long firstCycle) {
            var stalled = 0;

            while (mmu.tickDMA(firstCycle + stalled)) {
                stalled++;

                if (stalled > 1000) {
                    throw new AssertionError("DMA never finished");
                }
            }

            return stalled;
        }
    }

    @Nested
    class CpuIntegration {
        @Test
        void writingOamDmaStallsTheCpuThenLetsItCarryOn() {
            // LDA #$02 / STA $4014 / NOP, run from the reset vector.
            var nes = new NES(Cart.load(rom(0xA9, 0x02, 0x8D, 0x14, 0x40, 0xEA), "dma.nes"));
            var cpu = nes.getCPU();

            cpu.step();  // the reset sequence
            cpu.step();  // LDA #$02

            var beforeStore = cpu.getState().cycles();
            cpu.step();  // STA $4014, which starts the transfer

            var storeCycles = cpu.getState().cycles() - beforeStore;
            assertEquals(4, storeCycles, "the store itself is four cycles");
            assertTrue(nes.getBus().isDMAInProgress(), "the write should have armed the transfer");

            var beforeNop = cpu.getState().cycles();
            cpu.step();  // the NOP, which cannot start until the transfer is done

            var stalledPlusNop = cpu.getState().cycles() - beforeNop;
            var expectedStall = (beforeNop & 1) == 0 ? ALIGNED_DMA_CYCLES : UNALIGNED_DMA_CYCLES;

            assertFalse(nes.getBus().isDMAInProgress(), "the transfer should have finished");
            assertEquals(
                    expectedStall + 2, stalledPlusNop,
                    "expected the stall plus the two cycle NOP"
            );
            assertEquals(0xC006, cpu.getState().pc(), "and then execution carries on");
        }

        /**
         * Builds a mapper 0 image whose single PRG bank holds {@code code} at $C000, with the
         * reset vector pointing at it.
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

            // Reset vector at $FFFC, which is the last-but-four byte of the bank.
            image[16 + 0x3FFC] = 0x00;
            image[16 + 0x3FFD] = (byte) 0xC0;

            return image;
        }
    }
}
