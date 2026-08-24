package com.github.dimiro1.mynes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The arithmetic between what a player asks for and what the chip is told.
 * <p>
 * A percentage is the useful unit at one end and a scanline count at the other, and the conversion
 * goes through the region -- so the same setting is a different number of lines on the two machines
 * and has to be, because half of 262 lines is not half of 312.
 */
class OverclockTests {

    @Test
    void noneIsNoLinesAtAll() {
        assertEquals(0, Overclock.NONE.beforeNmi());
        assertEquals(0, Overclock.NONE.afterNmi());
        assertTrue(Overclock.NONE.isNone());

        assertTrue(new Overclock(0, 0).isNone(), "a count of nothing is nothing");
        assertFalse(new Overclock(0, 1).isNone(), "and a line after the NMI is still a line");
    }

    @Test
    void aPresetIsThatManyPercentOfTheRegionsScanlines() {
        assertEquals(new Overclock(131, 0), Overclock.percentOf(Region.NTSC, 50));
        assertEquals(new Overclock(156, 0), Overclock.percentOf(Region.PAL, 50));

        assertEquals(new Overclock(524, 0), Overclock.percentOf(Region.NTSC, 200));
        assertEquals(new Overclock(624, 0), Overclock.percentOf(Region.PAL, 200));
    }

    @Test
    void aPresetPutsEveryLineBeforeTheNmi() {
        // The half that changes nothing a game can observe except that the frame is longer. Lines
        // after the NMI move the picture relative to it, which is what a mid-screen split measures.
        assertEquals(0, Overclock.percentOf(Region.NTSC, 100).afterNmi());
    }

    @Test
    void aCountOutsideNoughtToAThousandIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Overclock(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> new Overclock(0, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new Overclock(Overclock.MAX_SCANLINES + 1, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new Overclock(0, Overclock.MAX_SCANLINES + 1));

        var refused = assertThrows(
                IllegalArgumentException.class, () -> new Overclock(0, 2000));

        assertTrue(refused.getMessage().contains("0 to " + Overclock.MAX_SCANLINES),
                "the message has to say what the range is: " + refused.getMessage());
        assertTrue(refused.getMessage().contains("after"),
                "and which of the two numbers was wrong: " + refused.getMessage());
    }

    @Test
    void theLimitItselfIsAllowed() {
        assertEquals(
                Overclock.MAX_SCANLINES,
                new Overclock(Overclock.MAX_SCANLINES, Overclock.MAX_SCANLINES).beforeNmi());
    }
}
