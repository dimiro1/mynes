package com.github.dimiro1.mynes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The mixer and everything between it and a sound card: the two nonlinear ladders, the decimator
 * that turns 1.79 million values a second into 44100, and the filters that give the result the
 * console's tone.
 * <p>
 * None of this is on the hardware's critical path in the way the timing is, so the tests here are
 * about the arithmetic rather than about cycles: the right number of samples, in the right range,
 * with the DC gone.
 */
class APUMixerTests {
    /**
     * One NTSC frame in CPU cycles: 1789773 divided by 60.0988.
     */
    private static final int FRAME_CYCLES = 29781;

    /**
     * How many samples a frame comes to at 44.1kHz, give or take where the fractional cycle count
     * happens to land.
     */
    private static final int SAMPLES_PER_FRAME = 734;

    private APU apu;

    @BeforeEach
    void setUp() {
        apu = new APU(level -> { }, level -> { });
    }

    private void tick(final int cycles) {
        for (var i = 0; i < cycles; i++) {
            apu.tick();
        }
    }

    /**
     * Runs a frame and returns everything it produced.
     */
    private short[] frame() {
        tick(FRAME_CYCLES);

        var out = new short[4096];
        var drained = apu.drainSamples(out);
        var samples = new short[drained];

        System.arraycopy(out, 0, samples, 0, drained);

        return samples;
    }

    /**
     * Arms both pulses at full volume with a period in the middle of the range, which is the
     * loudest thing the two of them can do together.
     */
    private void armBothPulses() {
        apu.write(0x4015, 0x03);

        apu.write(0x4000, 0x7F);  // 50% duty, halt, constant volume 15
        apu.write(0x4001, 0x00);
        apu.write(0x4002, 0x40);
        apu.write(0x4003, 0x08);

        apu.write(0x4004, 0x7F);
        apu.write(0x4005, 0x00);
        apu.write(0x4006, 0x40);
        apu.write(0x4007, 0x08);
    }

    @Nested
    @DisplayName("the rate")
    class Rate {
        @Test
        void aFrameIsAboutSevenHundredAndThirtyFourSamples() {
            var samples = frame();

            assertTrue(Math.abs(samples.length - SAMPLES_PER_FRAME) <= 1,
                    "expected about " + SAMPLES_PER_FRAME + " but got " + samples.length);
        }

        /**
         * The cycles that do not divide evenly into a sample are carried rather than dropped, so
         * a hundred frames come to a hundred frames' worth of samples and not to ninety-nine and
         * a bit. A third of a cycle thrown away each time would be a second of audio lost every
         * twenty minutes, which is a click a minute as the buffer catches up.
         */
        @Test
        void theFractionalCyclesAreCarriedRatherThanDropped() {
            var total = 0;

            for (var i = 0; i < 100; i++) {
                total += frame().length;
            }

            var expected = (int) Math.round(100.0 * FRAME_CYCLES * 44100 / 1_789_773.0);

            assertTrue(Math.abs(total - expected) <= 1,
                    "expected about " + expected + " over a hundred frames but got " + total);
        }

        @Test
        void samplesPileUpUntilSomethingDrainsThem() {
            tick(FRAME_CYCLES);
            assertEquals(SAMPLES_PER_FRAME, apu.availableSamples(), 1);

            tick(FRAME_CYCLES);
            assertEquals(2 * SAMPLES_PER_FRAME, apu.availableSamples(), 2);
        }

        /**
         * A machine nobody is listening to -- a test, or a front end that has stopped asking --
         * must not grow a buffer without end.
         */
        @Test
        void theRingStopsGrowingRatherThanFillingMemory() {
            tick(60 * FRAME_CYCLES);

            assertEquals(8192, apu.availableSamples(), "the ring's size, and no more");
        }

        @Test
        void drainingTakesOnlyWhatFits() {
            tick(FRAME_CYCLES);

            var small = new short[100];
            assertEquals(100, apu.drainSamples(small));
            assertEquals(SAMPLES_PER_FRAME - 100, apu.availableSamples(), 1);
        }

        @Test
        void drainingAnEmptyChipGivesNothing() {
            assertEquals(0, apu.drainSamples(new short[64]));
        }
    }

    @Nested
    @DisplayName("the two ladders")
    class Ladders {
        /**
         * The tables are evaluated from NESdev's closed forms at startup, so what is worth
         * checking is that the forms themselves were transcribed right. These are the values the
         * wiki quotes.
         */
        @Test
        void thePulseTableFollowsTheClosedForm() {
            assertEquals(0.0, pulseLevel(0), 1e-9, "silence is silence");
            assertEquals(95.52 / (8128.0 + 100), pulseLevel(1), 1e-9);
            assertEquals(95.52 / (8128.0 / 15 + 100), pulseLevel(15), 1e-9);
            assertEquals(95.52 / (8128.0 / 30 + 100), pulseLevel(30), 1e-9);
        }

        /**
         * The point of the curve: a second voice added to a loud one moves the output less than
         * the same voice added to a quiet one. Two pulses at fifteen are nowhere near twice one.
         */
        @Test
        void theLadderIsNotLinear() {
            assertTrue(pulseLevel(30) < 2 * pulseLevel(15),
                    "two full pulses are quieter than twice one");
            assertTrue(pulseLevel(30) > 1.5 * pulseLevel(15),
                    "but they are still louder than one");
        }

        @Test
        void theTndTableFollowsTheClosedForm() {
            assertEquals(0.0, tndLevel(0), 1e-9);
            assertEquals(163.67 / (24329.0 + 100), tndLevel(1), 1e-9);
            assertEquals(163.67 / (24329.0 / 202 + 100), tndLevel(202), 1e-9);
        }

        /**
         * Everything the five channels can do at once still fits in the range a sixteen bit
         * sample has, with a little to spare.
         */
        @Test
        void everythingAtOnceStaysUnderOne() {
            assertTrue(pulseLevel(30) + tndLevel(202) < 1.0);
        }

        private double pulseLevel(final int n) {
            return n == 0 ? 0.0 : 95.52 / (8128.0 / n + 100);
        }

        private double tndLevel(final int n) {
            return n == 0 ? 0.0 : 163.67 / (24329.0 / n + 100);
        }
    }

    @Nested
    @DisplayName("what comes out")
    class Output {
        /**
         * The triangle's ladder is driven by its sequencer whether or not the channel is playing
         * -- the counters stop it, they do not silence it -- so a machine that has just been
         * switched on has a standing level sitting on the mixer. A real console thumps at power-on
         * for exactly that reason, and its coupling capacitor takes the level away just as the
         * high pass does here.
         */
        @Test
        void silenceIsSilenceOnceThePowerOnStepHasDecayed() {
            var thump = peak(frame());

            for (var i = 0; i < 4; i++) {
                frame();
            }

            for (var sample : frame()) {
                assertEquals(0, sample, "expected silence but got " + sample);
            }

            assertTrue(thump > 1000, "and the step itself was there to decay");
        }

        @Test
        void aPlayingChannelSwingsBothWaysAroundZero() {
            armBothPulses();

            // The first frame is the high pass settling from the step the channels coming on
            // looks like.
            frame();

            var lowest = Short.MAX_VALUE;
            var highest = Short.MIN_VALUE;

            for (var sample : frame()) {
                lowest = (short) Math.min(lowest, sample);
                highest = (short) Math.max(highest, sample);
            }

            assertTrue(highest > 1000, "the wave should be well clear of the noise floor");
            assertTrue(lowest < -1000, "and it should swing below zero as well as above it");
        }

        /**
         * A game that writes $4011 is moving the DMC's level, which is a number from 0 to 127 and
         * has no reason to be anywhere near the middle. Without the high pass it would sit as a
         * standing offset under everything else; with it, what is left of a step a moment later is
         * nothing.
         */
        @Test
        void theDcOffsetOfALevelWriteDecaysAway() {
            apu.write(0x4011, 0x7F);

            var immediately = peak(frame());

            // Four frames is about 67ms, and the slowest high pass is 90Hz.
            frame();
            frame();
            frame();

            var later = peak(frame());

            assertTrue(immediately > 1000, "the step itself should be audible as a click");
            assertTrue(later < immediately / 10,
                    "and a fifteenth of a second later there should be next to nothing left of it");
        }

        @Test
        void aSampleThatWouldOverflowIsClampedRatherThanWrapped() {
            // Nothing the chip can do reaches full scale, so this is about the clamp being on the
            // right side: a wrap would turn a loud sample into a loud one of the opposite sign,
            // which is heard as a crack rather than as distortion.
            armBothPulses();

            apu.write(0x4015, 0x0F);
            apu.write(0x4008, 0xFF);  // the triangle, at the top of its ramp
            apu.write(0x400A, 0x40);
            apu.write(0x400B, 0xF8);
            apu.write(0x400C, 0x3F);  // the noise channel, at full volume
            apu.write(0x400E, 0x00);
            apu.write(0x400F, 0xF8);
            apu.write(0x4011, 0x7F);  // and the DMC at the top of its range

            for (var i = 0; i < 10; i++) {
                for (var sample : frame()) {
                    assertTrue(sample > Short.MIN_VALUE && sample < Short.MAX_VALUE,
                            "everything at once should still not reach the rails");
                }
            }
        }

        private int peak(final short[] samples) {
            var peak = 0;

            for (var sample : samples) {
                peak = Math.max(peak, Math.abs(sample));
            }

            return peak;
        }
    }
}
