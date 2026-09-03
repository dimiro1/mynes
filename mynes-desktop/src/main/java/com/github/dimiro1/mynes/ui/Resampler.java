package com.github.dimiro1.mynes.ui;

/**
 * Stretches or squeezes a run of samples by a hair, and remembers where it got to.
 * <p>
 * What {@link AudioOutput} needs to hold the sound card's queue at a fixed depth: the ratios it asks
 * for are within half a percent of one, so this is not resampling in the sense of changing a rate,
 * it is taking up the slack between two clocks that were both meant to be 44100Hz.
 *
 * <h2>Why it has state</h2>
 *
 * The samples arrive a frame at a time and are one continuous stream, which are two different
 * things, and the difference is the whole reason this is a class rather than a method. An output
 * grid that started afresh at every frame would put a step at every frame boundary -- sixty a
 * second of them, which is a buzz at exactly the frame rate -- so where the grid got to
 * ({@link #position}) and the sample it needs to interpolate back to ({@link #previous}) both have
 * to survive the gap between one frame and the next.
 * <p>
 * Linear rather than anything better, which is worth being explicit about: a linear interpolator is
 * a gentle low pass whose depth varies with where between two samples the output happens to land,
 * and at these ratios it lands nearly everywhere. Its worst case is a half-sample delay, which at
 * 44.1kHz costs half a decibel at 5kHz and a tenth at 2kHz. It costs a great deal more than that at
 * the top of the band, and that is the part that does not matter: the APU has already rolled its own
 * output off at 14kHz, so there is next to nothing up there to lose.
 */
final class Resampler {

    /**
     * Where in the incoming stream the next outgoing sample falls, as an offset into the frame that
     * is about to arrive. Always in [0, 1) between calls.
     */
    private double position;

    /**
     * The last sample of the frame before, which is what the first sample of this one interpolates
     * back to. One sample of group delay, which is 23 microseconds.
     */
    private short previous;

    /**
     * The most {@link #resample} can produce, which is what the output array has to hold.
     * <p>
     * One more than the arithmetic needs, for the carried {@link #position}: a frame that starts its
     * grid just before the first input sample fits one extra output sample in before the end.
     */
    static int outputCapacity(final int count, final double ratio) {
        return (int) Math.ceil(count * ratio) + 2;
    }

    /**
     * Resamples one frame.
     *
     * @param in    the samples.
     * @param count how many of them are real.
     * @param ratio output samples per input sample. One is not a copy -- it still interpolates at
     *              whatever fraction the grid is sitting at -- which is deliberate: switching
     *              between resampling and not would be the step this class exists to avoid.
     * @param out   where they go, at least {@link #outputCapacity} long.
     * @return how many were written.
     */
    int resample(final short[] in, final int count, final double ratio, final short[] out) {
        var step = 1.0 / ratio;
        var written = 0;

        while (position < count && written < out.length) {
            var index = (int) position;
            var fraction = position - index;

            // index - 1 rather than index, so that the sample before this frame is the one the
            // interpolation reaches back for. Reaching forward instead would need the first sample
            // of the frame that has not arrived yet.
            double before = index == 0 ? previous : in[index - 1];
            double after = in[index];

            out[written++] = (short) Math.round(before + fraction * (after - before));

            position += step;
        }

        // What is left over is where the next frame's first output sample falls. The guard is for
        // the length check above having ended the loop early, which a properly sized array makes
        // impossible and which would otherwise leave the position pointing before a frame's start.
        position = Math.max(0.0, position - count);
        previous = in[count - 1];

        return written;
    }

    /**
     * Forgets where it was, for a stream that is about to start somewhere else entirely -- the far
     * side of a pause, a rewind or a loaded state, where there is nothing to be continuous with.
     */
    void reset() {
        position = 0;
        previous = 0;
    }
}
