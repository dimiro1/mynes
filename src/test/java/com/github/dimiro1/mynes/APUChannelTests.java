package com.github.dimiro1.mynes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.function.IntSupplier;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four sound generators: two pulses, the triangle and the noise channel.
 * <p>
 * Each is a divider driving a sequencer, and what is worth testing is the shape that comes out and
 * the rate it comes out at -- a duty cycle of the wrong width or a divider counting the wrong
 * clock is a game that plays out of tune. The gates on top of them, the length counters and the
 * envelopes, belong to {@link APULengthTableTests} and {@link APUFrameCounterTests}.
 */
class APUChannelTests {
    /**
     * The cycle the first half frame of the four step sequence lands on, which is where the sweep
     * units are clocked.
     */
    private static final int FIRST_HALF_FRAME = 14913;

    /**
     * The other half frame, which is the last step of the sequence.
     */
    private static final int SECOND_HALF_FRAME = 29829;

    /**
     * The cycle the first quarter frame lands on, and so the earliest the triangle's linear
     * counter can be loaded and the channel start moving.
     */
    private static final int FIRST_QUARTER_FRAME = 7457;

    private APU apu;

    @BeforeEach
    void setUp() {
        apu = new APU(level -> { }, level -> { });
        apu.write(0x4015, 0x0F);
    }

    private void tick(final int cycles) {
        for (var i = 0; i < cycles; i++) {
            apu.tick();
        }
    }

    /**
     * Runs the chip on to the next half frame, which is where the sweep units and the length
     * counters are clocked. Used rather than a cycle count because the two half frames of a
     * sequence are 14916 and 14914 cycles apart, not 14915 twice.
     */
    private void tickToNextHalfFrame() {
        do {
            apu.tick();
        } while (apu.frameCounterCycle() != FIRST_HALF_FRAME
                && apu.frameCounterCycle() != SECOND_HALF_FRAME);
    }

    /**
     * What a channel puts out over a run of CPU cycles, sampled once per cycle after the cycle has
     * happened.
     */
    private int[] trace(final int cycles, final IntSupplier output) {
        var trace = new int[cycles];

        for (var i = 0; i < cycles; i++) {
            apu.tick();
            trace[i] = output.getAsInt();
        }

        return trace;
    }

    @Nested
    @DisplayName("the pulse channels")
    class PulseChannels {
        /**
         * A divider period the sweep unit will not mute, and a round one: the sequencer steps
         * every 2 * (15 + 1) CPU cycles, so a full wave is eight of those.
         */
        private static final int PERIOD = 15;
        private static final int STEP_CYCLES = 2 * (PERIOD + 1);
        private static final int WAVE_CYCLES = 8 * STEP_CYCLES;

        /**
         * Arms pulse 1 at a fixed volume and the given duty, with a note long enough to outlast
         * anything here and no sweep.
         */
        private void armPulse1(final int duty) {
            apu.write(0x4000, (duty << 6) | 0x3F);  // halt, constant volume 15
            apu.write(0x4001, 0x00);
            apu.write(0x4002, PERIOD);
            apu.write(0x4003, 0x08);                // length 254, timer high bits zero
        }

        @Test
        void stepTheSequencerEveryTwoCyclesPerUnitOfPeriod() {
            armPulse1(2);  // the 50% duty, so one rising edge per wave

            var trace = trace(4 * WAVE_CYCLES, apu::pulse1Output);
            var firstEdge = -1;
            var secondEdge = -1;

            for (var i = 1; i < trace.length; i++) {
                if (trace[i] > 0 && trace[i - 1] == 0) {
                    if (firstEdge < 0) {
                        firstEdge = i;
                    } else if (secondEdge < 0) {
                        secondEdge = i;
                    }
                }
            }

            assertTrue(secondEdge > 0, "four waves should have given at least two rising edges");
            assertEquals(WAVE_CYCLES, secondEdge - firstEdge,
                    "the divider counts APU cycles, so each step is two CPU cycles");
        }

        /**
         * The duty cycles, measured from the rising edge so that the answer does not depend on
         * where in its period the free running divider happened to be. What they come to is the
         * four widths the channel can play: an eighth, a quarter, a half, and three quarters --
         * that last one being the quarter with its bits flipped, which is the same wave a quarter
         * period along and is there to be played against the other pulse.
         */
        @ParameterizedTest
        @CsvSource({
                "0, 1",
                "1, 2",
                "2, 4",
                "3, 6",
        })
        void haveFourDutyCycles(final int duty, final int stepsHigh) {
            armPulse1(duty);

            var expected = new int[8];
            for (var step = 0; step < stepsHigh; step++) {
                expected[step] = 1;
            }

            assertArrayEquals(expected, waveFromTheRisingEdge());
        }

        @Test
        void putOutTheEnvelopeVolumeRatherThanABit() {
            apu.write(0x4000, 0x80 | 0x30 | 0x09);  // 50% duty, halt, constant volume 9
            apu.write(0x4001, 0x00);
            apu.write(0x4002, PERIOD);
            apu.write(0x4003, 0x08);

            var high = 0;

            for (var level : trace(WAVE_CYCLES, apu::pulse1Output)) {
                if (level != 0) {
                    assertEquals(9, level);
                    high++;
                }
            }

            assertTrue(high > 0, "something should have come out");
        }

        @Test
        void areSilencedByAPeriodBelowEight() {
            armPulse1(2);
            apu.write(0x4002, 8);
            assertTrue(maxOutput() > 0, "eight is the shortest period it will play");

            apu.write(0x4002, 7);
            assertEquals(0, maxOutput(), "and seven is below it");
        }

        /**
         * With the shift count left at zero the sweep unit's target is twice the period, so any
         * period over $3FF puts it out of the eleven bits the timer has -- which is why a pulse
         * channel cannot play the bottom two octaves at all, and why games put their bass on the
         * triangle.
         */
        @Test
        void areSilencedWhenTheSweepTargetWouldNotFit() {
            apu.write(0x4000, 0x3F);
            apu.write(0x4001, 0x00);
            apu.write(0x4002, 0x00);
            apu.write(0x4003, 0x0C);  // period $400, whose target is $800

            assertEquals(0, maxOutput());

            apu.write(0x4003, 0x08);  // period $000...
            apu.write(0x4002, 0x80);  // ...then $080, which is well inside the range
            assertTrue(maxOutput() > 0);
        }

        /**
         * @return the eight steps of one wave, rotated so that the first is the one the output
         * rises on.
         */
        private int[] waveFromTheRisingEdge() {
            var trace = trace(3 * WAVE_CYCLES, apu::pulse1Output);
            var edge = -1;

            for (var i = 1; i < trace.length && edge < 0; i++) {
                if (trace[i] > 0 && trace[i - 1] == 0) {
                    edge = i;
                }
            }

            assertTrue(edge > 0, "the channel never came on");

            var wave = new int[8];
            for (var step = 0; step < 8; step++) {
                // Each step is read in its middle, well away from the cycle it changes on.
                wave[step] = trace[edge + step * STEP_CYCLES + STEP_CYCLES / 2] > 0 ? 1 : 0;
            }

            return wave;
        }

        /**
         * @return the highest level pulse 1 reached over a run long enough to hold a whole wave at
         * any period these tests use.
         */
        private int maxOutput() {
            var highest = 0;

            for (var level : trace(4096, apu::pulse1Output)) {
                highest = Math.max(highest, level);
            }

            return highest;
        }
    }

    @Nested
    @DisplayName("the sweep units")
    class Sweep {
        /**
         * Arms a pulse channel at period $100 with a note that will not run out.
         *
         * @param base $4000 for pulse 1, $4004 for pulse 2.
         */
        private void arm(final int base) {
            apu.write(base, 0x3F);          // halt, constant volume 15
            apu.write(base + 2, 0x00);
            apu.write(base + 3, 0x08);      // length 254, timer high bits zero
            apu.write(base + 2, 0x00);
            apu.write(base + 3, 0x09);      // period $100
        }

        @Test
        void walkThePeriodUpwards() {
            arm(0x4000);
            apu.write(0x4001, 0x81);  // enabled, divider period 0, shift 1

            tickToNextHalfFrame();

            assertEquals(0x180, apu.pulse1Period(), "the period plus itself shifted right once");
        }

        @Test
        void walkItDownwards() {
            arm(0x4004);
            apu.write(0x4005, 0x89);  // enabled, divider period 0, negate, shift 1

            tickToNextHalfFrame();

            assertEquals(0x080, apu.pulse2Period(), "two's complement: the period less half of it");
        }

        /**
         * The one thing that is not the same circuit twice over: pulse 1 adds the one's complement
         * of the change and so lands one step lower than pulse 2 does. Two channels sweeping down
         * together drift apart by exactly that, and games are written knowing it.
         */
        @Test
        void differByOneOnADownwardSweep() {
            arm(0x4000);
            arm(0x4004);
            apu.write(0x4001, 0x89);
            apu.write(0x4005, 0x89);

            tickToNextHalfFrame();

            assertEquals(0x07F, apu.pulse1Period(), "one's complement on pulse 1");
            assertEquals(0x080, apu.pulse2Period(), "two's complement on pulse 2");
        }

        @Test
        void adjustThePeriodOnlyWhenTheirDividerRunsOut() {
            arm(0x4000);
            apu.write(0x4001, 0xB1);  // enabled, divider period 3, shift 1

            tickToNextHalfFrame();
            assertEquals(0x180, apu.pulse1Period(), "the divider starts at zero, so the first lands");

            for (var i = 0; i < 3; i++) {
                tickToNextHalfFrame();
            }
            assertEquals(0x180, apu.pulse1Period(), "and then three half frames go by");

            tickToNextHalfFrame();
            assertEquals(0x240, apu.pulse1Period(), "before the fourth adjusts it again");
        }

        @Test
        void doNotMoveThePeriodWhileTheyAreMuting() {
            apu.write(0x4000, 0x3F);
            apu.write(0x4002, 0xF0);
            apu.write(0x4003, 0x0F);  // period $7F0, whose target is out of range
            apu.write(0x4001, 0x81);

            for (var i = 0; i < 4; i++) {
                tickToNextHalfFrame();
            }

            assertEquals(0x7F0, apu.pulse1Period(), "a muted channel keeps its period");
        }

        @Test
        void doNothingWithTheShiftCountAtZero() {
            arm(0x4000);
            apu.write(0x4001, 0x80);  // enabled, shift 0

            for (var i = 0; i < 4; i++) {
                tickToNextHalfFrame();
            }

            assertEquals(0x100, apu.pulse1Period());
        }

        @Test
        void doNothingWhileTheyAreSwitchedOff() {
            arm(0x4000);
            apu.write(0x4001, 0x01);  // shift 1, but not enabled

            for (var i = 0; i < 4; i++) {
                tickToNextHalfFrame();
            }

            assertEquals(0x100, apu.pulse1Period());
        }
    }

    @Nested
    @DisplayName("the triangle")
    class Triangle {
        /**
         * Arms the triangle with a note and a linear counter that will not run out, at the given
         * divider period.
         */
        private void arm(final int period) {
            apu.write(0x4008, 0xFF);                       // control set, linear counter reload 127
            apu.write(0x400A, period & 0xFF);
            apu.write(0x400B, 0xF8 | ((period >> 8) & 7));  // length index 31
        }

        @Test
        void rampsDownAndBackUpThroughThirtyTwoSteps() {
            arm(0);  // one step per CPU cycle

            // The linear counter does not load until the first quarter frame, so nothing moves
            // before then -- and that first cycle takes the sequencer to step 1, so 31 more bring
            // it back to the top of the ramp.
            tick(FIRST_QUARTER_FRAME + 31);

            assertArrayEquals(new int[]{
                    14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
                    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
                    15,
            }, trace(32, apu::triangleOutput),
                    "fifteen down to zero and back up, with each end held for two steps");
        }

        /**
         * Thirty-two steps in thirty-two CPU cycles is the point: the pulses would have managed
         * sixteen. That extra octave at the bottom is the whole reason the bass lives here.
         */
        @Test
        void isClockedAtTheCpuRateRatherThanTheApuRate() {
            arm(0);
            tick(FIRST_QUARTER_FRAME);

            var start = apu.triangleOutput();
            tick(32);

            assertEquals(start, apu.triangleOutput(), "a whole ramp in thirty-two cycles");
        }

        @Test
        void takesOneCyclePerStepPerUnitOfPeriod() {
            arm(1);  // a step every two CPU cycles
            tick(FIRST_QUARTER_FRAME);

            var start = apu.triangleOutput();

            tick(64);
            assertEquals(start, apu.triangleOutput(), "sixty-four cycles is a whole ramp now");

            tick(32);
            assertNotEquals(start, apu.triangleOutput(), "and thirty-two is half of one");
        }

        @Test
        void stopsWhereItIsWhenTheLinearCounterRunsOut() {
            apu.write(0x4008, 0x01);  // control clear, so the counter counts, reload 1
            apu.write(0x400A, 0x00);
            apu.write(0x400B, 0xF8);

            tick(FIRST_QUARTER_FRAME + 4);
            var moving = apu.triangleOutput();

            // Two more quarter frames: the first takes the linear counter to zero, and after that
            // the sequencer holds.
            tick(2 * FIRST_QUARTER_FRAME);

            var stopped = apu.triangleOutput();
            tick(64);

            assertEquals(0, apu.triangleLinearCounter());
            assertEquals(stopped, apu.triangleOutput(), "it holds rather than dropping to zero");
            assertNotEquals(15, moving, "and it had been moving before that");
        }

        @Test
        void stopsWhenTheLengthCounterRunsOut() {
            apu.write(0x4008, 0x7F);  // control clear, reload 127, so the length counter counts too
            apu.write(0x400A, 0x00);
            apu.write(0x400B, 0x18);  // length index 3, which is 2

            tick(FIRST_QUARTER_FRAME + 8);
            var moving = apu.triangleOutput();

            tickToNextHalfFrame();
            tickToNextHalfFrame();

            var stopped = apu.triangleOutput();
            tick(64);

            assertEquals(0, apu.triangleLength());
            assertEquals(stopped, apu.triangleOutput());
            assertNotEquals(15, moving);
        }
    }

    @Nested
    @DisplayName("the noise channel")
    class Noise {
        /**
         * A handful of the sixteen tabulated periods, in CPU cycles, spread across the table.
         */
        @ParameterizedTest
        @CsvSource({
                "0, 4",
                "1, 8",
                "5, 96",
                "8, 202",
                "15, 4068",
        })
        void shiftItsRegisterOnceEveryTabulatedPeriod(final int index, final int cpuCycles) {
            apu.write(0x400E, index);

            var shifts = 0;
            var previous = apu.noiseShiftRegister();

            // The divider starts at zero, so the first shift is on the first cycle and the tenth
            // is exactly nine periods after it.
            for (var i = 0; i < 10 * cpuCycles; i++) {
                apu.tick();

                var now = apu.noiseShiftRegister();
                if (now != previous) {
                    shifts++;
                }
                previous = now;
            }

            assertEquals(10, shifts);
        }

        @Test
        void runsThroughEveryStateOfAFifteenBitRegister() {
            apu.write(0x400E, 0x00);

            assertEquals(32767, shiftsUntilTheRegisterComesBack(),
                    "long enough that what comes out is heard as hiss");
        }

        @Test
        void runsANinetyThreeStepSequenceInTheShortMode() {
            apu.write(0x400E, 0x80);  // the mode bit, which taps six bits along instead of one

            assertEquals(93, shiftsUntilTheRegisterComesBack(),
                    "short enough to be heard as a pitch instead");
        }

        @Test
        void isSilentWhileTheBottomBitOfTheRegisterIsSet() {
            apu.write(0x400C, 0x3F);  // halt, constant volume 15
            apu.write(0x400E, 0x00);
            apu.write(0x400F, 0x08);

            for (var i = 0; i < 200; i++) {
                var expected = (apu.noiseShiftRegister() & 1) != 0 ? 0 : 15;

                assertEquals(expected, apu.noiseOutput());
                tick(4);
            }
        }

        @Test
        void isSilencedByItsLengthCounter() {
            apu.write(0x400C, 0x0F);  // no halt, constant volume 15
            apu.write(0x400E, 0x00);
            apu.write(0x400F, 0x18);  // length index 3, which is 2

            tickToNextHalfFrame();
            tickToNextHalfFrame();

            assertEquals(0, apu.noiseLength());
            assertEquals(0, apu.noiseOutput());
        }

        /**
         * @return how many shifts the register takes to return to the value it started on, which
         * is the length of the sequence the channel plays.
         */
        private int shiftsUntilTheRegisterComesBack() {
            var start = apu.noiseShiftRegister();

            for (var shifts = 1; shifts <= 40000; shifts++) {
                tick(4);  // one shift at the fastest period

                if (apu.noiseShiftRegister() == start) {
                    return shifts;
                }
            }

            throw new AssertionError("the sequence never came back round");
        }
    }
}
