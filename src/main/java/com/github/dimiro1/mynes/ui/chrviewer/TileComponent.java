package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.cart.mappers.Mapper;
import com.github.dimiro1.mynes.utils.ByteUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class TileComponent extends JComponent {
    private int tileNumber;
    private boolean isHighlighted = false;
    private int baseAddress;
    private final int width;
    private final int height;

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
        refresh();
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
     * Fetch tile data from memory.
     */
    public void refresh() {
        var address = (tileNumber * 16) + baseAddress;
        for (var i = 0; i < 16; i++) {
            tileData[i] = mapper.charRead(address + i);
        }
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

    @Override
    public void paintComponent(final Graphics g) {
        super.paintComponent(g);

        for (var y = 0; y < 8; y++) {
            var upper = tileData[y];
            var lower = tileData[y + 8];

            for (var x = 0; x < 8; x++) {
                var paletteColor = ByteUtils.joinBits(
                        ByteUtils.getBit(7 - x, upper), ByteUtils.getBit(7 - x, lower));

                var color = switch (paletteColor) {
                    case 0b01 -> new Color(252, 228, 160);
                    case 0b10 -> new Color(0, 232, 216);
                    case 0b11 -> new Color(32, 56, 236);
                    default -> Color.BLACK;
                };

                bufferedImage.setRGB(x, y, color.getRGB());
            }
        }

        g.drawImage(bufferedImage, 0, 0, this.width, this.height, null);

        if (isHighlighted) {
            g.setColor(new Color(1.0f, 1.0f, 0.0f, 0.5f));
            g.fillRect(0, 0, this.width, this.height);
        }
    }
}
