package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper0;
import com.github.dimiro1.mynes.mappers.Mirroring;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The delta modulation channel and the DMA it reads its samples with.
 * <p>
 * The DMC is the only part of the APU that touches memory, and it does it by taking the bus off
 * the CPU for four cycles at a time -- the same stall OAM DMA uses, which is why this drives
 * {@link MMU#tickDMA} directly the way {@code MMUTests.OamDma} does, over a real
 * {@link Mapper0} with known bytes in it. What comes back out is a seven bit level walked two
 * units at a time, one bit of the sample per step.
 */
class APUDMCTests {
    /**
     * A full 32KB of PRG, so that a CPU address maps straight onto an offset in it and a sample
     * can be put at whatever address a test wants to name.
     */
    private static final int PRG_SIZE = 0x8000;

    /**
     * How long the DMC's fetch holds the CPU off the bus.
     */
    private static final int FETCH_CYCLES = 4;

    /**
     * An OAM transfer whose halt cycle landed on an even cycle: one halt plus 256 read/write
     * pairs.
     */
    private static final int ALIGNED_OAM_CYCLES = 513;

    /**
     * The fastest of the sixteen rates, in CPU cycles per bit of the sample.
     */
    private static final int FASTEST_RATE = 54;

    private byte[] prg;
    private APU apu;
    private MMU mmu;

    /**
     * The level of the DMC's end of the /IRQ line, as the APU last left it.
     */
    private boolean irqLine;

    @BeforeEach
    void setUp() {
        prg = new byte[PRG_SIZE];

        var mapper = new Mapper0(prg, new byte[0x2000], Mirroring.HORIZONTAL);
        apu = new APU(level -> { }, level -> irqLine = level);
        mmu = new MMU(new PPU(level -> { }, mapper), apu, mapper, null, null);
    }

    /**
     * Puts bytes into the cartridge at a CPU address.
     */
    private void putSample(final int address, final int... bytes) {
        for (var i = 0; i < bytes.length; i++) {
            prg[(address + i) & 0x7FFF] = (byte) bytes[i];
        }
    }

    /**
     * Runs the machine for a run of CPU cycles, offering the bus to the DMA on each of them the
     * way {@link CPU#tick()} does and clocking the APU either way -- the APU does not stall with
     * the CPU.
     */
    private void run(final int cycles) {
        for (var i = 0; i < cycles; i++) {
            mmu.tickDMA(i);
            apu.tick();
        }
    }

    /**
     * Arms a one byte sample at $C000 with the fastest rate, and starts it.
     */
    private void armOneByteSample(final int flags) {
        apu.write(0x4010, flags | 0x0F);
        apu.write(0x4012, 0x00);
        apu.write(0x4013, 0x00);
        apu.write(0x4015, 0x10);
    }

    @Nested
    @DisplayName("the sample fetch")
    class Fetch {
        @Test
        void holdsTheCpuOffTheBusForFourCycles() {
            armOneByteSample(0x00);

            assertTrue(apu.isDMCFetchPending(), "there is a sample and no byte in hand");

            for (var i = 0; i < FETCH_CYCLES - 1; i++) {
                assertTrue(mmu.tickDMA(i), "cycle " + i);
                assertTrue(apu.isDMCFetchPending(), "still waiting at cycle " + i);
            }

            assertTrue(mmu.tickDMA(FETCH_CYCLES - 1));
            assertFalse(apu.isDMCFetchPending(), "the last cycle is the read");
            assertFalse(mmu.tickDMA(FETCH_CYCLES), "and the bus goes back to the CPU");
        }

        @Test
        void doesNotAskForAnythingWithNoSampleToPlay() {
            assertFalse(apu.isDMCFetchPending());
            assertFalse(mmu.tickDMA(0));
        }

        @Test
        void readsTheSampleFromTheAddressTheProgramGave() {
            putSample(0xC000, 0x00);
            putSample(0xC040, 0xFF);  // the byte a $4012 of 1 points at

            apu.write(0x4011, 0x40);
            apu.write(0x4010, 0x0F);  // fastest rate, no loop, no interrupt
            apu.write(0x4012, 0x01);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            // Eight clocks go by before the byte reaches the shift register, eight more play it,
            // and the seventeenth finds nothing left and goes quiet.
            run(17 * FASTEST_RATE);

            assertEquals(0x50, apu.dmcOutput(), "eight one-bits at two units each");
        }

        @Test
        void spellsTheAddressAndLengthTheWayTheRegistersDo() {
            apu.write(0x4012, 0x02);  // $C000 plus two 64 byte steps
            apu.write(0x4013, 0x03);  // three 16 byte steps, plus one
            apu.write(0x4015, 0x10);

            assertEquals(0xC080, apu.dmcFetchAddress());
            assertEquals(49, apu.dmcBytesRemaining());
        }

        @Test
        void wrapsTheAddressFromTheEndOfTheCartridgeBackToItsStart() {
            apu.write(0x4012, 0xFF);  // $FFC0
            apu.write(0x4013, 0x04);  // 65 bytes, which is one past the end
            apu.write(0x4015, 0x10);

            assertEquals(0xFFC0, apu.dmcFetchAddress());

            for (var i = 0; i < 63; i++) {
                apu.finishDMCFetch(0);
            }

            assertEquals(0xFFFF, apu.dmcFetchAddress(), "the last byte of the cartridge");

            apu.finishDMCFetch(0);
            assertEquals(0x8000, apu.dmcFetchAddress(), "and then round to the start of it");
        }

        @Test
        void startsTheSampleAgainWhenTheLoopFlagIsSet() {
            apu.write(0x4010, 0x40);  // loop
            apu.write(0x4012, 0x01);  // $C040
            apu.write(0x4013, 0x00);  // one byte
            apu.write(0x4015, 0x10);

            apu.finishDMCFetch(0);

            assertEquals(0xC040, apu.dmcFetchAddress(), "back to the top of the sample");
            assertEquals(1, apu.dmcBytesRemaining());
        }
    }

    @Nested
    @DisplayName("sharing the bus with OAM DMA")
    class Arbitration {
        @Test
        void takesTheFirstFourCyclesOfATransferThatStartsAtTheSameTime() {
            armOneByteSample(0x00);
            mmu.write(0x4014, 0x02);

            assertEquals(ALIGNED_OAM_CYCLES + FETCH_CYCLES, runDMAToCompletion());
        }

        @Test
        void freezesATransferAlreadyUnderWay() {
            mmu.write(0x4014, 0x02);

            var stalled = 0;
            while (stalled < 100) {
                assertTrue(mmu.tickDMA(stalled));
                stalled++;
            }

            armOneByteSample(0x00);

            for (var i = 0; i < FETCH_CYCLES; i++) {
                assertTrue(mmu.tickDMA(stalled + i));
            }

            assertFalse(apu.isDMCFetchPending(), "the fetch went first");

            stalled += FETCH_CYCLES;
            while (mmu.tickDMA(stalled)) {
                stalled++;

                if (stalled > 2000) {
                    throw new AssertionError("the transfers never finished");
                }
            }

            assertEquals(ALIGNED_OAM_CYCLES + FETCH_CYCLES, stalled,
                    "the page still went across, four cycles later than it would have");
        }

        /**
         * @return how many cycles the CPU was held off the bus for, both transfers together.
         */
        private int runDMAToCompletion() {
            var stalled = 0;

            while (mmu.tickDMA(stalled)) {
                stalled++;

                if (stalled > 2000) {
                    throw new AssertionError("the transfers never finished");
                }
            }

            return stalled;
        }
    }

    @Nested
    @DisplayName("the output unit")
    class OutputUnit {
        @Test
        void takesAWriteToTheLevelRegisterStraightOnTheChin() {
            apu.write(0x4011, 0x55);
            assertEquals(0x55, apu.dmcOutput());

            apu.write(0x4011, 0xFF);
            assertEquals(0x7F, apu.dmcOutput(), "seven bits of it -- the top one is not wired");
        }

        /**
         * The whole point of the rate table, and what blargg's {@code 8-dmc_rates} measures. The
         * sample here is $AA, whose bits alternate, so every step is visible as a change.
         */
        @ParameterizedTest
        @CsvSource({
                "0, 428",
                "1, 380",
                "8, 190",
                "15, 54",
        })
        void stepsTheLevelOnceEveryTabulatedRate(final int index, final int cpuCycles) {
            putSample(0xC000, 0xAA);

            apu.write(0x4011, 0x40);
            apu.write(0x4010, 0x40 | index);  // looping, so the sample never runs out
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            // Eight clocks of silence before the first byte reaches the shift register.
            run(9 * cpuCycles);

            var changes = 0;
            var previous = apu.dmcOutput();

            for (var i = 0; i < 10 * cpuCycles; i++) {
                mmu.tickDMA(i);
                apu.tick();

                if (apu.dmcOutput() != previous) {
                    changes++;
                }
                previous = apu.dmcOutput();
            }

            assertEquals(10, changes);
        }

        @Test
        void stepsUpByTwoForAOneAndDownByTwoForAZero() {
            putSample(0xC000, 0x0F);  // 0000 1111, and the low bit goes first

            apu.write(0x4011, 0x40);
            apu.write(0x4010, 0x0F);
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            run(12 * FASTEST_RATE);
            assertEquals(0x48, apu.dmcOutput(), "four one-bits up");

            run(4 * FASTEST_RATE);
            assertEquals(0x40, apu.dmcOutput(), "and four zero-bits back down again");
        }

        @Test
        void clampsAtTheTopRatherThanWrapping() {
            putSample(0xC000, 0xFF);

            apu.write(0x4011, 0x7E);  // 126, and the step is two
            apu.write(0x4010, 0x4F);  // looping, fastest rate
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            run(100 * FASTEST_RATE);

            assertEquals(0x7E, apu.dmcOutput());
        }

        @Test
        void clampsAtTheBottomRatherThanWrapping() {
            putSample(0xC000, 0x00);

            apu.write(0x4011, 0x01);
            apu.write(0x4010, 0x4F);
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            run(100 * FASTEST_RATE);

            assertEquals(0x01, apu.dmcOutput());
        }

        @Test
        void holdsTheLevelWhereItIsWhenTheSampleRunsOut() {
            putSample(0xC000, 0xFF);

            apu.write(0x4011, 0x40);
            apu.write(0x4010, 0x0F);
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            run(200 * FASTEST_RATE);

            assertEquals(0x50, apu.dmcOutput(),
                    "silence freezes the speaker rather than dropping it to zero, which would click");
        }
    }

    @Nested
    @DisplayName("$4015 and the interrupt")
    class Status {
        @Test
        void saysWhetherThereIsAnythingLeftToPlay() {
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x01);  // 17 bytes
            apu.write(0x4015, 0x10);

            assertEquals(0x10, apu.readStatus() & 0x10);

            apu.write(0x4015, 0x00);
            assertEquals(0, apu.readStatus() & 0x10, "and clearing the bit stops the sample");
            assertEquals(0, apu.dmcBytesRemaining());
        }

        @Test
        void startingAnAlreadyPlayingSampleDoesNotRestartIt() {
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x01);  // 17 bytes
            apu.write(0x4015, 0x10);

            apu.finishDMCFetch(0);
            assertEquals(16, apu.dmcBytesRemaining());

            apu.write(0x4015, 0x10);
            assertEquals(16, apu.dmcBytesRemaining(), "it was already going");
        }

        @Test
        void raisesTheInterruptWhenASampleEndsWithItEnabled() {
            apu.write(0x4010, 0x80);  // interrupt enabled, no loop
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            apu.finishDMCFetch(0);

            assertTrue(apu.isDMCIRQRaised());
            assertTrue(irqLine);
        }

        @Test
        void doesNotRaiseItWithTheEnableBitClear() {
            armOneByteSample(0x00);
            apu.finishDMCFetch(0);

            assertFalse(apu.isDMCIRQRaised());
            assertFalse(irqLine);
        }

        @Test
        void doesNotRaiseItOnASampleThatLoops() {
            apu.write(0x4010, 0xC0);  // interrupt enabled and looping, which never ends
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            apu.finishDMCFetch(0);
            apu.finishDMCFetch(0);

            assertFalse(apu.isDMCIRQRaised());
        }

        @Test
        void isNotAcknowledgedByReadingTheStatusRegister() {
            apu.write(0x4010, 0x80);
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);
            apu.finishDMCFetch(0);

            assertEquals(0x80, apu.readStatus() & 0x80, "bit 7 is the DMC's interrupt");
            assertTrue(apu.isDMCIRQRaised(),
                    "unlike the frame counter's, reading does not clear it");

            apu.write(0x4015, 0x00);
            assertFalse(apu.isDMCIRQRaised(), "writing $4015 is what does");
            assertFalse(irqLine);
        }

        @Test
        void isAlsoAcknowledgedByClearingTheEnableBit() {
            apu.write(0x4010, 0x80);
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);
            apu.finishDMCFetch(0);

            apu.write(0x4010, 0x00);

            assertFalse(apu.isDMCIRQRaised());
            assertFalse(irqLine);
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {
        @Test
        void keepsOnlyTheBottomBitOfTheLevel() {
            apu.write(0x4011, 0x55);
            apu.reset();

            assertEquals(1, apu.dmcOutput(), "the documented behaviour, odd as it looks");

            apu.write(0x4011, 0x54);
            apu.reset();

            assertEquals(0, apu.dmcOutput());
        }

        @Test
        void stopsTheSample() {
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x01);
            apu.write(0x4015, 0x10);

            apu.reset();

            assertEquals(0, apu.dmcBytesRemaining());
            assertFalse(apu.isDMCFetchPending());
        }
    }
}
