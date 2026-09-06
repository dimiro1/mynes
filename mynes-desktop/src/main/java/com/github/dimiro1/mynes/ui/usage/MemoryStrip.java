package com.github.dimiro1.mynes.ui.usage;

import com.github.dimiro1.mynes.debug.Usage;
import com.github.dimiro1.mynes.ui.debugger.Theme;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * One area of memory drawn across its own address range: a bar that is also a map.
 * <p>
 * A percentage would say how much of the area is in use and nothing about <em>where</em>, which is
 * most of what anybody wants from this. A game that keeps a sprite buffer shows it as a solid block
 * of 256 bytes at the left of the work RAM's strip; a stack that has gone deep shows as a strip
 * filled from the right, because the stack fills downwards; a decompression buffer shows as a hole
 * that fills in and empties again. None of that survives being averaged into a number.
 * <p>
 * <b>Two readings stacked in one column, because one contains the other.</b> The whole column is
 * how many of that cell's bytes have ever been written, and the coloured part at its foot is how
 * many of them the program has written in the last quarter of a second -- so a full grey column with
 * a sliver of colour is memory the game set up once and has stopped touching, and a column that is
 * coloured all the way up is where it is working now. Every byte written recently was written at
 * some point, so the second is a subset of the first by construction and stacking them is what they
 * are. Deliberately not the arrangement the sound keyboards use for their trail: there the two
 * readings are <em>alternatives</em> and are given two shapes so they cannot be read as degrees of
 * one thing.
 * <p>
 * <b>It takes whatever width it is given</b> and divides it by the 128 cells rather than drawing
 * them at a fixed size, because the wider it is drawn the finer the map is to read -- so a window
 * somebody has made large should spend the room on it.
 */
final class MemoryStrip extends JComponent {
    private static final int HEIGHT = 30;

    /**
     * How wide it asks to be when nothing is stretching it: two pixels a cell, which is 256 -- the
     * width of the picture the console draws.
     */
    private static final int WIDTH = Usage.CELLS * 2;


    private int[] ever = new int[0];
    private int[] live = new int[0];
    private int perCell = 1;

    MemoryStrip() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setMinimumSize(new Dimension(Usage.CELLS, HEIGHT));
    }

    /**
     * The area as it stands, or nothing at all when nobody has been measuring.
     */
    void show(final Usage.Segment segment) {
        if (segment == null) {
            ever = new int[0];
            live = new int[0];
        } else {
            ever = segment.everMap();
            live = segment.liveMap();
            perCell = segment.area().perCell();
        }

        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        var height = getHeight();
        var width = getWidth();

        // The whole range in the colour a listing's background is, which on every theme the program
        // ships is lighter than the panel behind it -- so an area nothing has been written to is
        // visibly an empty strip rather than a gap in the layout, and a column that has been
        // written to has the contrast of ink on paper rather than of one grey on another.
        g.setColor(Theme.background());
        g.fillRect(0, 0, width, height);

        for (var cell = 0; cell < ever.length; cell++) {
            // Both edges worked out from the cell rather than a width worked out once, so that the
            // rounding is shared between neighbours and no column of the background shows through
            // where a division did not come out whole.
            var left = cell * width / Usage.CELLS;
            var right = (cell + 1) * width / Usage.CELLS;

            if (right == left) {
                continue;
            }

            var used = tall(ever[cell], height);
            var now = tall(live[cell], height);

            if (used > 0) {
                g.setColor(Theme.muted());
                g.fillRect(left, height - used, right - left, used);
            }

            // Over the top of it and from the same floor, since a byte written recently was
            // written at all: what shows above the colour is the rest of what the game has ever
            // used, which is the comparison the two are here to make.
            if (now > 0) {
                g.setColor(Theme.running());
                g.fillRect(left, height - now, right - left, now);
            }
        }

        // Last, so that an area which is entirely used still has an edge to it.
        g.setColor(Theme.dim());
        g.drawRect(0, 0, width - 1, height - 1);
    }

    /**
     * How tall a column of {@code bytes} out of a cell is. At least a pixel where anything at all
     * was written, since a cell of twelve bytes with one byte in it is a byte somebody is looking
     * for and would otherwise round away to nothing.
     */
    private int tall(final int bytes, final int height) {
        return bytes == 0 ? 0 : Math.max(1, (int) Math.round((double) bytes / perCell * height));
    }
}
