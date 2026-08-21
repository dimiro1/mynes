package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.cheat.GameGenieCode;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * The log a {@link Movie} is made of, while it is still growing.
 * <p>
 * Driver-owned like {@link Rewind}, and <strong>deliberately not reachable from {@link NES}</strong>
 * -- {@code SaveStateCompletenessTests} walks everything the console can reach and scrambles every
 * array it finds, so a growing log hanging off a chip would be walked into and shredded. This
 * belongs to whoever is driving the machine, which is also the honest place for it: a machine does
 * not know that anybody is writing down what it does.
 *
 * <h2>One rule for every jump</h2>
 *
 * A recording is a straight line from its anchor. Three things break the line, and all three are
 * answered the same way -- <strong>the log describes the timeline that was finally played</strong>:
 * <ul>
 *   <li>{@link #rewound} within the recording truncates the tail. The frames that were taken back
 *       were played and un-played, so a replay never re-enacts them. This is exact rather than
 *       approximate because a rewound machine <em>is</em> the machine that never went forward.</li>
 *   <li>{@link #rewound} past the recording's own start re-anchors: the machine is now somewhere the
 *       log cannot describe, so the log starts again from where it now stands.</li>
 *   <li>{@link #jumped} -- a loaded save state -- re-anchors for the same reason. Anything else
 *       would be a movie whose buttons were played against a machine that had been swapped out from
 *       under them.</li>
 * </ul>
 * Re-anchoring costs the take so far, and the alternative costs the take's correctness.
 *
 * <h2>What is pinned, and when</h2>
 *
 * The cartridge digest, the mapper number, the region and the Game Genie codes are read once, at
 * construction. The first three cannot change under a running machine: a save state from another
 * cartridge or another region is refused, and the chips are built around their region. The codes
 * can, which is why both front ends refuse to change them while a recording is running -- a movie
 * whose header pinned one set of codes and whose frames were played against another is a file that
 * cannot be replayed and does not say so.
 */
public final class MovieRecorder {

    /**
     * How many frames of masks to make room for up front. Twenty seconds or so, doubled from there
     * -- the array is one byte a frame, so even an hour of play is under a quarter of a megabyte and
     * nothing here is worth a smarter structure.
     */
    private static final int INITIAL_FRAMES = 1024;

    /**
     * Roughly what one gzipped state comes to, so the buffer an anchor builds into rarely has to
     * grow. Nothing depends on it being right.
     */
    private static final int EXPECTED_STATE_BYTES = 16 * 1024;

    private final String romSHA256;
    private final int mapperNumber;
    private final Region region;
    private final List<GameGenieCode> genie;

    /**
     * The whole save state the movie starts from, or null when it starts at power on.
     */
    private byte[] anchor;

    private long anchorFrame;

    /**
     * One mask per finished frame, of which the first {@link #recorded} are real.
     */
    private byte[] buttons = new byte[INITIAL_FRAMES];

    private int recorded;

    /**
     * The frames Reset was pressed at the start of, as movie-relative indices. Strictly increasing,
     * which {@link #reset} keeps true and {@link #rewound} keeps true by dropping the tail.
     */
    private long[] resets = new long[0];

    private int resetCount;

    private MovieRecorder(
            final NES nes, final List<GameGenieCode> codes, final byte[] anchor) {
        this.romSHA256 = nes.getCart().sha256();
        this.mapperNumber = nes.getCart().mapperNumber();
        this.region = nes.getRegion();
        this.genie = List.copyOf(codes);
        this.anchor = anchor;
        this.anchorFrame = nes.getPPU().getFrame();
    }

    /**
     * Records from power on, carrying no state at all.
     * <p>
     * The smaller and the more portable of the two: the file is buttons and nothing else, and
     * anybody with the same cartridge can play it. It is only honest when the machine really is
     * untouched, which is what the check below is for -- and note that "untouched" includes the
     * cartridge's battery RAM, which a movie has no way to carry, so a session that filled it from a
     * {@code .sav} has to anchor instead.
     *
     * @throws MovieException if the machine has already run.
     */
    public static MovieRecorder atPowerOn(final NES nes, final List<GameGenieCode> codes) {
        if (nes.getPPU().getFrame() != 0) {
            throw new MovieException(
                    "a movie can only start at power on from frame 0, and this machine is at frame "
                            + nes.getPPU().getFrame() + ".");
        }

        return new MovieRecorder(nes, codes, null);
    }

    /**
     * Records from here, whatever "here" is, by putting the whole machine in the file.
     * <p>
     * What the window always does, because a player who has just decided to record something is
     * hardly ever sitting on a machine that has not run yet.
     */
    public static MovieRecorder anchoredAt(final NES nes, final List<GameGenieCode> codes) {
        return new MovieRecorder(nes, codes, capture(nes));
    }

    /**
     * Writes down the mask that was in force for the frame that has just finished.
     * <p>
     * To be called at the end of every finished frame and nowhere else, which is the same contract
     * {@link Rewind#capture} has and for a sharper reason: this counts in frames, so a call from
     * anywhere but a frame boundary puts every later index out by one and the replay diverges from
     * that point on.
     */
    public void frame(final int mask) {
        if (recorded == buttons.length) {
            buttons = Arrays.copyOf(buttons, buttons.length * 2);
        }

        buttons[recorded++] = (byte) mask;
    }

    /**
     * The console's Reset button, pressed at the start of the frame that is about to run.
     * <p>
     * Called before the machine is told, so that the index written down is the frame the reset will
     * be seen in rather than the one before it. Pressing it twice before a frame runs is one press:
     * the console has one button and a replay applies it once.
     */
    public void reset() {
        if (resetCount > 0 && resets[resetCount - 1] == recorded) {
            return;
        }

        if (resetCount == resets.length) {
            resets = Arrays.copyOf(resets, Math.max(8, resets.length * 2));
        }

        resets[resetCount++] = recorded;
    }

    /**
     * The machine has gone back {@code frames} frames.
     * <p>
     * <strong>Frames, not rewind steps.</strong> {@link Rewind#rewind} answers in states, and a
     * state is {@link Rewind#interval()} frames -- so the window, which keeps one every other frame,
     * has to hand over the difference in the PPU's own frame counter rather than what that call
     * returned.
     */
    public void rewound(final NES nes, final long frames) {
        if (frames <= 0) {
            return;
        }

        if (frames > recorded) {
            // Back past the start of the recording, so there is no longer a log that describes where
            // the machine is. Starting again from here is the only honest answer, and it keeps the
            // take alive rather than throwing away everything somebody is about to play next.
            reanchor(nes);
            return;
        }

        recorded -= (int) frames;

        while (resetCount > 0 && resets[resetCount - 1] >= recorded) {
            resetCount--;
        }
    }

    /**
     * The machine has been replaced wholesale -- a loaded save state, and nothing else.
     * <p>
     * Not a rewind, which has its own method and its own answer: this is a machine nobody played
     * their way to, so there is no timeline to truncate.
     */
    public void jumped(final NES nes) {
        reanchor(nes);
    }

    /**
     * How many frames are in the log, which is where the next one will go.
     */
    public long framesRecorded() {
        return recorded;
    }

    /**
     * Whether the recording carries a state to start from, rather than starting at power on. Can
     * become true part way through a take, since a rewind past the start or a loaded state
     * re-anchors it.
     */
    public boolean anchored() {
        return anchor != null;
    }

    /**
     * The frame the recording currently starts on.
     */
    public long anchorFrame() {
        return anchorFrame;
    }

    /**
     * The movie as it stands. Cheap enough to ask for repeatedly: it copies the log rather than
     * freezing it, so recording can carry on afterwards.
     */
    public Movie movie() {
        return new Movie(
                new Movie.Header(
                        Movie.VERSION,
                        romSHA256,
                        mapperNumber,
                        region,
                        anchor != null,
                        anchorFrame,
                        recorded,
                        Movie.PORTS),
                anchor,
                Arrays.copyOf(buttons, recorded),
                null,
                Arrays.copyOf(resets, resetCount),
                genie);
    }

    private void reanchor(final NES nes) {
        anchor = capture(nes);
        anchorFrame = nes.getPPU().getFrame();
        recorded = 0;
        resetCount = 0;
    }

    private static byte[] capture(final NES nes) {
        var out = new ByteArrayOutputStream(EXPECTED_STATE_BYTES);

        try {
            SaveState.write(nes, out);
        } catch (IOException e) {
            // ByteArrayOutputStream does not fail, and neither does the deflater over it.
            throw new AssertionError("a state written to memory cannot fail", e);
        }

        return out.toByteArray();
    }
}
