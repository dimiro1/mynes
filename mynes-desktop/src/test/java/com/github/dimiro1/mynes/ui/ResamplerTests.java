package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The half a percent that keeps the sound card's queue where it was put.
 * <p>
 * Two things are worth holding here, and the second is the one that would break silently. The rate
 * has to come out right over a long run, or the queue this exists to steer is not being steered.
 * And the frame boundaries have to be invisible: the same stream cut into different-sized pieces has
 * to resample to the same samples, because a discontinuity at a frame boundary is a buzz at sixty
 * hertz -- which is exactly the fault this whole arrangement was built to remove.
 */
class ResamplerTests {

    /**
     * One NTSC frame's worth, near enough.
     */
    private static final int FRAME = 735;

    private final Resampler resampler = new Resampler();

    /**
     * A ramp, which is the signal linear interpolation is exact on -- so anything that comes out
     * bent is the arithmetic rather than the interpolation.
     */
    private static short[] ramp(final int from, final int count) {
        var samples = new short[count];

        for (var i = 0; i < count; i++) {
            samples[i] = (short) (from + i);
        }

        return samples;
    }

    private short[] resample(final short[] in, final double ratio) {
        var out = new short[Resampler.outputCapacity(in.length, ratio)];
        var written = resampler.resample(in, in.length, ratio, out);
        var trimmed = new short[written];

        System.arraycopy(out, 0, trimmed, 0, written);

        return trimmed;
    }

    @Nested
    @DisplayName("how many come out")
    class Rate {
        @Test
        void aRatioOfOneGivesBackAsManyAsItWasGiven() {
            assertEquals(FRAME, resample(ramp(0, FRAME), 1.0).length);
        }

        @Test
        void stretchingGivesBackMoreAndSqueezingFewer() {
            assertTrue(resample(ramp(0, FRAME), 1.005).length > FRAME);
            assertTrue(resample(ramp(0, FRAME), 0.995).length < FRAME);
        }

        /**
         * The point of the whole thing. Over a minute of frames the total has to be the ratio times
         * the input to within a sample or two, or the queue drifts anyway and this bought nothing --
         * which is the fault it would be easiest to ship, since a single frame looks right whatever
         * the fraction does.
         */
        @Test
        void theFractionIsCarriedRatherThanDropped() {
            var ratio = 1.003;
            var frames = 3600;
            var total = 0;

            for (var i = 0; i < frames; i++) {
                total += resample(ramp(0, FRAME), ratio).length;
            }

            assertEquals(frames * FRAME * ratio, total, 2.0);
        }

        @Test
        void theCapacityIsEnoughForWhatItProduces() {
            // Nothing is lost to the array running out, which is what the count above proves at one
            // ratio and this proves across the range.
            for (var ratio = 0.995; ratio <= 1.0051; ratio += 0.0005) {
                var out = new short[Resampler.outputCapacity(FRAME, ratio)];

                assertTrue(resampler.resample(ramp(0, FRAME), FRAME, ratio, out) < out.length,
                        "at " + ratio + " it filled the array it was given");
            }
        }
    }

    @Nested
    @DisplayName("across the frame boundaries")
    class Continuity {
        /**
         * One stream, cut two ways. A resampler that started its output grid afresh each frame would
         * pass every test above and fail this one, and what it sounds like is a buzz at the frame
         * rate.
         */
        @Test
        void theSameStreamResamplesTheSameWhereverItIsCut() {
            var whole = resample(ramp(0, 4 * FRAME), 1.004);

            resampler.reset();

            var pieces = new short[0];

            for (var piece = 0; piece < 4; piece++) {
                var next = resample(ramp(piece * FRAME, FRAME), 1.004);
                var joined = new short[pieces.length + next.length];

                System.arraycopy(pieces, 0, joined, 0, pieces.length);
                System.arraycopy(next, 0, joined, pieces.length, next.length);

                pieces = joined;
            }

            assertArrayEquals(whole, pieces);
        }

        /**
         * A flat signal comes out flat, which is what stops a filter that is on all the time from
         * being audible on a held note. The first sample is the one before the stream started, which
         * is the interpolator's one sample of delay rather than a step.
         */
        @Test
        void aConstantRunComesOutConstant() {
            var flat = new short[FRAME];
            Arrays.fill(flat, (short) 1234);

            // The first frame carries in the delay, from a resampler that has seen nothing.
            resample(flat, 1.002);

            for (var sample : resample(flat, 1.002)) {
                assertEquals(1234, sample);
            }
        }

        @Test
        void resettingForgetsWhereItWas() {
            resample(ramp(0, FRAME), 1.005);
            resampler.reset();

            var first = resample(ramp(1000, FRAME), 1.005)[0];

            assertEquals(0, first, "a reset resampler interpolates from silence, not from before");
        }
    }
}
