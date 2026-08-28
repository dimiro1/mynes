package com.github.dimiro1.mynes.ui;

/**
 * How fast the machine is actually running, worked out from a counter and a clock.
 * <p>
 * A frame rate is the one thing on the status bar that nothing announces. Every other thing there
 * is a setting somebody chose, and the window is told when it changes; this has to be measured, and
 * measuring it is two readings of {@link EmulatorRunner#getFramesRun()} and the time between them.
 * <p>
 * Kept apart from the bar that shows it because it is the only part with arithmetic in it, and
 * because the arithmetic is worth testing without a window and without waiting for a clock:
 * {@link #sample} is handed both numbers rather than reading either.
 * <p>
 * The interval it divides by is the one that actually elapsed rather than the one the caller's
 * timer asked for. A Swing timer fires late under load, which is exactly the moment the rate is
 * worth reading, and dividing by the period that was requested would report the drop as a smaller
 * one than it was.
 */
final class FrameRate {

    /**
     * What {@link #sample} answers when there is no interval to divide by yet. Negative because no
     * real rate can be: a machine that is doing nothing is running at zero, which is a different
     * thing to say and worth saying.
     */
    static final int UNKNOWN = -1;

    private static final double NANOS_PER_SECOND = 1_000_000_000.0;

    /**
     * How far a new measurement has to be from the one already on show before it replaces it.
     * <p>
     * A frame counter is a whole number, so a window of about a second catches either 60 frames or
     * 61 of a machine running at the NTSC chip's 60.0988 -- and a bar flickering between the two
     * would be reporting the arithmetic rather than anything about the game. One frame in the
     * window is exactly that rounding and nothing else, so a reading that close to the last one is
     * not news. Anything further is, and moves immediately: this steadies a number that is not
     * changing, it does not slow down one that is.
     */
    private static final int DEADBAND = 1;

    /**
     * Whether {@link #frames} and {@link #nanos} hold a reading, as against the zeroes a field
     * starts at. A machine really can be at frame zero at nanosecond zero as far as this class can
     * tell, so the two numbers cannot answer this themselves.
     */
    private boolean sampled;

    private long frames;
    private long nanos;
    private int rate = UNKNOWN;

    /**
     * Notes where the counter stands, and answers what it has been running at since the last time
     * it was asked.
     *
     * @param frames what {@link EmulatorRunner#getFramesRun()} says now.
     * @param nanos  what {@link System#nanoTime()} says now.
     * @return frames a second, rounded, or {@link #UNKNOWN} when this reading starts an interval
     *         rather than closing one. Held at the last answer when the new one is within
     *         {@link #DEADBAND} of it.
     */
    int sample(final long frames, final long nanos) {
        var elapsed = nanos - this.nanos;
        var ran = frames - this.frames;

        // A counter that has gone backwards belongs to a machine built since the last reading, and
        // the gap between one machine's counter and another's is not an interval of anything.
        // reset() is how that is normally said; this is the guard for the times nobody said it.
        var restarted = !sampled || ran < 0;

        // Two readings from the same instant are not an interval either -- and taking the second as
        // the new baseline would throw away whatever ran between them, so the reading that finally
        // gets an interval would be short by exactly those frames.
        if (!restarted && elapsed <= 0) {
            return rate;
        }

        if (restarted) {
            rate = UNKNOWN;
        } else {
            var measured = (int) Math.round(ran * NANOS_PER_SECOND / elapsed);

            if (rate == UNKNOWN || Math.abs(measured - rate) > DEADBAND) {
                rate = measured;
            }
        }

        this.frames = frames;
        this.nanos = nanos;
        sampled = true;

        return rate;
    }

    /**
     * Forgets the last reading, so the next one starts an interval instead of closing one.
     * <p>
     * For the moment a new machine is built. Its counter starts again from zero, and an interval
     * measured across the changeover would be a rate for a machine that no longer exists divided by
     * however long it took to build its replacement.
     */
    void reset() {
        sampled = false;
        rate = UNKNOWN;
    }
}
