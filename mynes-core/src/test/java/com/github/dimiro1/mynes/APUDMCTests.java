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
 * {@link MMU#beginDMACycle} directly the way {@code MMUTests.OamDma} does, over a real
 * {@link Mapper0} with known bytes in it. What comes back out is a seven bit level walked two
 * units at a time, one bit of the sample per step.
 */
class APUDMCTests {
    /**
     * One cycle of whatever the transfer engine wants, standing in for the CPU.
     * <p>
     * A halt cycle is answered as though the CPU had spent it reading, which is what it does
     * except in the handful of cycles an instruction spends writing.
     *
     * @return true if the CPU would have been held off the bus.
     */
    private static boolean stallCycle(final MMU mmu, final long cpuCycle) {
        var kind = mmu.beginDMACycle(cpuCycle);

        if (kind == CPUBus.DMACycle.HALT) {
            mmu.endHaltCycle(false);
        }

        return kind != CPUBus.DMACycle.NONE;
    }

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
            stallCycle(mmu, i);
            apu.tick();
        }
    }

    /**
     * How long a sample started by a $4015 write takes to ask for its first byte, from a write on
     * a get cycle. One on a put waits a cycle less, because both are aiming at the same cycle of
     * the chip's own clock; these tests all write on a get.
     *
     * @see Fetch#doesNotAskForTheFirstByteUntilTheChannelHasLoaded
     */
    private static final int LOAD_CYCLES = 3;

    /**
     * Arms a one byte sample at $C000 with the fastest rate, starts it, and waits out the load.
     * <p>
     * The wait is here rather than in every caller because what they are all about to measure is
     * the fetch, and it cannot begin until the channel has loaded. Clocking the chip is enough on
     * its own -- {@link #stallCycle} takes the cycle number it is asking about as an argument, so
     * these cycles do not move the get/put phase the callers count in.
     */
    private void armOneByteSample(final int flags) {
        apu.write(0x4010, flags | 0x0F);
        apu.write(0x4012, 0x00);
        apu.write(0x4013, 0x00);
        apu.write(0x4015, 0x10);

        for (var i = 0; i < LOAD_CYCLES; i++) {
            apu.tick();
        }
    }

    @Nested
    @DisplayName("the sample fetch")
    class Fetch {
        /**
         * The fetch is a halt cycle, a dummy cycle, an alignment cycle when the phase calls for
         * one, and the read -- so three cycles or four depending on which half of the clock the
         * request landed in. The cycle it lands in is not one of them: by then that cycle's fate is
         * settled, and the halt is the one after.
         */
        @ParameterizedTest
        @CsvSource({
                "0, 3",  // halt on 1, dummy on 2, and 3 is a get: no alignment needed
                "1, 4",  // halt on 2, dummy on 3, and 4 is a put: one cycle spent waiting
        })
        void holdsTheCpuOffTheBusForThreeCyclesOrFour(final int requestedOn, final int cost) {
            for (var i = 0; i < requestedOn; i++) {
                assertFalse(stallCycle(mmu, i), "nothing is asking for the bus yet");
            }

            armOneByteSample(0x00);
            assertTrue(apu.isDMCFetchPending(), "there is a sample and no byte in hand");

            assertFalse(stallCycle(mmu, requestedOn), "the request arrives too late for this one");

            for (var i = 1; i < cost; i++) {
                assertTrue(stallCycle(mmu, requestedOn + i), "cycle " + i);
                assertTrue(apu.isDMCFetchPending(), "still waiting at cycle " + i);
            }

            assertTrue(stallCycle(mmu, requestedOn + cost));
            assertFalse(apu.isDMCFetchPending(), "the last cycle is the read");
            assertFalse(stallCycle(mmu, requestedOn + cost + 1), "and the bus goes back");
        }

        /**
         * A sample already playing feeds itself, and the fetch that keeps it going is asked for on
         * the cycle after the buffer empties. One <em>started</em> by a write to $4015 is not
         * that: the channel has to be loaded first, and the transfer does not begin until four
         * cycles after the write -- two APU cycles, which is what AccuracyCoin prints when it
         * measures this by landing the transfer on a {@code LDA $2002}.
         */
        @Test
        void doesNotAskForTheFirstByteUntilTheChannelHasLoaded() {
            apu.write(0x4010, 0x0F);
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            for (var i = 0; i < LOAD_CYCLES; i++) {
                assertFalse(apu.isDMCFetchPending(), "still loading at cycle " + i);
                assertFalse(stallCycle(mmu, i), "so nothing has asked for the bus");
                apu.tick();
            }

            assertTrue(apu.isDMCFetchPending(), "and now it wants its first byte");
        }

        @Test
        void doesNotAskForAnythingWithNoSampleToPlay() {
            assertFalse(apu.isDMCFetchPending());
            assertFalse(stallCycle(mmu, 0));
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
        /**
         * What a sample fetch costs a page transfer it collides with.
         * <p>
         * Two cycles rather than the four a fetch costs on its own, because the two transfers
         * interleave rather than take turns. The fetch's halt and dummy cycles are spent while the
         * page transfer carries on underneath them -- the CPU is already off the bus, so there is
         * nothing for them to wait for -- and then the DMC takes one get cycle the page wanted and
         * the page pays one idle cycle getting back into phase.
         *
         * @see <a href="https://www.nesdev.org/wiki/DMA#DMC_DMA_during_OAM_DMA">DMC DMA during OAM DMA</a>
         */
        private static final int COLLISION_CYCLES = 2;

        @Test
        void costsATransferThatStartsAtTheSameTimeTwoCycles() {
            armOneByteSample(0x00);
            mmu.write(0x4014, 0x02);

            assertEquals(ALIGNED_OAM_CYCLES + COLLISION_CYCLES, runDMAToCompletion());
        }

        @Test
        void stealsOneGetCycleFromATransferAlreadyUnderWay() {
            mmu.write(0x4014, 0x02);

            var stalled = 0;
            while (stalled < 100) {
                assertTrue(stallCycle(mmu, stalled));
                stalled++;
            }

            armOneByteSample(0x00);

            for (var i = 0; i < FETCH_CYCLES; i++) {
                assertTrue(stallCycle(mmu, stalled + i));
            }

            assertFalse(apu.isDMCFetchPending(), "the fetch went first");

            stalled += FETCH_CYCLES;
            while (stallCycle(mmu, stalled)) {
                stalled++;

                if (stalled > 2000) {
                    throw new AssertionError("the transfers never finished");
                }
            }

            assertEquals(ALIGNED_OAM_CYCLES + COLLISION_CYCLES, stalled,
                    "the page still went across, two cycles later than it would have");
        }

        /**
         * @return how many cycles the CPU was held off the bus for, both transfers together.
         */
        private int runDMAToCompletion() {
            var stalled = 0;

            while (stallCycle(mmu, stalled)) {
                stalled++;

                if (stalled > 2000) {
                    throw new AssertionError("the transfers never finished");
                }
            }

            return stalled;
        }
    }

    @Nested
    @DisplayName("the aborted DMA")
    class Abort {
        /**
         * Where the machine has got to, so that the get/put phase runs on across a whole test
         * rather than starting again at each step of one.
         */
        private int cycle;

        /**
         * One cycle of the whole machine.
         *
         * @return true if the CPU was held off the bus for it.
         */
        private boolean tick() {
            var stalled = stallCycle(mmu, cycle++);
            apu.tick();

            return stalled;
        }

        /**
         * @return how many of the next {@code cycles} cycles the CPU is held off the bus for.
         */
        private int stalledCyclesOver(final int cycles) {
            var stalled = 0;

            for (var i = 0; i < cycles; i++) {
                if (tick()) {
                    stalled++;
                }
            }

            return stalled;
        }

        /**
         * The interval from one request to the next, measured rather than assumed.
         */
        private int period;

        /**
         * Starts a looping sample and runs it until its rhythm is known, which is the only thing
         * any of this can be positioned against.
         * <p>
         * The DMC's divider free-runs, so the interval is fixed. This measures it across two
         * requests -- not the first, which comes from the load delay rather than from the divider
         * and so is short.
         *
         * @return the cycle the next request will be seen on.
         */
        private int startTheSampleAndLearnItsRhythm() {
            putSample(0xC000, 0x00);
            apu.write(0x4010, 0x4F);  // looping, fastest rate, so the requests keep coming
            apu.write(0x4012, 0x00);
            apu.write(0x4013, 0x00);
            apu.write(0x4015, 0x10);

            runToARequest();
            serveTheFetch();

            var first = runToARequest();
            serveTheFetch();

            var second = runToARequest();
            serveTheFetch();

            period = second - first;

            return second + period;
        }

        /**
         * Runs on to the cycle a write should land on to be {@code before} cycles ahead of
         * {@code request}.
         */
        private void runToWriteOn(final int request, final int before) {
            stalledCyclesOver(request - before + 1 - cycle);
        }

        /**
         * @return the cycle the DMC will be seen asking for a byte on.
         */
        private int runToARequest() {
            var start = cycle;

            while (!apu.isDMCFetchPending()) {
                tick();

                if (cycle - start > 5_000) {
                    throw new AssertionError("the DMC never asked for a byte");
                }
            }

            return cycle;
        }

        private void serveTheFetch() {
            assertEquals(FETCH_CYCLES, stalledCyclesOver(FETCH_CYCLES + 1),
                    "a transfer the emptying buffer asked for");
        }

        /**
         * The window is two cycles wide, and what it costs is a halt cycle and nothing else -- no
         * dummy cycle, no alignment, and no read.
         *
         * @see <a href="https://www.nesdev.org/wiki/DMA#Bugs">DMC DMA bugs</a>
         */
        @ParameterizedTest
        @CsvSource({"2", "3"})
        void spendsOneCycleWhenPlaybackStopsJustBeforeTheDmcWouldAsk(final int before) {
            runToWriteOn(startTheSampleAndLearnItsRhythm(), before);

            mmu.write(0x4015, 0x00);

            assertFalse(apu.isDMCFetchPending(), "the sample is gone, so nothing is fetched");
            assertEquals(1, stalledCyclesOver(FETCH_CYCLES + 4));
        }

        @Test
        void spendsNothingWhenPlaybackStopsBeforeThatWindow() {
            runToWriteOn(startTheSampleAndLearnItsRhythm(), 4);

            mmu.write(0x4015, 0x00);

            assertEquals(0, stalledCyclesOver(FETCH_CYCLES + 4), "too early to have started");
        }

        /**
         * Once the DMC has asked, nothing stops the transfer: the byte is read and handed to a
         * channel that is no longer playing, which throws it away.
         */
        @Test
        void letsATransferTheDmcHasAlreadyAskedForFinish() {
            runToWriteOn(startTheSampleAndLearnItsRhythm(), 0);

            mmu.write(0x4015, 0x00);

            assertEquals(FETCH_CYCLES, stalledCyclesOver(FETCH_CYCLES + 4));
        }

        /**
         * The implicit stop. Nobody wrote $4015 to stop anything: a one byte sample with looping
         * off simply ran out, and running out inside the window schedules the same aborted DMA.
         * <p>
         * The buffer has to be empty for a $4015 write to start a transfer at all, so this stops
         * the sample and lets the output unit run the buffer dry before starting the one byte one
         * far enough ahead that its only byte is fetched in the window.
         */
        @ParameterizedTest
        @CsvSource({"9", "10"})
        void alsoHappensWhenAOneByteSampleRunsOutInThatWindow(final int before) {
            var request = startTheSampleAndLearnItsRhythm();

            apu.write(0x4015, 0x00);
            stalledCyclesOver(request - cycle);

            runToWriteOn(request + period, before);
            apu.write(0x4010, 0x0F);  // no loop, so one byte is the whole sample
            mmu.write(0x4015, 0x10);

            assertEquals(FETCH_CYCLES + 1, stalledCyclesOver(2 * FETCH_CYCLES + 4),
                    "the transfer that fetched the byte, and the aborted one behind it");
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
                stallCycle(mmu, i);
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
