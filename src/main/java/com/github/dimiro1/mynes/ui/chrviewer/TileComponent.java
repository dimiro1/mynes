package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.cart.mappers.Mapper;
import com.github.dimiro1.mynes.utils.ByteUtils;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class TileComponent extends JComponent {
    private final int tileNumber;
    private final int[] tileData = new int[16];
    private boolean isHighlighted = false;
    private final Mapper mapper;
    private final BufferedImage bufferedImage;
    private final int address;

    public TileComponent(final int tileNumber, final int baseAddress, final Mapper mapper) {
        this.tileNumber = tileNumber;
        this.address = (tileNumber * 16) + baseAddress;
        this.mapper = mapper;
        bufferedImage = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        setSize(16, 16);
        refresh();
    }

    /**
     * Fetch tile data from memory.
     */
    public void refresh() {
        var byteIndex = 0;

        for (var i = address; i <= address + 15; i++) {
            tileData[byteIndex] = mapper.charRead(i);
            byteIndex++;
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
        isHighlighted = true;
        repaint();
    }

    /**
     * Remove the border around the tile.
     */
    public void removeHighlight() {
        isHighlighted = false;
        repaint();
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

        g.drawImage(bufferedImage, 0, 0, 16, 16, null);

        if (isHighlighted) {
            g.setColor(new Color(1.0f, 1.0f, 0.0f, 0.5f));
            g.fillRect(0, 0, 16, 16);
        }
    }
}
