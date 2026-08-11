package com.github.dimiro1.mynes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The divider that clocks everything in the APU that is not a sound generator.
 * <p>
 * Everything here is counted in CPU cycles from the point the sequencer was last reset, because
 * that is how NESdev tabulates it and how blargg's ROMs measure it. The cycle numbers are the
 * whole subject: a step in the wrong place detunes every envelope in every game at once, and the
 * interrupt landing in the wrong cycle is what {@code 6-irq_flag_timing} exists to catch.
 */
class APUFrameCounterTests {
    /**
     * The cycle the four step sequence first sets its interrupt flag on. It stays set through the
     * two cycles after this one as well.
     */
    private static final int IRQ_CYCLE = 29828;

    /**
     * How long the four step sequence is.
     */
    private static final int FOUR_STEP_PERIOD = 29830;

    /**
     * The cycle the first quarter frame lands on.
     */
    private static final int FIRST_QUARTER_FRAME = 7457;

    /**
     * The cycle the first half frame lands on, so the first time a length counter moves.
     */
    private static final int FIRST_HALF_FRAME = 14913;

    /**
     * The last step of the five step sequence, which is the other half frame.
     */
    private static final int FIFTH_STEP = 37281;

    private APU apu;

    /**
     * The level of the frame counter's end of the /IRQ line, as the APU last left it.
     */
    private boolean irqLine;

    @BeforeEach
    void setUp() {
        apu = new APU(level -> irqLine = level, level -> { });
    }

    private void tick(final int cycles) {
        for (var i = 0; i < cycles; i++) {
            apu.tick();
        }
    }

    /**
     * Runs the chip until the sequencer is the given number of cycles into its sequence.
     * <p>
     * Used rather than a cycle count wherever a $4017 write is involved, since where the sequence
     * starts then depends on the three-or-four cycle delay and so on the parity of the write.
     */
    private void tickToSequenceCycle(final int cycle) {
        for (var i = 0; i < 2 * FIFTH_STEP; i++) {
            if (apu.frameCounterCycle() == cycle) {
                return;
            }
            apu.tick();
        }

        throw new AssertionError("the sequencer never reached cycle " + cycle);
    }

    /**
     * Arms pulse 1 with the longest note in the table and no halt, so that a half frame is visible
     * as the length counter coming down by one.
     */
    private void armPulse1() {
        apu.write(0x4015, 0x01);
        apu.write(0x4000, 0x00);  // envelope, halt clear
        apu.write(0x4003, 0x08);  // length index 1, which is 254
    }

    @Nested
    @DisplayName("the four step sequence")
    class FourStep {
        @Test
        void raisesTheInterruptAtTheEndOfTheSequenceAndNotBefore() {
            tick(IRQ_CYCLE - 1);
            assertFalse(apu.isFrameIRQRaised(), "one cycle early");

            apu.tick();
            assertTrue(apu.isFrameIRQRaised(), "and on the cycle itself");
        }

        @Test
        void pullsTheSharedLineDownWithTheFlag() {
            tick(IRQ_CYCLE);

            assertTrue(irqLine, "the flag and the line are the same thing");
        }

        @Test
        void keepsTheFlagUpUntilSomethingAcknowledgesIt() {
            tick(IRQ_CYCLE + 5000);

            assertTrue(apu.isFrameIRQRaised(), "nothing clears it on its own");
        }

        /**
         * The flag is set on three consecutive cycles rather than on one, so a program that reads
         * $4015 in the middle of the window sees it come straight back. Which is the whole of what
         * {@code 6-irq_flag_timing} measures.
         */
        @Test
        void setsTheFlagOnEachOfTheLastThreeCyclesOfTheSequence() {
            tick(IRQ_CYCLE);
            apu.readStatus();
            assertFalse(apu.isFrameIRQRaised(), "acknowledged");

            apu.tick();
            assertTrue(apu.isFrameIRQRaised(), "and set again on the next cycle");
            apu.readStatus();

            apu.tick();
            assertTrue(apu.isFrameIRQRaised(), "and on the cycle the sequence wraps");
        }

        @Test
        void raisesItAgainEverySequence() {
            tick(FOUR_STEP_PERIOD);
            apu.readStatus();
            assertFalse(apu.isFrameIRQRaised());

            tickToSequenceCycle(IRQ_CYCLE - 1);
            assertFalse(apu.isFrameIRQRaised(), "one cycle early");

            apu.tick();
            assertTrue(apu.isFrameIRQRaised());
        }

        @Test
        void clocksAHalfFrameAtTheSecondStep() {
            armPulse1();

            tick(FIRST_HALF_FRAME - 1);
            assertEquals(254, apu.pulse1Length(), "one cycle early");

            apu.tick();
            assertEquals(253, apu.pulse1Length());
        }

        @Test
        void clocksFourQuarterFramesPerSequence() {
            // The envelope's divider is clocked by the quarter frames, so counting them is a
            // matter of giving it a period of zero and watching the decay walk down from 15.
            apu.write(0x4015, 0x01);
            apu.write(0x4000, 0x20);  // looping envelope, period 0, and so a halted length counter
            apu.write(0x4003, 0x08);  // loads the length counter and restarts the envelope

            tick(FOUR_STEP_PERIOD);

            // The first quarter frame consumes the start flag and loads the decay with 15, and the
            // three after it each take one off.
            assertEquals(12, apu.pulse1Volume(), "steps 1 to 4 are all quarter frames");
            assertEquals(254, apu.pulse1Length(), "and a halted length counter does not count");
        }

        @Test
        void clocksTwoHalfFramesPerSequence() {
            armPulse1();

            tick(FOUR_STEP_PERIOD);

            assertEquals(252, apu.pulse1Length(), "steps 2 and 4");
        }
    }

    @Nested
    @DisplayName("the five step sequence")
    class FiveStep {
        /**
         * Enough sequences that a missing interrupt is a fact rather than a coincidence of where
         * the test stopped counting.
         */
        private static final int SEQUENCES = 3;

        @Test
        void neverRaisesTheInterrupt() {
            apu.write(0x4017, 0x80);
            tick(SEQUENCES * (FIFTH_STEP + 1));

            assertFalse(apu.isFrameIRQRaised(), "there is no interrupt in this mode at all");
            assertFalse(irqLine);
        }

        @Test
        void clocksAQuarterAndAHalfFrameOnTheWayIn() {
            armPulse1();
            apu.write(0x4017, 0x80);

            // Four cycles covers the write delay at either parity, and the first real step of the
            // sequence is thousands of cycles away.
            tick(4);

            assertEquals(253, apu.pulse1Length(), "the immediate half frame");
        }

        @Test
        void doesNotClockAnythingOnTheWayIntoFourStepMode() {
            armPulse1();
            apu.write(0x4017, 0x00);
            tick(4);

            assertEquals(254, apu.pulse1Length(), "only the five step mode clocks on entry");
        }

        @Test
        void stretchesTheSequenceRatherThanAddingAStep() {
            armPulse1();
            apu.write(0x4017, 0x80);
            tick(4);
            assertEquals(253, apu.pulse1Length(), "the entry clock");

            // The first two steps are the four step sequence's, then a step at 29829 where
            // nothing happens at all, and the other half frame at the end.
            tickToSequenceCycle(FIRST_HALF_FRAME);
            assertEquals(252, apu.pulse1Length(), "the second step is a half frame here too");

            tickToSequenceCycle(FIFTH_STEP);
            assertEquals(251, apu.pulse1Length(), "and the fifth step is the other one");
        }
    }

    @Nested
    @DisplayName("acknowledging the interrupt")
    class Acknowledging {
        @Test
        void readingTheStatusRegisterReturnsTheFlagAndClearsIt() {
            tick(IRQ_CYCLE);

            assertEquals(0x40, apu.readStatus() & 0x40, "bit 6 is the frame interrupt");
            assertFalse(apu.isFrameIRQRaised(), "and reading it is what acknowledges it");
            assertFalse(irqLine, "so the line comes back up");
        }

        @Test
        void settingTheInhibitBitClearsAFlagThatIsAlreadyThere() {
            tick(IRQ_CYCLE);
            apu.write(0x4017, 0x40);

            assertFalse(apu.isFrameIRQRaised(), "which is how a handler acknowledges it");
            assertFalse(irqLine);
        }

        @Test
        void theInhibitBitStopsTheNextOneToo() {
            apu.write(0x4017, 0x40);
            tick(IRQ_CYCLE + 4);

            assertFalse(apu.isFrameIRQRaised());
        }

        @Test
        void clearingTheInhibitBitLetsThemThroughAgain() {
            apu.write(0x4017, 0x40);
            tick(1000);
            apu.write(0x4017, 0x00);

            tickToSequenceCycle(IRQ_CYCLE);

            assertTrue(apu.isFrameIRQRaised());
        }
    }

    @Nested
    @DisplayName("the delay on a $4017 write")
    class WriteDelay {
        @Test
        void takesThreeCyclesFromAnApuCycle() {
            tick(1000);  // an even number of cycles, so the write lands on an APU cycle

            apu.write(0x4017, 0x00);
            tick(2);
            assertEquals(1002, apu.frameCounterCycle(), "still counting the old sequence");

            apu.tick();
            assertEquals(0, apu.frameCounterCycle(), "and the third cycle restarts it");
        }

        @Test
        void takesFourCyclesFromBetweenTwoApuCycles() {
            tick(1001);

            apu.write(0x4017, 0x00);
            tick(3);
            assertEquals(1004, apu.frameCounterCycle(), "still counting the old sequence");

            apu.tick();
            assertEquals(0, apu.frameCounterCycle(), "and the fourth cycle restarts it");
        }

        /**
         * The cycle the reset lands on is cycle zero of the new sequence rather than the cycle
         * before it, so the first step is a full 7457 cycles after the reset and not 7456. One
         * either way moves both interrupt windows, which is what {@code 6-irq_flag_timing}
         * measures.
         */
        @Test
        void theResetCycleIsCycleZeroOfTheNewSequence() {
            tick(1000);
            apu.write(0x4017, 0x00);
            tick(3);

            assertEquals(0, apu.frameCounterCycle());

            tick(FIRST_QUARTER_FRAME);
            assertEquals(FIRST_QUARTER_FRAME, apu.frameCounterCycle());
        }

        @Test
        void theInhibitBitDoesNotWait() {
            tick(IRQ_CYCLE);
            apu.write(0x4017, 0x40);

            // A handler that had to wait three cycles for this would return with the line still
            // low and be interrupted all over again.
            assertFalse(irqLine);
        }
    }

    @Nested
    @DisplayName("reset")
    class Reset {
        @Test
        void startsTheSequenceAgainAndDropsTheInterrupt() {
            tick(IRQ_CYCLE);
            assertTrue(apu.isFrameIRQRaised());

            apu.reset();

            assertFalse(apu.isFrameIRQRaised());
            assertFalse(irqLine);
            assertEquals(0, apu.frameCounterCycle());

            tick(IRQ_CYCLE - 1);
            assertFalse(apu.isFrameIRQRaised(), "a whole sequence from the reset, not from before");

            apu.tick();
            assertTrue(apu.isFrameIRQRaised());
        }

        @Test
        void silencesEveryChannel() {
            apu.write(0x4015, 0x0F);
            apu.write(0x4003, 0x08);
            apu.write(0x4007, 0x08);
            apu.write(0x400B, 0x08);
            apu.write(0x400F, 0x08);

            apu.reset();

            assertEquals(0, apu.pulse1Length());
            assertEquals(0, apu.pulse2Length());
            assertEquals(0, apu.triangleLength());
            assertEquals(0, apu.noiseLength());
        }

        @Test
        void leavesTheModeAloneSoAFiveStepMachineStaysQuiet() {
            apu.write(0x4017, 0x80);
            tick(4);

            apu.reset();
            tick(IRQ_CYCLE + 4);

            assertFalse(apu.isFrameIRQRaised(), "the reset line does not reach the $4017 latch");
        }
    }

    @Nested
    @DisplayName("the quarter frame")
    class QuarterFrame {
        @Test
        void clocksTheTrianglesLinearCounter() {
            apu.write(0x4015, 0x04);
            apu.write(0x4008, 0x05);  // reload 5, control clear
            apu.write(0x400B, 0x08);  // arms the reload flag

            tick(FIRST_QUARTER_FRAME);
            assertEquals(5, apu.triangleLinearCounter(), "the first one reloads");

            tick(FIRST_QUARTER_FRAME);
            assertEquals(4, apu.triangleLinearCounter(), "and the ones after it count down");
        }

        @Test
        void reloadsTheLinearCounterEveryQuarterFrameWhileTheControlBitIsSet() {
            apu.write(0x4015, 0x04);
            apu.write(0x4008, 0x85);  // reload 5, control set
            apu.write(0x400B, 0x08);

            tick(4 * FIRST_QUARTER_FRAME);

            assertEquals(5, apu.triangleLinearCounter(),
                    "the control bit is what keeps the reload flag armed");
        }

        @Test
        void restartsTheEnvelopeAfterAWriteToTheLengthRegister() {
            apu.write(0x4015, 0x08);
            apu.write(0x400C, 0x00);  // envelope, period 0, no loop
            apu.write(0x400F, 0x08);

            tick(FOUR_STEP_PERIOD);
            assertEquals(12, apu.noiseVolume(), "four quarter frames in, minus the one that loads");

            apu.write(0x400F, 0x08);
            tick(FIRST_QUARTER_FRAME);

            assertEquals(15, apu.noiseVolume(), "and the write puts the decay back at the top");
        }
    }

    /**
     * The 2A07, whose sequence is the same five steps 11% further apart -- because a CPU cycle is
     * 11% longer there and the sequence is meant to come to one video frame either way.
     */
    @Nested
    @DisplayName("on a PAL machine")
    class PALTiming {
        private static final int PAL_FIRST_HALF_FRAME = 16627;
        private static final int PAL_IRQ_CYCLE = 33252;

        private APU pal;

        @BeforeEach
        void setUp() {
            pal = new APU(level -> { }, level -> { }, Region.PAL);
        }

        @Test
        void movesALengthCounterOnItsOwnHalfFrameAndNotOnNTSCs() {
            pal.write(0x4015, 0x01);
            pal.write(0x4000, 0x00);
            pal.write(0x4003, 0x08);  // 254 half frames

            for (var i = 0; i < FIRST_HALF_FRAME; i++) {
                pal.tick();
            }

            assertEquals(254, pal.pulse1Length(), "an NTSC half frame is far too early here");

            for (var i = FIRST_HALF_FRAME; i < PAL_FIRST_HALF_FRAME; i++) {
                pal.tick();
            }

            assertEquals(253, pal.pulse1Length(), "and its own is not");
        }

        @Test
        void raisesTheInterruptOnItsOwnLastCycle() {
            for (var i = 0; i < PAL_IRQ_CYCLE - 1; i++) {
                pal.tick();
            }

            assertFalse(pal.isFrameIRQRaised(), "one cycle early");

            pal.tick();
            assertTrue(pal.isFrameIRQRaised(), "and on the cycle itself");
        }

        @Test
        void leavesTheNTSCSequenceAlone() {
            // The same object, built the other way round, still keeps NTSC's numbers. Which is
            // only worth saying because both sequences now come out of one field.
            tick(IRQ_CYCLE);

            assertTrue(apu.isFrameIRQRaised());
        }
    }
}
