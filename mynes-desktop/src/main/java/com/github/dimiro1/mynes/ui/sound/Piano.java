package com.github.dimiro1.mynes.ui.sound;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.debugger.Theme;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.util.EnumMap;
import java.util.Map;

/**
 * The three voices that make a pitch, put where a musician would look for them.
 * <p>
 * The rows above already say what note each one is on, and this says nothing they do not -- what it
 * adds is the <em>relation</em> between them, which a column of names cannot show. Whether the two
 * pulses are a third apart or a semitone apart, whether the bass is an octave under the melody or
 * fighting it, whether a part has jumped an octave because a period wrapped: all of those are a
 * glance here and arithmetic there.
 * <p>
 * <b>Eighty-eight keys, A0 to C8, because that is also what the chip can play.</b> The triangle at
 * its longest period is 27.3Hz, which is A0 to within a few cents, and the pulses run out at the top
 * where the sweep unit silences them anyway. So a real piano's range is not a rounding of the NES's
 * -- it is the same range, which is why the metaphor is worth using at all.
 * <p>
 * <b>One keyboard per voice, or all four on one.</b> They answer different questions and both are
 * worth having, which is why there is a tick rather than a decision. Four keyboards are read like
 * four staves: each part's own shape, its range, and whether it is moving at all. One keyboard is
 * read like a chord -- what the parts are doing <em>to each other</em>, which is where an octave,
 * a third and a semitone of accidental dissonance stop looking alike. Separate is the default for
 * the reason a score is written that way: a part is easier to follow than a texture.
 * <p>
 * <b>The trail is the part that is not a measurement.</b> A readout is taken four times a second
 * and a melody moves faster than that, so a keyboard showing only what is held at the instant of the
 * readout would blink and miss half the tune. Each key therefore keeps a mark for two readouts after
 * the voice has left it -- half a second -- and that mark is deliberately <em>not</em> the same
 * shape as a held note: a held key is filled, and a key that was just played has a bar across its
 * foot. "This is sounding now" and "this was sounding a moment ago" are different answers, and a
 * fading version of one colour would blur them into a guess.
 */
final class Piano extends JComponent {
    /**
     * Which of the twelve semitones are the white keys, counted from C.
     */
    private static final boolean[] WHITE = {
            true, false, true, false, true, true, false, true, false, true, false, true,
    };

    /**
     * How many white keys there are between A0 and C8 inclusive, which is what the width is
     * divided into.
     */
    private static final int WHITE_KEYS = 52;

    /**
     * How wide a white key is drawn before the component is stretched, and how tall the keyboard
     * is. Twelve pixels is about as narrow as a key can be and still take a mark that reads as a
     * mark rather than as a line.
     */
    private static final int KEY_WIDTH = 12;
    private static final int HEIGHT = 54;

    /**
     * A black key against a white one: how much of the width and how much of the height.
     */
    private static final double BLACK_WIDTH = 0.62;
    private static final double BLACK_HEIGHT = 0.62;

    /**
     * How tall the bar at the foot of a key that was played recently is.
     */
    private static final int TRAIL_HEIGHT = 4;

    /**
     * How many readouts a key keeps its trail for: two, which is half a second. Long enough for a
     * melody to leave a line behind it and short enough that nothing on screen is a second out of
     * date.
     */
    private static final int TRAIL_READOUTS = 2;

    /**
     * The voices that can have a note, in the order the rows above have them.
     * <p>
     * The noise is one of them, which is the entry worth explaining. In its usual mode its shift
     * register runs a sequence 32767 steps long and what comes out is hiss with no pitch at all --
     * so its keyboard stays empty, which is itself the answer to whether a sound is a note. In
     * short mode the sequence is 93 steps, which repeats fast enough to be heard as a metallic
     * pitch, and games use it for exactly that. {@link APU.VoiceState#pitch()} is what tells the
     * two apart, and this asks it rather than deciding for itself.
     * <p>
     * The DMC is not here at all and cannot be: its rate is a sample rate, and what pitch a sample
     * comes out at is a fact about the bytes in it rather than about the chip.
     */
    static final APUChannel[] PITCHED = {
            APUChannel.PULSE_1, APUChannel.PULSE_2, APUChannel.TRIANGLE, APUChannel.NOISE,
    };

    /**
     * Which voices this keyboard is drawing: all three, or one of them.
     */
    private final APUChannel[] voices;

    /**
     * Which key each voice is on now, or {@link Notes#NO_KEY}.
     */
    private final Map<APUChannel, Integer> held = new EnumMap<>(APUChannel.class);

    /**
     * How many more readouts each voice's recent keys stay marked for. One counter per key per
     * voice, which is 88 of them and costs nothing to walk.
     */
    private final Map<APUChannel, int[]> trail = new EnumMap<>(APUChannel.class);

    /**
     * @param voices whose notes to draw. One for a keyboard of its own, or all of
     *               {@link #PITCHED} for the one they share.
     */
    Piano(final APUChannel... voices) {
        this.voices = voices;

        for (var channel : voices) {
            held.put(channel, Notes.NO_KEY);
            trail.put(channel, new int[Notes.HIGHEST_KEY - Notes.LOWEST_KEY + 1]);
        }

        setPreferredSize(new Dimension(WHITE_KEYS * KEY_WIDTH, HEIGHT));
        setMinimumSize(getPreferredSize());
    }

    /**
     * The frame that has just finished.
     * <p>
     * Asked of every keyboard rather than only the ones on show, so that the trail behind a note is
     * right the moment somebody switches between one keyboard and three rather than starting empty.
     */
    void show(final Readout readout) {
        for (var channel : voices) {
            var voice = readout.voice(channel);
            var key = voice.playing() ? Notes.keyOf(voice.pitch()) : Notes.NO_KEY;
            var marks = trail.get(channel);

            for (var i = 0; i < marks.length; i++) {
                if (marks[i] > 0) {
                    marks[i]--;
                }
            }

            held.put(channel, key);

            if (key >= Notes.LOWEST_KEY && key <= Notes.HIGHEST_KEY) {
                marks[key - Notes.LOWEST_KEY] = TRAIL_READOUTS;
            }
        }

        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        var width = Math.max(1, getWidth() / WHITE_KEYS);
        var height = getHeight();
        var left = (getWidth() - width * WHITE_KEYS) / 2;

        // The whites first and the blacks over them, which is how a keyboard is built and is what
        // lets a lit white key be filled to the top without a black key having to leave room.
        for (var key = Notes.LOWEST_KEY; key <= Notes.HIGHEST_KEY; key++) {
            if (isWhite(key)) {
                drawKey(g, key, left + whiteIndex(key) * width, width, height, true);
            }
        }

        for (var key = Notes.LOWEST_KEY; key <= Notes.HIGHEST_KEY; key++) {
            if (!isWhite(key)) {
                var black = (int) Math.round(width * BLACK_WIDTH);

                // Centred on the seam between the two white keys it sits between, which for a
                // sharp is the seam after the white key below it.
                drawKey(
                        g,
                        key,
                        left + whiteIndex(key + 1) * width - black / 2,
                        black,
                        (int) Math.round(height * BLACK_HEIGHT),
                        false);
            }
        }
    }

    // ================================================================================== internals

    /**
     * One key, and whatever is on it. A key with two voices on it is split into bands down its
     * length rather than given whichever colour was drawn last, since two parts on the same note is
     * exactly the thing somebody would be looking for.
     */
    private void drawKey(
            final Graphics g,
            final int key,
            final int x,
            final int width,
            final int height,
            final boolean white) {

        var voices = held.entrySet().stream()
                .filter(entry -> entry.getValue() == key)
                .map(Map.Entry::getKey)
                .toList();

        g.setColor(white ? Theme.background() : Theme.foreground());
        g.fillRect(x, 0, width - 1, height);

        for (var i = 0; i < voices.size(); i++) {
            var band = height / voices.size();

            g.setColor(Traces.colourOf(voices.get(i)));
            g.fillRect(
                    x,
                    i * band,
                    width - 1,
                    i == voices.size() - 1 ? height - i * band : band);
        }

        // The trail, at the foot of the key and only where the key is not held: a bar under a fill
        // would be the same voice saying the same thing twice.
        if (voices.isEmpty()) {
            var recent = recentlyOn(key);

            if (recent != null) {
                g.setColor(recent);
                g.fillRect(x, height - TRAIL_HEIGHT, width - 1, TRAIL_HEIGHT);
            }
        }

        g.setColor(Theme.dim());
        g.drawRect(x, 0, width - 1, height - 1);
    }

    /**
     * The colour of whichever voice was last on this key and has not been gone long, or null.
     */
    private Color recentlyOn(final int key) {
        for (var channel : voices) {
            if (trail.get(channel)[key - Notes.LOWEST_KEY] > 0) {
                return Traces.colourOf(channel);
            }
        }

        return null;
    }

    private static boolean isWhite(final int key) {
        return WHITE[Math.floorMod(key, 12)];
    }

    /**
     * How many white keys there are below this one, which is where it sits along the keyboard. A
     * black key answers with the index of the white key above it, which is what puts it on the
     * seam.
     */
    private static int whiteIndex(final int key) {
        var count = 0;

        for (var below = Notes.LOWEST_KEY; below < key; below++) {
            if (isWhite(below)) {
                count++;
            }
        }

        return count;
    }
}
