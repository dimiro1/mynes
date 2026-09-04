package com.github.dimiro1.mynes.ui.ppuviewer;

/**
 * What each of the thirty two cells of palette RAM is, said in words.
 * <p>
 * Kept apart from the window that draws them, and free of Swing, because the interesting part of a
 * palette RAM viewer is not the swatch -- it is knowing that four of the cells are not memory at
 * all and that three more are memory nothing ever draws. That is arithmetic, it is easy to get
 * subtly wrong, and it is invisible once it is wrong, so it is worth being able to test on a
 * machine with no display.
 */
final class PaletteCells {
    /**
     * Thirty two bytes back the whole of $3F00-$3FFF.
     */
    static final int CELLS = 32;

    /**
     * How a cell is spelled in a debugger, which is the address a watchpoint would go on.
     */
    static int addressOf(final int index) {
        return 0x3F00 | index;
    }

    /**
     * Which cell this one actually is, when it is not itself.
     * <p>
     * The first entry of each sprite palette is the same cell as the first entry of the matching
     * background palette -- $3F10 is $3F00, $3F14 is $3F04, and so on -- which is why writing
     * $3F10 changes the screen's background colour. Folded exactly as
     * {@code PPU.paletteIndex} folds it.
     *
     * @return the cell it aliases, or -1 when the cell is its own memory.
     */
    static int mirrorOf(final int index) {
        return (index & 0x13) == 0x10 ? index & 0x0F : -1;
    }

    /**
     * Which of the eight palettes a cell belongs to: the first sixteen cells are the background's
     * four and the rest are the sprites'.
     */
    static String paletteNameOf(final int index) {
        return String.format("%s %d", index < 0x10 ? "BG" : "SP", (index >> 2) & 3);
    }

    /**
     * Whether a cell belongs to one of the four sprite palettes, which is the difference between
     * the two ways of asking where it is being used.
     */
    static boolean isSprite(final int index) {
        return index >= 0x10;
    }

    /**
     * Which of the four palettes of its half a cell belongs to, 0 to 3.
     */
    static int numberOf(final int index) {
        return (index >> 2) & 3;
    }

    /**
     * Which palette a cell belongs to and where in it, which is how a game thinks about it: "the
     * third colour of sprite palette 1", not "$3F17".
     */
    static String nameOf(final int index) {
        return String.format("%s entry %d", paletteNameOf(index), index & 3);
    }

    /**
     * The thing about this cell that the colour in it does not say. Empty for the twenty four
     * ordinary ones.
     */
    static String noteOf(final int index) {
        var mirror = mirrorOf(index);

        if (mirror >= 0) {
            return String.format("mirror of $%04X", addressOf(mirror));
        }

        if (index == 0) {
            return "the backdrop";
        }

        // $3F04, $3F08 and $3F0C: real, writable, distinct memory that the chip never puts on the
        // screen, because a transparent background pixel is drawn with $3F00 whichever palette the
        // tile was using. A game is free to keep something else in them, and some do.
        if ((index & 3) == 0) {
            return "not drawn; the backdrop shows here instead";
        }

        return "";
    }

    private PaletteCells() {
    }
}
