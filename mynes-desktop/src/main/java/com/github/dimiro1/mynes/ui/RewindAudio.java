package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.APU;

/**
 * The sound that went with the last few seconds, so that rewinding can play it backwards.
 * <p>
 * A rewind with the sound cut is a rewind that feels broken: the picture is doing something and the
 * speaker has stopped. Playing the game's own audio in reverse is the thing that reads as rewinding
 * rather than as a fault -- it is what Braid does, and what a tape did before that.
 * <p>
 * Beside {@link com.github.dimiro1.mynes.state.Rewind} rather than inside it, and in this module
 * rather than the core, because it is a fact about having a sound card. A headless run has no
 * speaker and a save state has no sound in it: the APU's sample ring is deliberately left out of the
 * format, on the grounds that it is the queue between the chip and the card rather than the chip.
 * That is the right decision and this is what it costs -- the sound has to be kept separately,
 * fed on exactly the frames the state ring is fed on, or the two would drift and the rewind would
 * play the wrong seconds.
 *
 * <h2>Why it is one array</h2>
 *
 * A frame is about 735 samples, and the obvious shape -- a queue of {@code short[]} -- would make
 * sixty arrays a second of garbage on the emulation thread, which is the one thread with a deadline.
 * So it is one flat buffer of fixed slots with the frames written round it, and after the
 * constructor nothing here allocates anything. Thirty seconds costs about 3MB, which is small beside
 * the states it accompanies.
 */
final class RewindAudio {

    /**
     * How big one frame's slot is. Sized for PAL, which fits 882 samples into its longer frame
     * against NTSC's 735 -- one number for both, since the few kilobytes it wastes on an NTSC
     * machine are not worth a second code path.
     */
    private static final int SAMPLES_PER_FRAME = APU.SAMPLE_RATE / 50 + 16;

    private final int capacity;
    private final short[] samples;
    private final int[] counts;

    /**
     * The slot the newest frame went into, and how many slots hold anything. Together they are the
     * ring: there is no separate read cursor because the only reader walks backwards from the
     * newest, which is what rewinding is.
     */
    private int newest = -1;
    private int size;

    /**
     * @param capacity how many frames of sound to keep. The same number of frames the states cover,
     *                 so the two run out together.
     */
    RewindAudio(final int capacity) {
        this.capacity = capacity;
        this.samples = new short[capacity * SAMPLES_PER_FRAME];
        this.counts = new int[capacity];
    }

    /**
     * Writes down a frame's worth of sound, dropping the oldest when full.
     * <p>
     * To be called for <em>every</em> finished frame, including the silent ones and the ones whose
     * sound is not being played. A frame missing from here is a frame the rewind would take its
     * sound from the wrong side of.
     */
    void capture(final short[] from, final int count) {
        newest = (newest + 1) % capacity;

        // A frame that ran long can produce more than a slot holds. Losing the tail of it is a
        // handful of samples out of a rewind that is already a scrub.
        var kept = Math.clamp(count, 0, SAMPLES_PER_FRAME);

        System.arraycopy(from, 0, samples, newest * SAMPLES_PER_FRAME, kept);
        counts[newest] = kept;

        if (size < capacity) {
            size++;
        }
    }

    /**
     * Takes the newest {@code frames} frames back off the ring and lays them into {@code into}
     * newest first, each one backwards -- which is those frames played in reverse.
     * <p>
     * Taken rather than read, so that the sound goes away with the states it belongs to and cannot
     * be played twice.
     *
     * @return how many samples landed in {@code into}, which is 0 once the history has run out.
     */
    int take(final int frames, final short[] into) {
        var written = 0;

        for (var i = 0; i < frames && size > 0; i++) {
            var base = newest * SAMPLES_PER_FRAME;

            for (var sample = counts[newest] - 1; sample >= 0 && written < into.length; sample--) {
                into[written++] = samples[base + sample];
            }

            newest = (newest + capacity - 1) % capacity;
            size--;
        }

        return written;
    }

    /**
     * How many frames of sound are held.
     */
    int size() {
        return size;
    }
}
