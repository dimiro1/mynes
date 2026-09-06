package com.github.dimiro1.mynes.ui.sound;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A frequency named the way a musician would name it.
 * <p>
 * The numbers below are the standard ones rather than anything the NES produces, which is the
 * point: the chip cannot hit most of them, so what makes this worth having is that it says which
 * note is nearest and by how much it missed.
 */
class NotesTests {
    /**
     * The NTSC processor's clock, which is what turns a period into a frequency.
     */
    private static final double NTSC = 1789772.7272;

    /**
     * The keyboard is drawn from these, so what matters is that the numbering is the standard one
     * -- a keyboard laid out against a different origin would put every note in the wrong place and
     * still look like a keyboard.
     */
    @Test
    void theKeyNumbersAreTheOnesAPianoUses() {
        assertEquals(69, Notes.keyOf(440), "A4 is 69, as MIDI has it");
        assertEquals(60, Notes.keyOf(261.63), "middle C is 60");
        assertEquals(Notes.LOWEST_KEY, Notes.keyOf(27.5), "A0 is the bottom of a piano");
        assertEquals(Notes.HIGHEST_KEY, Notes.keyOf(4186.01), "and C8 is the top");
    }

    /**
     * The two ends of what the chip can play, which is why a piano's eighty-eight is the right
     * keyboard to draw rather than a rounding of one.
     */
    @Test
    void theChipsLowestAndHighestNotesAreOnAPiano() {
        var triangle = NTSC / (32 * (2047 + 1.0));

        assertEquals(Notes.LOWEST_KEY, Notes.keyOf(triangle),
                "the triangle at its longest period is A0, at " + triangle + "Hz");

        assertTrue(Notes.keyOf(triangle) >= Notes.LOWEST_KEY);
    }

    @Test
    void aFrequencyWithNoNoteHasNoKeyEither() {
        assertEquals(Notes.NO_KEY, Notes.keyOf(0), "a stopped channel");
        assertEquals(Notes.NO_KEY, Notes.keyOf(12), "below anything anybody hears as a pitch");
        assertEquals(Notes.NO_KEY, Notes.keyOf(12429), "a pulse at its shortest period");
        assertNull(Notes.nameOf(12429), "and the two agree about it");
    }

    @Test
    void concertAIsA4() {
        assertEquals("A4", Notes.nameOf(440));
        assertEquals(0, Notes.centsOff(440));
    }

    @Test
    void theOctaveNumberTurnsOverAtC() {
        assertEquals("B3", Notes.nameOf(246.94));
        assertEquals("C4", Notes.nameOf(261.63));
        assertEquals("B4", Notes.nameOf(493.88));
        assertEquals("C5", Notes.nameOf(523.25));
    }

    @Test
    void everySemitoneOfAnOctaveIsNamed() {
        var names = new String[]{
                "C4", "C#4", "D4", "D#4", "E4", "F4", "F#4", "G4", "G#4", "A4", "A#4", "B4",
        };

        for (var semitone = 0; semitone < names.length; semitone++) {
            var hertz = 261.6256 * Math.pow(2, semitone / 12.0);

            assertEquals(names[semitone], Notes.nameOf(hertz), Double.toString(hertz));
        }
    }

    /**
     * The half of it that is worth more than the name. A pulse's period is an integer, so most
     * notes come out a little sharp or flat -- and a bass line that drifts as it descends is a real
     * thing a period table does.
     */
    @Test
    void howFarOffItIsComesBackInCents() {
        // Nearly a quarter tone either side of A4, which is nearly as far from a note as anything
        // gets. Exactly a quarter tone is the one input with two right answers, so it is left out.
        assertEquals(40, Notes.centsOff(440 * Math.pow(2, 0.4 / 12)), 1);
        assertEquals(-40, Notes.centsOff(440 * Math.pow(2, -0.4 / 12)), 1);

        // What the chip actually plays when a game asks for A440: period 253 on an NTSC machine.
        assertEquals("A4", Notes.nameOf(NTSC / (16 * 254)));
        assertEquals(2, Notes.centsOff(NTSC / (16 * 254)), 1);
    }

    /**
     * Below and above the range anybody hears as a pitch there is no note to name, and naming one
     * would be worse than saying nothing.
     */
    @Test
    void thereIsNoNoteWhereThereIsNoPitch() {
        assertNull(Notes.nameOf(0));
        assertNull(Notes.nameOf(8));
        assertNull(Notes.nameOf(20_000));
    }
}
