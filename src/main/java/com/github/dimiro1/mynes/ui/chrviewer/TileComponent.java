package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.ByteUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Arrays;

/**
 * One 8x8 tile from character memory, drawn scaled to whatever size the component was given.
 * <p>
 * The tile is decoded into a small image when its bytes or its colours change, rather than on
 * every paint, so a repaint costs one image blit and the CHR viewer's refresh timer can walk all
 * 256 of these without doing any real work for tiles that have not moved.
 */
public class TileComponent extends JComponent {
    /**
     * The colours a tile is drawn with when no game palette has been chosen: black plus three
     * colours picked to be tellable apart, one per bit plane combination.
     */
    public static final int[] DEFAULT_PALETTE = {
            0xFF000000, 0xFFFCE4A0, 0xFF00E8D8, 0xFF2038EC,
    };

    private static final Color HIGHLIGHT = new Color(1.0f, 1.0f, 0.0f, 0.5f);

    private int tileNumber;
    private boolean isHighlighted = false;
    private int baseAddress;
    private final int width;
    private final int height;

    private final int[] palette = DEFAULT_PALETTE.clone();
    private final int[] tileData = new int[16];
    private final BufferedImage bufferedImage;
    private final Mapper mapper;

    public TileComponent(
            final int tileNumber,
            final int baseAddress,
            final int width,
            final int height,
            final Mapper mapper
    ) {
        this.tileNumber = tileNumber;
        this.baseAddress = baseAddress;
        this.mapper = mapper;
        this.width = width;
        this.height = height;
        this.bufferedImage = new BufferedImage(
                8, 8, BufferedImage.TYPE_INT_RGB);

        setPreferredSize(new Dimension(width, height));
        fetchTileData();
        renderImage();
    }

    /**
     * Creates a TileComponent with width=16px and height=16px
     */
    public TileComponent(
            final int tileNumber,
            final int baseAddress,
            final Mapper mapper
    ) {
        this(tileNumber, baseAddress, 16, 16, mapper);
    }

    /**
     * Fetches the tile's bytes from character memory again, redrawing only if they changed --
     * which for a CHR ROM tile is never, and for CHR RAM is whenever the game rewrote it.
     *
     * @return whether anything had changed.
     */
    public boolean refresh() {
        if (!fetchTileData()) {
            return false;
        }

        renderImage();
        repaint();
        return true;
    }

    /**
     * Hands the tile a new set of four colours, index 0 the backdrop. The array is copied.
     */
    public void setPalette(final int[] colours) {
        if (Arrays.equals(palette, colours)) {
            return;
        }

        System.arraycopy(colours, 0, palette, 0, palette.length);
        renderImage();
        repaint();
    }

    /**
     * Change the number of the tile.
     */
    public void setTileNumber(final int tileNumber) {
        if (this.tileNumber != tileNumber) {
            this.tileNumber = tileNumber;
            refresh();
        }
    }

    /**
     * Sets the base address in the CHR ROM of the tile.
     */
    public void setBaseAddress(final int baseAddress) {
        if (this.baseAddress != baseAddress) {
            this.baseAddress = baseAddress;
            refresh();
        }
    }

    /**
     * Returns the index of the tile.
     */
    public int getTileNumber() {
        return tileNumber;
    }

    /**
     * Adds a border around the tile.
     */
    public void highlight() {
        if (!isHighlighted) {
            isHighlighted = true;
            repaint();
        }
    }

    /**
     * Remove the border around the tile.
     */
    public void removeHighlight() {
        if (isHighlighted) {
            isHighlighted = false;
            repaint();
        }
    }

    private boolean fetchTileData() {
        var address = (tileNumber * 16) + baseAddress;
        var changed = false;

        for (int i = 0; i < 16; i++) {
            var read = mapper.charRead(address + i);
            if (tileData[i] != read) {
                tileData[i] = read;
                changed = true;
            }
        }

        return changed;
    }

    private void renderImage() {
        for (int y = 0; y < 8; y++) {
            // The first plane holds the low bit of each pixel, the one eight bytes later the
            // high bit -- same order the PPU fetches them in.
            var low = tileData[y];
            var high = tileData[y + 8];

            for (int x = 0; x < 8; x++) {
                var paletteColor = ByteUtils.joinBits(
                        ByteUtils.getBit(7 - x, high), ByteUtils.getBit(7 - x, low));

                bufferedImage.setRGB(x, y, palette[paletteColor]);
            }
        }
    }

    @Override
    public void paintComponent(final Graphics g) {
        super.paintComponent(g);

        var g2 = (Graphics2D) g.create();

        try {
            // Nearest neighbour: pixel art scaled smoothly turns to mush.
            g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(bufferedImage, 0, 0, this.width, this.height, null);

            if (isHighlighted) {
                g2.setColor(HIGHLIGHT);
                g2.fillRect(0, 0, this.width, this.height);
            }
        } finally {
            g2.dispose();
        }
    }
}
