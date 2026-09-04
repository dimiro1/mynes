package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The eight palettes as they stand, four background and four sprite, with the byte drawn on top of
 * the colour it names.
 * <p>
 * Both at once rather than one or the other, for the reason the OAM viewer shows both: the colour
 * is what the question was about and the byte is what a watchpoint catches, and converting between
 * them by hand is the thing this window exists to stop.
 * <p>
 * <b>Entry 0 of every palette is set apart from the other three</b>, because all eight of those
 * cells are special and none of the other twenty four are: one is the backdrop, three are memory
 * the chip never draws, and four are not memory at all. Laid out background beside sprite so that
 * each mirrored pair -- $3F10 and $3F00, $3F14 and $3F04 -- sits on one row, where two swatches
 * that always agree look like the one cell they are.
 * <p>
 * The swatches are the <em>bytes</em>: $2001's greyscale bit and its three emphasis bits are not
 * applied, even though the chip applies both on the way out. That is the whole distinction the
 * window is for -- a screen in the wrong colours is either a byte a game wrote or a bit in $2001,
 * and a viewer that quietly folded the second into the first could not tell them apart. The frame's
 * header says what $2001 is doing instead.
 * <p>
 * Everything here runs on the event dispatch thread and reads palette RAM while the emulation
 * thread runs. Deliberately unsynchronised, exactly as the other viewers are: reading an array
 * element cannot tear, so the worst case is a colour a quarter of a second out of date.
 */
final class PaletteRAMPanel extends JComponent {
    private static final int SWATCH = 40;

    /**
     * The gap that sets entry 0 apart from entries 1 to 3.
     */
    private static final int ENTRY_GAP = 8;

    private static final int ROW_GAP = 6;
    private static final int COLUMN_GAP = 28;

    /**
     * Room for the palette number down the left of each column, and for the two headings above
     * them.
     */
    private static final int LABEL = 24;
    private static final int HEADING = 20;

    private static final int MARGIN = 12;

    private static final int GROUP = SWATCH * 4 + ENTRY_GAP;
    private static final int COLUMN = LABEL + GROUP;

    static final int WIDTH = MARGIN * 2 + COLUMN * 2 + COLUMN_GAP;
    static final int HEIGHT = MARGIN * 2 + HEADING + (SWATCH + ROW_GAP) * 4 - ROW_GAP;

    /**
     * Where the text drawn on a swatch tips from black to white. A palette entry is a colour off a
     * television rather than one somebody chose to be readable, so the two that need dark text --
     * $30 and the greys around it -- have to be found rather than assumed.
     */
    private static final double DARK_TEXT_ABOVE = 0.55;

    private static final Color EDGE = new Color(0, 0, 0, 90);
    private static final Color HOVER = new Color(1.0f, 0.25f, 0.25f, 0.9f);

    private final PPU ppu;
    private final int[] values = new int[PaletteCells.CELLS];

    private NESPalette palette;
    private int hovered = -1;

    PaletteRAMPanel(final PPU ppu, final NESPalette palette) {
        this.ppu = ppu;
        this.palette = palette;

        setPreferredSize(new Dimension(WIDTH, HEIGHT));
    }

    void setPalette(final NESPalette palette) {
        this.palette = palette;
        repaint();
    }

    /**
     * The byte in a cell as of the last sweep, so the frame's header and its pointer line describe
     * the same moment the swatches do rather than a fresher one.
     */
    int valueAt(final int index) {
        return values[index];
    }

    void setHovered(final int index) {
        if (index != hovered) {
            hovered = index;
            repaint();
        }
    }

    /**
     * Which cell the pointer is on, or -1 for the gaps between them and the margins around them.
     * <p>
     * Static because it is the layout above and nothing else: no instance of this is needed to ask
     * where a cell would be, which is what lets the arithmetic be tested where there is no display
     * to build a window on.
     */
    static int cellAt(final int x, final int y) {
        var down = y - MARGIN - HEADING;
        var across = x - MARGIN;

        // Both offsets are checked for being negative before they are divided, because integer
        // division truncates towards zero: a point four pixels above the first row would otherwise
        // come out as row 0 rather than as no row at all.
        if (down < 0 || across < 0) {
            return -1;
        }

        var row = down / (SWATCH + ROW_GAP);

        if (row > 3 || down % (SWATCH + ROW_GAP) >= SWATCH) {
            return -1;
        }

        var column = across / (COLUMN + COLUMN_GAP);

        if (column > 1) {
            return -1;
        }

        var within = across - column * (COLUMN + COLUMN_GAP) - LABEL;

        if (within < 0) {
            return -1;
        }

        // Entry 0 sits alone and entries 1 to 3 sit past the gap, so the two have to be asked
        // about separately rather than divided out of one run of swatches.
        if (within < SWATCH) {
            return column * 0x10 + row * 4;
        }

        var entry = (within - ENTRY_GAP) / SWATCH;

        return within < SWATCH + ENTRY_GAP || entry > 3
                ? -1
                : column * 0x10 + row * 4 + entry;
    }

    /**
     * Reads all thirty two cells again and redraws. Unconditional, like the nametable viewer's
     * sweep: thirty two reads is cheaper than deciding whether they were worth doing.
     * <p>
     * {@link PPU#peekPalette} folds the mirrors, so the four sprite entry 0 cells come back holding
     * whatever the background cell they alias holds, with nothing here having to know they do.
     */
    void refresh() {
        for (var i = 0; i < values.length; i++) {
            values[i] = ppu.peekPalette(i);
        }

        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);

        var g2 = (Graphics2D) g.create();

        try {
            g2.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            g2.setColor(getForeground());
            g2.drawString("Background", MARGIN + LABEL, MARGIN + HEADING - 6);
            g2.drawString("Sprite", MARGIN + COLUMN + COLUMN_GAP + LABEL, MARGIN + HEADING - 6);

            var hex = new Font(Font.MONOSPACED, Font.PLAIN, 11);

            for (var column = 0; column < 2; column++) {
                for (var row = 0; row < 4; row++) {
                    var y = MARGIN + HEADING + row * (SWATCH + ROW_GAP);

                    g2.setFont(getFont());
                    g2.setColor(getForeground());
                    g2.drawString(
                            Integer.toString(row),
                            MARGIN + column * (COLUMN + COLUMN_GAP) + 8,
                            y + SWATCH / 2 + 5);

                    g2.setFont(hex);

                    for (var entry = 0; entry < 4; entry++) {
                        paintSwatch(g2, column * 0x10 + row * 4 + entry, column, row, entry, y);
                    }
                }
            }
        } finally {
            g2.dispose();
        }
    }

    private void paintSwatch(
            final Graphics2D g2,
            final int index,
            final int column,
            final int row,
            final int entry,
            final int y) {

        var x = MARGIN + column * (COLUMN + COLUMN_GAP) + LABEL
                + entry * SWATCH + (entry == 0 ? 0 : ENTRY_GAP);
        var value = values[index];
        var colour = new Color(palette.colour(value));

        g2.setColor(colour);
        g2.fillRect(x, y, SWATCH, SWATCH);

        g2.setColor(EDGE);
        g2.drawRect(x, y, SWATCH - 1, SWATCH - 1);

        var text = String.format("$%02X", value);
        var width = g2.getFontMetrics().stringWidth(text);

        g2.setColor(luminanceOf(colour) > DARK_TEXT_ABOVE ? Color.BLACK : Color.WHITE);
        g2.drawString(text, x + (SWATCH - width) / 2, y + SWATCH / 2 + 4);

        if (index == hovered) {
            g2.setColor(HOVER);
            g2.drawRect(x + 1, y + 1, SWATCH - 3, SWATCH - 3);
        }
    }

    /**
     * How bright a colour looks rather than how bright it is: the eye takes most of its light from
     * green and almost none from blue, so the plain average would put black text on $12 and white
     * text on $2A.
     */
    private static double luminanceOf(final Color colour) {
        return (0.299 * colour.getRed()
                + 0.587 * colour.getGreen()
                + 0.114 * colour.getBlue()) / 255.0;
    }
}
