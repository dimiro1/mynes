package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * All four nametables at once, with the window the PPU is showing drawn over them.
 * <p>
 * The point of seeing all four rather than the one on screen: a scrolling game writes the column
 * about to come into view into the nametable that is currently off screen, and every bug in that
 * -- a column written one tile late, an attribute byte written to the wrong quadrant, a status bar
 * scrolled away with the level -- is invisible in the picture until it arrives, and obvious here.
 * <p>
 * The rectangle is drawn from {@code t} rather than from {@code v}: during rendering {@code v} is a
 * counter the PPU is walking across the screen, so a window polled at some arbitrary dot would show
 * a box wherever the beam happened to be. {@code t} is what the game last wrote, which is the scroll
 * it means -- and for a game that splits the screen mid-frame, it is whichever half of the split has
 * been written by the time the timer looks.
 * <p>
 * Everything here runs on the event dispatch thread and reads emulator memory while the emulation
 * thread runs. Deliberately unsynchronised, exactly as the CHR viewer is: reading an array element
 * cannot tear, so the worst case is a tile a quarter of a second out of date.
 */
final class NametablePanel extends JComponent {
    static final int TILE = 8;
    static final int COLUMNS = 32;
    static final int ROWS = 30;

    /**
     * One screen, and then the four of them in a two by two block the way the address space lays
     * them out: $2000 top left, $2400 top right, $2800 bottom left, $2C00 bottom right.
     */
    static final int SCREEN_WIDTH = COLUMNS * TILE;
    static final int SCREEN_HEIGHT = ROWS * TILE;
    static final int WIDTH = SCREEN_WIDTH * 2;
    static final int HEIGHT = SCREEN_HEIGHT * 2;

    /**
     * Where a nametable's 64 attribute bytes start, after its 960 name bytes.
     */
    private static final int ATTRIBUTES = 0x3C0;

    private static final Color GRID = new Color(1.0f, 1.0f, 1.0f, 0.10f);
    private static final Color SCROLL = new Color(1.0f, 0.25f, 0.25f, 0.9f);

    private final PPU ppu;

    /**
     * Drawn into directly rather than through {@code setRGB}, which is a call per pixel and there
     * are a quarter of a million of them four times a second.
     */
    private final BufferedImage image =
            new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);

    private final int[] pixels =
            ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

    private NESPalette palette;
    private boolean gridVisible;
    private boolean scrollVisible = true;

    NametablePanel(final PPU ppu, final NESPalette palette) {
        this.ppu = ppu;
        this.palette = palette;

        setPreferredSize(new Dimension(WIDTH, HEIGHT));
    }

    void setPalette(final NESPalette palette) {
        this.palette = palette;
        refresh();
    }

    void setGridVisible(final boolean visible) {
        gridVisible = visible;
        repaint();
    }

    void setScrollVisible(final boolean visible) {
        scrollVisible = visible;
        repaint();
    }

    /**
     * Reads all four nametables again and redraws. Unconditional, unlike the CHR viewer's per-tile
     * compare: a nametable is what a game rewrites constantly, so almost every sweep would find a
     * change anyway and the compare would cost more than it saved.
     */
    void refresh() {
        var patterns = ppu.getBackgroundPatternTable();
        var colours = new int[4][];

        for (var i = 0; i < 4; i++) {
            colours[i] = Tiles.coloursOf(ppu, palette, i * 4, false);
        }

        for (var table = 0; table < 4; table++) {
            var base = 0x2000 + table * 0x400;
            var originX = (table & 1) * SCREEN_WIDTH;
            var originY = (table >> 1) * SCREEN_HEIGHT;

            for (var row = 0; row < ROWS; row++) {
                for (var column = 0; column < COLUMNS; column++) {
                    var tile = ppu.peekVRAM(base + row * COLUMNS + column);

                    Tiles.draw(
                            pixels,
                            WIDTH,
                            originX + column * TILE,
                            originY + row * TILE,
                            ppu,
                            patterns + tile * 16,
                            colours[paletteOf(base, row, column)],
                            false,
                            false);
                }
            }
        }

        repaint();
    }

    /**
     * Which of the four background palettes a tile is drawn with.
     * <p>
     * One attribute byte covers a four by four block of tiles and holds four two-bit palette
     * numbers, one per two by two quadrant of it -- bit 0 the top left, bit 2 the top right, bit 4
     * the bottom left, bit 6 the bottom right. So the shift is built out of bit 1 of the row and bit
     * 1 of the column, which is exactly which quadrant the tile is in.
     */
    private int paletteOf(final int base, final int row, final int column) {
        var attribute = ppu.peekVRAM(base + ATTRIBUTES + (row / 4) * 8 + (column / 4));
        var shift = ((row & 2) << 1) | (column & 2);

        return (attribute >> shift) & 3;
    }

    /**
     * Where the top left corner of the visible window is, in this panel's own coordinates.
     * <p>
     * {@code t} is laid out {@code yyy NN YYYYY XXXXX}, and fine X is the one part of the scroll
     * that never goes near it.
     */
    private int scrollX() {
        var t = ppu.getT();

        return ((t >> 10) & 1) * SCREEN_WIDTH + (t & 0x1F) * TILE + ppu.getFineX();
    }

    private int scrollY() {
        var t = ppu.getT();

        return ((t >> 11) & 1) * SCREEN_HEIGHT + ((t >> 5) & 0x1F) * TILE + ((t >> 12) & 7);
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);

        var g2 = (Graphics2D) g.create();

        try {
            g2.drawImage(image, 0, 0, null);

            if (gridVisible) {
                g2.setColor(GRID);

                for (var x = 0; x < WIDTH; x += TILE) {
                    g2.drawLine(x, 0, x, HEIGHT);
                }

                for (var y = 0; y < HEIGHT; y += TILE) {
                    g2.drawLine(0, y, WIDTH, y);
                }
            }

            if (scrollVisible) {
                paintScroll(g2);
            }
        } finally {
            g2.dispose();
        }
    }

    /**
     * The visible window, and its wrapped halves.
     * <p>
     * A screen is 256 by 240 inside a 512 by 480 space, so a scroll position anywhere but the top
     * left corner puts part of the window off the right or the bottom -- and that part is really on
     * the other side, because the nametables wrap. Four copies at the four offsets is the whole of
     * saying so; three of them are off the panel and cost a clipped fill.
     */
    private void paintScroll(final Graphics2D g2) {
        var x = scrollX();
        var y = scrollY();

        g2.setColor(SCROLL);
        g2.setStroke(new BasicStroke(2.0f));

        for (var dx = 0; dx >= -WIDTH; dx -= WIDTH) {
            for (var dy = 0; dy >= -HEIGHT; dy -= HEIGHT) {
                g2.drawRect(x + dx, y + dy, SCREEN_WIDTH, SCREEN_HEIGHT);
            }
        }
    }
}
