package com.github.dimiro1.mynes.ui.sound;

/**
 * A frequency as a musician would name it.
 * <p>
 * Worth having because a period is not a pitch and a pitch is not a note. A game writes eleven bits
 * to $4002 and $4003; what comes out is a frequency that depends on which console it is running on;
 * and what somebody is trying to hear is whether the melody is in the right key. Two of those three
 * steps are arithmetic nobody should do in their head while watching a game.
 * <p>
 * Equal temperament with A4 at 440Hz, which is what the music these chips play was written against
 * -- the NES cannot hit most of the notes exactly, since its periods are integers, so what this
 * names is the nearest one. How far off it is comes back in cents: a game's bass line drifting flat
 * as it goes down the scale is a real thing a period table does, and it is invisible without a
 * number.
 */
public final class Notes {
    private static final String[] NAMES = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B",
    };

    /**
     * Concert A, and the note everything else is counted from.
     */
    private static final double A4 = 440;

    /**
     * How many semitones A4 is above the C of its octave, which is what turns a count from A into a
     * name and an octave number.
     */
    private static final int A_ABOVE_C = 9;

    private static final int SEMITONES = 12;

    /**
     * Which key of a piano A4 is, numbered the way MIDI numbers them. The eighty-eight of a full
     * keyboard run from {@link #LOWEST_KEY} to {@link #HIGHEST_KEY}.
     */
    private static final int A4_KEY = 69;

    /**
     * A0 and C8, the ends of a piano -- and, as it happens, of what the chip can play as a note.
     * The triangle at its longest period is 27.3Hz, which is A0 to within a few cents; the pulses
     * at their shortest are up past 12kHz, where the sweep unit silences them anyway.
     */
    public static final int LOWEST_KEY = 21;
    public static final int HIGHEST_KEY = 108;

    /**
     * What {@link #keyOf} answers where there is no note to point at.
     */
    public static final int NO_KEY = -1;

    /**
     * The range worth naming: below this a period is a rumble nobody hears as a pitch, and above it
     * the pulse channels are into the range the sweep unit mutes anyway.
     */
    private static final double LOWEST = 16;
    private static final double HIGHEST = 8000;

    private Notes() {
    }

    /**
     * The nearest note to {@code hertz}, or {@code null} where there is no pitch to name -- a
     * stopped channel, or a period too high or low to hear as one.
     */
    public static String nameOf(final double hertz) {
        if (hertz < LOWEST || hertz > HIGHEST) {
            return null;
        }

        var semitones = (int) Math.round(semitonesAboveA4(hertz));
        var index = Math.floorMod(semitones + A_ABOVE_C, SEMITONES);
        var octave = 4 + Math.floorDiv(semitones + A_ABOVE_C, SEMITONES);

        return NAMES[index] + octave;
    }

    /**
     * Which key of a piano the nearest note is, numbered as MIDI numbers them: A0 is 21, middle C
     * is 60 and C8 is 108.
     * <p>
     * A number rather than a name because a keyboard is drawn from positions rather than read from
     * text, and because two voices on the same note have to come out as the same number for the
     * drawing to say so.
     *
     * @return the key, or {@link #NO_KEY} where there is no pitch to point at. It can land outside
     *         a piano's eighty-eight at either end -- {@link #LOWEST} is below A0 -- so whatever
     *         draws one has to decide what to do with a note that is off the end of it.
     */
    public static int keyOf(final double hertz) {
        if (hertz < LOWEST || hertz > HIGHEST) {
            return NO_KEY;
        }

        return (int) Math.round(semitonesAboveA4(hertz)) + A4_KEY;
    }

    /**
     * How far {@code hertz} is from the note {@link #nameOf} gives it, in cents -- a hundredth of a
     * semitone each, so anything past about twenty is audibly out.
     */
    public static int centsOff(final double hertz) {
        var semitones = semitonesAboveA4(hertz);

        return (int) Math.round((semitones - Math.round(semitones)) * 100);
    }

    private static double semitonesAboveA4(final double hertz) {
        return SEMITONES * Math.log(hertz / A4) / Math.log(2);
    }
}
