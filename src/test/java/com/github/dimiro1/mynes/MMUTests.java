package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper0;
import com.github.dimiro1.mynes.mappers.Mirroring;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

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
        var mapper = new Mapper0(new byte[0x4000], new byte[0x2000], Mirroring.HORIZONTAL);
        ppu = new PPU(level -> { }, mapper);
        mmu = new MMU(ppu, new APU(level -> { }, level -> { }), mapper, null, null);
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
            // OAM is the only PPU register pair that reads back what was written without any
            // buffering or side effects, so the round trip has to go through $2003 and $2004.
            // $3FF3 and $3FFC are the last mirrors of those two.
            mmu.write(0x3FF3, 0x10);
            mmu.write(0x3FFC, 0x42);

            mmu.write(0x2003, 0x10);
            assertEquals(0x42, mmu.read(0x2004), "the write through the last mirror should land");
        }

        @Test
        void peekDoesNotTouchRegisters() {
            mmu.write(0x0100, 0x99);

            assertEquals(0x99, mmu.peek(0x0100), "plain memory reads back normally");
            assertEquals(0, mmu.peek(0x2002), "PPU registers must not be read for real");
            assertEquals(0, mmu.peek(0x4016), "controller ports must not be clocked");
        }
    }

    /**
     * The seam a debugger's watchpoints hang off. What matters is not only that it sees CPU writes
     * but exactly which writes it does not see, since that is the difference between a watchpoint
     * that misses something and one whose limits are known.
     */
    @Nested
    class WriteListener {
        private final List<String> seen = new ArrayList<>();

        private void record() {
            mmu.setWriteListener((address, value) ->
                    seen.add(String.format("%04X=%02X", address, value)));
        }

        @Test
        void seesEveryByteTheCpuWrites() {
            record();

            mmu.write(0x0123, 0x5A);
            mmu.write(0x8000, 0x99);

            assertEquals(List.of("0123=5A", "8000=99"), seen);
        }

        @Test
        void seesTheMirrorItWasWrittenThroughRatherThanTheRamBehindIt() {
            record();

            mmu.write(0x1FFF, 0x42);

            assertEquals(List.of("1FFF=42"), seen, "where the CPU put it is what a watch is set on");
        }

        @Test
        void isToldTheValueBeforeItLands() {
            mmu.write(0x0100, 0x11);

            var wasThere = new int[1];
            mmu.setWriteListener((address, value) -> wasThere[0] = mmu.peek(address));

            mmu.write(0x0100, 0x22);

            assertEquals(0x11, wasThere[0], "the byte is handed over because it cannot be read yet");
            assertEquals(0x22, mmu.peek(0x0100), "and it lands afterwards");
        }

        @Test
        void readingDoesNotFireIt() {
            mmu.write(0x0100, 0x99);
            record();

            mmu.read(0x0100);
            mmu.peek(0x0100);
            mmu.read(0x2002);

            assertEquals(List.of(), seen);
        }

        /**
         * The documented hole, pinned so that closing it is a decision rather than an accident: a
         * sprite DMA copies into OAM by calling the PPU directly, so a watch on $2004 sleeps
         * through all 256 of the writes it makes.
         */
        @Test
        void aSpriteDmaIsNotSeen() {
            for (var i = 0; i < 0x100; i++) {
                mmu.write(0x0200 + i, i);
            }

            record();
            mmu.write(OAM_DMA, 0x02);

            while (mmu.tickDMA(seen.size())) {
                if (seen.size() > 1) {
                    break;
                }
            }

            assertEquals(List.of("4014=02"), seen, "the register write, and none of the transfer");
        }

        @Test
        void clearingItStopsTheTelling() {
            record();
            mmu.write(0x0100, 0x11);

            mmu.setWriteListener(null);
            mmu.write(0x0100, 0x22);

            assertEquals(List.of("0100=11"), seen);
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
                    0xFF, readOAM(0xFF),
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

            assertEquals(0xFF, readOAM(0xFF));
        }

        @Test
        void doesNotStallWhenIdle() {
            assertFalse(mmu.tickDMA(0), "no transfer requested");
            assertFalse(mmu.isDMAInProgress());
        }

        /**
         * Reads one byte of OAM.
         * <p>
         * A whole page transfer leaves OAMADDR back where it started, so the address has to be
         * pointed at the byte of interest before $2004 will show it.
         */
        private int readOAM(final int address) {
            mmu.write(0x2003, address);
            return ppu.read(OAM_DATA_REGISTER);
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
