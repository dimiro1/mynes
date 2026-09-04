package com.github.dimiro1.mynes.ui.ppuviewer;

import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Where the pointer lands, which is the only part of the panel that can be wrong without looking
 * wrong: a swatch drawn one place and answered for from another shows the right colours under the
 * wrong addresses, and the window would be lying about exactly the thing it exists to say.
 * <p>
 * No display needed, and none built: the geometry is the layout constants and nothing else.
 */
class PaletteRAMPanelTests {
    /**
     * Every cell is reachable, and no two of them answer to the same pixel.
     */
    @Test
    void allThirtyTwoCellsCanBePointedAt() {
        var found = new HashSet<Integer>();

        for (var y = 0; y < PaletteRAMPanel.HEIGHT; y++) {
            for (var x = 0; x < PaletteRAMPanel.WIDTH; x++) {
                var cell = PaletteRAMPanel.cellAt(x, y);

                if (cell >= 0) {
                    found.add(cell);
                }
            }
        }

        assertEquals(PaletteCells.CELLS, found.size());
    }

    /**
     * Nothing outside the swatches claims to be a cell -- the margins, the two headings, the gaps
     * between the rows, the gap that sets entry 0 apart, and the run between the two columns.
     */
    @Test
    void theSpaceAroundThemBelongsToNobody() {
        assertEquals(-1, PaletteRAMPanel.cellAt(0, 0));
        assertEquals(-1, PaletteRAMPanel.cellAt(PaletteRAMPanel.WIDTH - 1, 0));
        assertEquals(-1, PaletteRAMPanel.cellAt(-4, 40));
        assertEquals(-1, PaletteRAMPanel.cellAt(40, -4));
        assertEquals(-1, PaletteRAMPanel.cellAt(
                PaletteRAMPanel.WIDTH - 1, PaletteRAMPanel.HEIGHT - 1));
    }

    /**
     * The two halves of the window are the two halves of palette RAM, and the four rows are the
     * four palettes in each. Read off the top left corner of a swatch, found by sweeping rather
     * than by repeating the arithmetic under test.
     */
    @Test
    void theLeftColumnIsBackgroundAndTheRightIsSprite() {
        for (var row = 0; row < 4; row++) {
            assertEquals(row * 4, firstCellOfRow(row, 0));
            assertEquals(0x10 + row * 4, firstCellOfRow(row, 1));
        }
    }

    /**
     * Entry 0 and entry 1 are separated by a gap wide enough to fall into, which is the whole point
     * of the gap: the eye has to be told that the first cell of a palette is a different kind of
     * thing from the other three.
     */
    @Test
    void thereIsDeadSpaceBetweenEntryZeroAndEntryOne() {
        var y = rowOf(0);
        var start = xOf(0x00, y);
        var next = xOf(0x01, y);

        assertNotEquals(-1, start);
        assertNotEquals(-1, next);

        for (var x = start + 1; x < next; x++) {
            if (PaletteRAMPanel.cellAt(x, y) == -1) {
                return;
            }
        }

        throw new AssertionError("entry 0 runs straight into entry 1");
    }

    private static int firstCellOfRow(final int row, final int column) {
        var y = rowOf(row);
        var half = column == 0 ? 0 : PaletteRAMPanel.WIDTH / 2;

        for (var x = half; x < PaletteRAMPanel.WIDTH; x++) {
            var cell = PaletteRAMPanel.cellAt(x, y);

            if (cell >= 0) {
                return cell;
            }
        }

        throw new AssertionError("no cell on row " + row + " of column " + column);
    }

    /**
     * A y that is inside the swatches of a row, found rather than computed.
     */
    private static int rowOf(final int row) {
        var seen = -1;
        var previous = -1;

        for (var y = 0; y < PaletteRAMPanel.HEIGHT; y++) {
            var cell = PaletteRAMPanel.cellAt(PaletteRAMPanel.WIDTH / 4, y);

            if (cell >= 0 && cell != previous) {
                seen++;
                previous = cell;

                if (seen == row) {
                    return y + 2;
                }
            }
        }

        throw new AssertionError("no row " + row);
    }

    private static int xOf(final int cell, final int y) {
        for (var x = 0; x < PaletteRAMPanel.WIDTH; x++) {
            if (PaletteRAMPanel.cellAt(x, y) == cell) {
                return x;
            }
        }

        return -1;
    }
}
