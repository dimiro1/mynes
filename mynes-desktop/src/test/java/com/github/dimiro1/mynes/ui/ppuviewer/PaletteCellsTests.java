package com.github.dimiro1.mynes.ui.ppuviewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The part of the palette viewer that is arithmetic rather than pixels, and so the part a machine
 * with no display can still be held to. Everything here is a claim about the hardware; the window
 * around it is a claim about taste.
 */
class PaletteCellsTests {
    @Test
    void everyCellIsSomewhereInThePaletteAddressSpace() {
        for (var index = 0; index < PaletteCells.CELLS; index++) {
            assertEquals(0x3F00 + index, PaletteCells.addressOf(index));
        }
    }

    /**
     * The four cells that are not memory. $3F10 is $3F00, which is why a game that writes the first
     * colour of a sprite palette has written the screen's background.
     */
    @Test
    void theFirstEntryOfEachSpritePaletteIsTheBackgroundPalettesOwn() {
        assertEquals(0x00, PaletteCells.mirrorOf(0x10));
        assertEquals(0x04, PaletteCells.mirrorOf(0x14));
        assertEquals(0x08, PaletteCells.mirrorOf(0x18));
        assertEquals(0x0C, PaletteCells.mirrorOf(0x1C));
    }

    @Test
    void theOtherTwentyEightCellsAreTheirOwnMemory() {
        for (var index = 0; index < PaletteCells.CELLS; index++) {
            if (index != 0x10 && index != 0x14 && index != 0x18 && index != 0x1C) {
                assertEquals(-1, PaletteCells.mirrorOf(index), "cell " + index);
            }
        }
    }

    @Test
    void aCellIsNamedByItsPaletteAndItsPositionInIt() {
        assertEquals("BG 0 entry 0", PaletteCells.nameOf(0x00));
        assertEquals("BG 3 entry 1", PaletteCells.nameOf(0x0D));
        assertEquals("SP 0 entry 0", PaletteCells.nameOf(0x10));
        assertEquals("SP 1 entry 0", PaletteCells.nameOf(0x14));
        assertEquals("SP 3 entry 3", PaletteCells.nameOf(0x1F));
    }

    /**
     * The three cells that hold what a game put there and never reach the screen, because a
     * transparent background pixel is drawn with $3F00 whichever palette its tile was using.
     */
    @Test
    void theThreeUndrawnBackgroundCellsSaySoAndTheBackdropDoesNot() {
        for (var index : new int[]{0x04, 0x08, 0x0C}) {
            assertTrue(
                    PaletteCells.noteOf(index).startsWith("not drawn"),
                    "cell " + index + " said " + PaletteCells.noteOf(index));
        }

        assertEquals("the backdrop", PaletteCells.noteOf(0x00));
    }

    @Test
    void aMirrorNamesTheCellItReallyIs() {
        assertEquals("mirror of $3F00", PaletteCells.noteOf(0x10));
        assertEquals("mirror of $3F0C", PaletteCells.noteOf(0x1C));
    }

    /**
     * Which of the eight a cell belongs to, which is what the overlay beside the swatches is asked
     * for: the question "where is this colour" cannot be answered off a frame of indices, and the
     * question "where is this palette" can.
     */
    @Test
    void aCellKnowsWhichPaletteItBelongsTo() {
        assertEquals("BG 0", PaletteCells.paletteNameOf(0x00));
        assertEquals("BG 3", PaletteCells.paletteNameOf(0x0F));
        assertEquals("SP 0", PaletteCells.paletteNameOf(0x10));
        assertEquals("SP 3", PaletteCells.paletteNameOf(0x1F));

        for (var index = 0; index < PaletteCells.CELLS; index++) {
            assertEquals(index >= 0x10, PaletteCells.isSprite(index), "cell " + index);
            assertEquals((index >> 2) & 3, PaletteCells.numberOf(index), "cell " + index);
        }
    }

    @Test
    void anOrdinaryCellHasNothingToAdd() {
        assertEquals("", PaletteCells.noteOf(0x01));
        assertEquals("", PaletteCells.noteOf(0x0F));
        assertEquals("", PaletteCells.noteOf(0x13));
        assertEquals("", PaletteCells.noteOf(0x1F));
    }
}
