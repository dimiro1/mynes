package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;

/**
 * The last few seconds of the machine, kept so that they can be run backwards.
 * <p>
 * A ring that drops its oldest entry when it is full, and one invariant holding the whole thing up:
 * <strong>the newest entry is never newer than the machine.</strong> {@link #capture} keeps that
 * true going forwards, and {@link #rewind} keeps it true going backwards by moving onto the newest
 * entry that is genuinely behind -- which means throwing the top away first when the top is exactly
 * where the machine is standing, and loading it as it is when the machine has run on past it.
 * <p>
 * Two things follow, and both are the answer somebody holding the key down wants. Running out parks
 * on the oldest frame still kept rather than failing -- a key held too long is a key held too long,
 * not a mistake. And a rewind of nothing is nothing: no state is reloaded, so a caller may ask on
 * every frame without paying for the frames where there was nowhere to go.
 *
 * <h2>The interval</h2>
 *
 * An entry is not necessarily a frame. {@link #Rewind(int, int)} takes a number of frames to leave
 * between captures, and the window uses two, which buys three things at once: half the capture cost,
 * twice the history for the same memory, and -- because a caller pops one entry per display tick --
 * a rewind that runs backwards at twice speed instead of taking five seconds to undo five seconds.
 * The price is landing on even frames only, which is half a frame of imprecision about where letting
 * go of the key leaves the game.
 * <p>
 * Going wider than a frame or two is where it would start to show. The states in between are not
 * recoverable by any means -- nothing here re-emulates -- so the interval is exactly the granularity
 * of the whole feature.
 *
 * <h2>Why whole save states</h2>
 *
 * Every entry is a {@link SaveState}, unchanged and entire. That sounds expensive and is not: the
 * body is gzipped, and a state taken mid-level comes to 2.5KB on Super Mario Bros., 7KB on Super
 * Mario Bros. 2 and 13KB at the worst moment of Contra -- so the thirty seconds of NTSC history the
 * window keeps is 4 to 22MB rather than the hundreds of megabytes the raw figures suggest. Most of a
 * state is the framebuffer and the cartridge's RAM, and both of those are largely one repeated value.
 * <p>
 * A diff against the previous frame would still be smaller, and it is not worth writing. This format
 * is the one {@code SaveStateDivergenceTests} already proves round-trips from anywhere,
 * mid-instruction and mid-scanline included; a second, cheaper, less tested way of putting a machine
 * back is a second way for it to come back subtly wrong.
 * <p>
 * The cartridge and region checks {@link SaveState#read} makes are trivially satisfied here, since
 * the state is being put back into the machine it was taken from a moment ago. They cost a string
 * comparison and they are left in: this is not a private format, it is the format.
 * <p>
 * It also carries the framebuffer, which is what makes showing a rewound frame free. There is no
 * re-emulation anywhere below -- the picture the display wants arrives with the state.
 *
 * <h2>What it is not attached to</h2>
 *
 * Deliberately not reachable from {@link NES}, and not for tidiness. {@code SaveStateCompletenessTests}
 * walks everything the console can reach and scrambles every array it finds; a ring of states hanging
 * off a chip would be walked into and shredded. This belongs to whoever is driving the machine --
 * the window's emulation thread, or a headless session -- which is also the honest place for it,
 * since a machine does not know how it got to where it is.
 * <p>
 * The history is wall-clock rather than causal, and rewinding through a reset or a loaded slot is
 * allowed on purpose: those are things that happened, and the point of holding a key down is to
 * undo what just happened. One consequence worth knowing about is that an external
 * {@link SaveState#read} -- a quick-load, say -- leaves the newest entry describing a machine that
 * is no longer there, and so breaks the invariant until the next {@link #capture}. The first rewind
 * step discards that entry rather than loading it, so the damage is one frame of history rather than
 * a machine put back to the wrong place.
 */
public final class Rewind {

    /**
     * Below this there is nothing to rewind to: one entry is where the machine already is.
     */
    public static final int MINIMUM_CAPACITY = 2;

    /**
     * Roughly what one gzipped state comes to, so the buffer a capture builds into rarely has to
     * grow. Nothing depends on it being right.
     */
    private static final int EXPECTED_STATE_BYTES = 16 * 1024;

    private final int capacity;
    private final int interval;

    /**
     * Newest last, which is the end both {@link #capture} and {@link #rewind} work from.
     */
    private final ArrayDeque<byte[]> states;

    /**
     * How many more calls to {@link #capture} to wave through before taking one. Zero means the next
     * one is due, which is why a fresh ring captures the moment it is asked to.
     */
    private int untilCapture;

    /**
     * Which frame the newest entry was taken on, so {@link #rewind} can tell whether the machine is
     * standing on it or has run on past it. Meaningless while the ring is empty.
     */
    private long newestFrame = -1;

    /**
     * A state for every frame.
     *
     * @param capacity how many states to keep, at least {@link #MINIMUM_CAPACITY}.
     */
    public Rewind(final int capacity) {
        this(capacity, 1);
    }

    /**
     * @param capacity how many states to keep, at least {@link #MINIMUM_CAPACITY}.
     * @param interval how many frames apart to take them. 1 for every frame; 2 for every other,
     *                 which is what the window uses.
     */
    public Rewind(final int capacity, final int interval) {
        if (capacity < MINIMUM_CAPACITY) {
            throw new IllegalArgumentException(
                    "a rewind ring holds at least " + MINIMUM_CAPACITY + " states, since one of them"
                            + " is where the machine already is -- not " + capacity + ".");
        }

        if (interval < 1) {
            throw new IllegalArgumentException(
                    "states are taken every " + MINIMUM_CAPACITY + " frames at the widest and every"
                            + " frame at the narrowest -- not every " + interval + ".");
        }

        this.capacity = capacity;
        this.interval = interval;
        this.states = new ArrayDeque<>(capacity);
    }

    /**
     * How many states this keeps once it is full. Multiply by {@link #interval()} for the frames of
     * history that comes to.
     */
    public int capacity() {
        return capacity;
    }

    /**
     * How many frames apart the states are taken.
     */
    public int interval() {
        return interval;
    }

    /**
     * How far back {@link #rewind} could go right now, which is one less than what is held: the
     * newest entry is the machine as it stands.
     */
    public int rewindable() {
        return Math.max(0, states.size() - 1);
    }

    /**
     * Offers the machine to the ring, which writes it down if a state is due and counts the frame
     * otherwise. The oldest is dropped when the ring is full.
     * <p>
     * To be called at the end of every finished frame, including the ones it is going to throw away
     * -- the counting is what an interval is made of. It is for the end of a frame and nowhere else:
     * a capture taken part way through one is not wrong, since the format handles it, but a ring of
     * them no longer counts in frames and every number a caller reports about it stops meaning what
     * it says.
     */
    public void capture(final NES nes) {
        if (untilCapture > 0) {
            untilCapture--;
            return;
        }

        var out = new ByteArrayOutputStream(EXPECTED_STATE_BYTES);

        try {
            SaveState.write(nes, out);
        } catch (IOException e) {
            // ByteArrayOutputStream does not fail, and neither does the deflater over it.
            throw new AssertionError("a state written to memory cannot fail", e);
        }

        if (states.size() == capacity) {
            states.removeFirst();
        }

        states.addLast(out.toByteArray());
        newestFrame = nes.getPPU().getFrame();
        untilCapture = interval - 1;
    }

    /**
     * Puts the machine back {@code steps} states, or as far back as there is history for.
     * <p>
     * The states gone past are forgotten rather than kept to go forwards again. Rewinding is how you
     * take back what just happened; what happens instead is captured from here as it is played, and
     * an undo of the undo would be a different feature with a different key on it.
     *
     * @param steps how many states to go back, each one {@link #interval()} frames. Zero or less, or
     *              an empty ring, moves nothing and leaves the machine untouched -- so there is no
     *              reload to pay for on a tick with nowhere to go.
     * @return how many states it actually moved, which is fewer than asked for when the history ran
     * out.
     */
    public int rewind(final NES nes, final int steps) {
        if (states.isEmpty()) {
            return 0;
        }

        // Whether the top entry is where the machine is standing or somewhere it has already run on
        // from. Both happen: with an interval of one it is standing on it at every frame boundary,
        // and with a wider one it is past it on all but every nth frame. A state loaded from outside
        // -- a quick-load -- lands here too, and is treated as the ordinary case, so the first step
        // back goes past the entry that no longer describes anything rather than onto it.
        var pastTheTop = nes.getPPU().getFrame() > newestFrame;

        var moved = Math.min(Math.max(steps, 0), rewindable() + (pastTheTop ? 1 : 0));

        if (moved == 0) {
            return 0;
        }

        // The first step is free when the machine has already left the top behind: there is a state
        // to go back onto without discarding anything. Otherwise the top is discarded and the entry
        // it was covering is loaded, which is the order that keeps the newest entry from describing
        // a machine that is no longer there.
        var discarded = pastTheTop ? moved - 1 : moved;

        for (var i = 0; i < discarded; i++) {
            states.removeLast();
        }

        try {
            SaveState.read(nes, new ByteArrayInputStream(states.getLast()));
        } catch (IOException e) {
            throw new AssertionError("a state read from memory cannot fail", e);
        }

        // Read back off the machine rather than tracked: the state that has just landed put the
        // frame counter exactly where it was when that state was taken.
        newestFrame = nes.getPPU().getFrame();

        // And the next capture is a whole interval away, so resuming lays the new timeline down on
        // the same spacing the old one had rather than one frame out of step with it.
        untilCapture = interval - 1;

        return moved;
    }

    /**
     * How many frames of history {@code seconds} comes to on this machine.
     * <p>
     * Here rather than worked out by each front end, because the two would drift: it is 1803 frames
     * on NTSC and 1500 on PAL for the same thirty seconds, and neither is thirty times a round
     * number. A region is what says how long a frame is; this is the only place that has to know it.
     */
    public static int framesFor(final Region region, final int seconds) {
        return (int) Math.round(seconds * 1e9 / region.frameNanos());
    }
}
