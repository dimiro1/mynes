package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * The screen beside the palettes, with the light taken off everything the chosen palette is not
 * drawing.
 * <p>
 * A palette viewer without this can say a cell holds $16 and cannot say the one thing a wrong
 * colour actually raises, which is <em>which of the eight</em> put it there. Two palettes holding
 * the same byte are the same pixel in the picture, so the answer has to be rebuilt out of the
 * attribute bytes and out of OAM rather than read off the frame -- see {@link PaletteUse}.
 * <p>
 * Laid out with the same margin and heading as {@link PaletteRAMPanel} so the two headings sit on
 * one line, and drawn at one to one: an attribute quadrant is sixteen pixels across, which is large
 * enough to see without magnifying and small enough to keep the window a sensible width.
 * <p>
 * All 240 lines, with the sixteen a television hid shaded rather than cropped away, for the reason
 * {@link Screen} gives.
 */
final class PaletteUsePanel extends JComponent {
    private static final int MARGIN = 12;
    private static final int HEADING = 20;

    static final int WIDTH = MARGIN * 2 + PPU.SCREEN_WIDTH;
    static final int HEIGHT = MARGIN * 2 + HEADING + PPU.SCREEN_HEIGHT;

    private static final Color EDGE = new Color(0, 0, 0, 90);

    private final PPU ppu;

    private final BufferedImage image = new BufferedImage(
            PPU.SCREEN_WIDTH, PPU.SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);

    private final int[] pixels =
            ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

    private final boolean[] lit = new boolean[PaletteUse.PIXELS];

    /**
     * The 512 entry table the framebuffer indexes, taken once per palette change rather than per
     * frame: {@code NESPalette.colours()} hands out a copy.
     */
    private int[] colours;

    private int cell = -1;

    PaletteUsePanel(final PPU ppu, final NESPalette palette) {
        this.ppu = ppu;
        this.colours = palette.colours();

        setPreferredSize(new Dimension(WIDTH, HEIGHT));
    }

    void setPalette(final NESPalette palette) {
        this.colours = palette.colours();
        refresh();
    }

    /**
     * Which cell of palette RAM is being asked about, or -1 for none, in which case the picture is
     * drawn plainly. The whole palette the cell belongs to is what gets lit: the question "where is
     * this colour on the screen" cannot be answered from a frame of indices, and the question that
     * can be answered is the more useful one anyway.
     */
    void setCell(final int index) {
        if (index != cell) {
            cell = index;
            refresh();
        }
    }

    /**
     * Reads the frame, and the machine behind it, again.
     */
    void refresh() {
        if (cell < 0) {
            Screen.draw(pixels, ppu, colours);
        } else {
            if (PaletteCells.isSprite(cell)) {
                PaletteUse.sprite(lit, ppu, PaletteCells.numberOf(cell));
            } else {
                PaletteUse.background(lit, ppu, PaletteCells.numberOf(cell));
            }

            Screen.drawDimmed(pixels, ppu, colours, lit);
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
            g2.drawString(heading(), MARGIN, MARGIN + HEADING - 6);

            g2.drawImage(image, MARGIN, MARGIN + HEADING, null);

            Screen.paintOverscan(g2, MARGIN, MARGIN + HEADING, 1);

            g2.setColor(EDGE);
            g2.drawRect(
                    MARGIN, MARGIN + HEADING, PPU.SCREEN_WIDTH - 1, PPU.SCREEN_HEIGHT - 1);
        } finally {
            g2.dispose();
        }
    }

    private String heading() {
        return cell < 0
                ? "The screen"
                : "Where " + PaletteCells.paletteNameOf(cell) + " is used";
    }
}
