package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Controller;

/**
 * How often the game has looked at the pads, one frame at a time.
 * <p>
 * The controllers count polls and bits for their whole lives -- see {@link Controller#getPolls()}
 * -- and a lifetime total says nothing anybody wants to know. What a game does with its pad is a
 * <em>rate</em>: once a frame, every frame, at the top of the main loop or in the NMI. So the
 * interesting number is the difference between one frame boundary and the next, and the interesting
 * event is a difference of zero.
 * <p>
 * <b>A frame with no poll in it is a frame the game did not finish.</b> That is the whole point of
 * keeping this. The main loop that would have read the pad was still working when the next frame
 * began, so the game skipped a turn -- the every-other-frame stutter Super Mario Bros. 3 and
 * Gradius get under load, and precisely what {@code --hack overclock} exists to undo. Nothing else
 * in the front end can see it: the picture simply shows the last frame again, which looks like a
 * game standing still on purpose.
 * <p>
 * Which is why the window is a run of frames rather than a total. Lag comes in patterns, and the
 * pattern is the diagnosis: a mark every other frame is a loop that overruns by a little, a run of
 * them is a level loading, and one every few seconds is a garbage collection in the host rather
 * than anything the game did.
 * <p>
 * The emulation thread owns one of these and nothing else touches it. It is asked only while
 * somebody is watching -- the {@link com.github.dimiro1.mynes.debug.Debugger#isArmed()} rule -- and
 * {@link #frameEnded} is given the frame number so that it can tell a run of frames from two frames
 * with a gap between them. A gap means the panel was shut for a while, or the machine was rewound
 * past what was measured, and in both cases the frames in between were not counted: the baselines
 * start again rather than reporting one frame's worth of nothing as a lag frame.
 */
final class PadPolling {
    /**
     * How many frames the window holds. {@link Readout.Pads#WINDOW}, because the panel drawing it
     * has to know the same number and needs it before the first readout arrives.
     */
    private static final int WINDOW = Readout.Pads.WINDOW;

    /**
     * One entry per frame, true where the game latched the pad. A ring, oldest wherever
     * {@link #at} points once it has filled.
     */
    private final boolean[] polled = new boolean[WINDOW];

    private int at;
    private int filled;

    /**
     * Which frame {@link #frameEnded} was last called for. What the next call is checked against:
     * anything but one more than this means frames went by uncounted.
     * <p>
     * Starts at a number no frame can be one more than, so that the first frame counted is the
     * baseline rather than a measurement. It could be measured -- the counters really are zero at
     * power on -- but the first call in practice is whatever frame the panel was opened on, and one
     * rule for both is one rule to keep true.
     */
    private long lastFrame = Long.MIN_VALUE;

    private long polls1;
    private long bits1;
    private long polls2;
    private long bits2;

    /**
     * What the frame that just finished came to, which is what a readout carries.
     */
    private int framePolls1;
    private int frameBits1;
    private int framePolls2;
    private int frameBits2;

    /**
     * A frame has finished. Called on the emulation thread, at the boundary, with the machine
     * standing still.
     *
     * @param frame which frame this is, counted the way the loop counts them.
     * @param one   the first pad.
     * @param two   the second.
     */
    void frameEnded(final long frame, final Controller one, final Controller two) {
        if (frame != lastFrame + 1) {
            // Frames went by that nobody counted, so there is no difference to take. Everything is
            // rebased on this frame and the window starts again: a lag mark here would say the game
            // missed a poll when what really happened is that nobody was looking.
            restart(frame, one, two);
            return;
        }

        lastFrame = frame;

        framePolls1 = advance(one.getPolls() - polls1);
        frameBits1 = advance(one.getBitsRead() - bits1);
        framePolls2 = advance(two.getPolls() - polls2);
        frameBits2 = advance(two.getBitsRead() - bits2);

        polls1 = one.getPolls();
        bits1 = one.getBitsRead();
        polls2 = two.getPolls();
        bits2 = two.getBitsRead();

        // Pad one alone, because a single write to $4016 latches both ports: asking the second pad
        // as well would be asking the same question twice and answering "the game polled" for a
        // machine with no second controller in it.
        polled[at] = framePolls1 > 0;
        at = (at + 1) % WINDOW;
        filled = Math.min(filled + 1, WINDOW);
    }

    /**
     * The window and the last frame's counts, as a readout carries them. Built fresh each time and
     * shared with nothing, since it is about to cross to the event dispatch thread.
     */
    Readout.Pads snapshot() {
        var window = new boolean[filled];
        var lag = 0;

        for (var i = 0; i < filled; i++) {
            // Oldest first, so that the strip drawn from it reads left to right the way time does.
            window[i] = polled[(at - filled + i + 2 * WINDOW) % WINDOW];

            if (!window[i]) {
                lag++;
            }
        }

        return new Readout.Pads(framePolls1, frameBits1, framePolls2, frameBits2, window, lag);
    }

    private void restart(final long frame, final Controller one, final Controller two) {
        lastFrame = frame;

        polls1 = one.getPolls();
        bits1 = one.getBitsRead();
        polls2 = two.getPolls();
        bits2 = two.getBitsRead();

        framePolls1 = 0;
        frameBits1 = 0;
        framePolls2 = 0;
        frameBits2 = 0;

        at = 0;
        filled = 0;
    }

    /**
     * A difference, brought down to an {@code int}. The counters are longs so that they cannot
     * wrap, and one frame's worth of either is a handful.
     */
    private static int advance(final long difference) {
        return (int) Math.min(difference, Integer.MAX_VALUE);
    }
}
