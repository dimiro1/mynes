package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the number on the status bar, which is the one thing there that is measured rather than
 * chosen.
 * <p>
 * Both readings are handed in, so none of this waits for a clock or runs a machine: a second is
 * {@code 1_000_000_000} and a late timer is whatever number is written next to it.
 */
class FrameRateTests {
    private static final long SECOND = 1_000_000_000L;

    @Test
    void theFirstReadingHasNoIntervalToDivideBy() {
        assertEquals(FrameRate.UNKNOWN, new FrameRate().sample(0, 0));
    }

    @Test
    void aSecondReadingIsTheFramesOverTheTimeBetweenThem() {
        var rate = new FrameRate();

        rate.sample(0, 0);

        assertEquals(60, rate.sample(60, SECOND));
    }

    @Test
    void aHalfSecondOfThirtyFramesIsStillSixty() {
        var rate = new FrameRate();

        rate.sample(0, 0);

        assertEquals(60, rate.sample(30, SECOND / 2));
    }

    /**
     * The point of dividing by the interval that actually elapsed. A Swing timer asked for half a
     * second fires later than that under load, which is exactly when the rate is worth reading --
     * and dividing by the period that was asked for would report the drop as a smaller one than it
     * was.
     */
    @Test
    void theIntervalIsTheOneThatElapsedRatherThanTheOneAskedFor() {
        var rate = new FrameRate();

        rate.sample(0, 0);

        assertEquals(30, rate.sample(30, SECOND), "half a second's worth of frames, a second late");
    }

    @Test
    void aRateIsRoundedRatherThanTruncated() {
        var rate = new FrameRate();

        rate.sample(0, 0);

        assertEquals(61, rate.sample(121, 2 * SECOND), "60.5 frames a second");
    }

    @Test
    void aMachineThatIsNotClockingAnythingRunsAtZero() {
        var rate = new FrameRate();

        rate.sample(60, 0);

        assertEquals(0, rate.sample(60, SECOND));
    }

    /**
     * Two readings from the same instant are not an interval -- and the second must not become the
     * new baseline, or the frames counted between them would be lost from the reading that finally
     * gets one.
     */
    @Test
    void twoReadingsAtOneInstantKeepTheLastAnswerAndLoseNoFrames() {
        var rate = new FrameRate();

        rate.sample(0, 0);

        assertEquals(60, rate.sample(60, SECOND));
        assertEquals(60, rate.sample(90, SECOND), "the answer from the interval that did happen");
        assertEquals(60, rate.sample(120, 2 * SECOND), "60 frames since the last real reading");
    }

    /**
     * One frame in the window is the rounding and nothing else -- a machine at the NTSC chip's
     * 60.0988 gives 60 frames in one second and 61 in the next -- so a reading that close to the
     * last is held rather than shown. A real drop is not held for a moment.
     */
    @Test
    void aReadingOneFrameFromTheLastIsRoundingRatherThanNews() {
        var rate = new FrameRate();

        rate.sample(0, 0);

        assertEquals(60, rate.sample(60, SECOND));
        assertEquals(60, rate.sample(121, 2 * SECOND), "61, which is one frame of rounding");
        assertEquals(60, rate.sample(180, 3 * SECOND), "59, the same thing the other way");
        assertEquals(45, rate.sample(225, 4 * SECOND), "and a machine that really slowed down");
    }

    @Test
    void aCounterThatWentBackwardsStartsAnIntervalRatherThanClosingOne() {
        var rate = new FrameRate();

        rate.sample(0, 0);
        rate.sample(600, 10 * SECOND);

        assertEquals(FrameRate.UNKNOWN, rate.sample(0, 11 * SECOND), "a machine of its own");
        assertEquals(60, rate.sample(60, 12 * SECOND), "and measured from there");
    }

    @Test
    void resettingStartsAnIntervalRatherThanClosingOne() {
        var rate = new FrameRate();

        rate.sample(0, 0);
        rate.sample(600, 10 * SECOND);

        rate.reset();

        assertEquals(FrameRate.UNKNOWN, rate.sample(0, 11 * SECOND));
        assertEquals(50, rate.sample(50, 12 * SECOND));
    }
}
