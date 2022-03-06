package com.github.dimiro1.mynes.ui.ppu;

import com.github.dimiro1.mynes.cart.mappers.Mapper;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;

public class Tile extends JComponent {
    private final int tileIndex;
    private final int[] tileData = new int[16];
    private boolean isHighlighted = false;
    private final Mapper mapper;
    private final BufferedImage bufferedImage;

    public Tile(final int tileIndex, final Mapper mapper) {
        this.tileIndex = tileIndex;
        this.mapper = mapper;
        bufferedImage = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        setSize(16, 16);
        fetchTileData();
    }

    /**
     * Fetch tile data from memory.
     */
    public void fetchTileData() {
        var byteIndex = 0;
        for (var address = tileIndex * 16; address < tileIndex * 16 + 15; address++) {
            tileData[byteIndex] = mapper.charRead(address);
            byteIndex++;
        }
    }

    /**
     * Returns the index of the tile.
     */
    public int getTileIndex() {
        return tileIndex;
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
    public void paint(final Graphics g) {
        super.paint(g);

        var g2 = (Graphics2D) g;

        for (var y = 0; y < 8; y++) {
            var upper = tileData[y];
            var lower = tileData[y + 8];

            for (var x = 7; x >= 0; x--) {
                var value = (1 & upper) << 1 | (1 & lower);
                upper = upper >> 1;
                lower = lower >> 1;

                var rgb = switch (value) {
                    case 1 -> new Color(0, 232, 216);
                    case 2 -> new Color(228, 0, 88);
                    case 3 -> new Color(0, 0, 168);
                    default -> Color.BLACK;
                };
                bufferedImage.setRGB(x, y, rgb.getRGB());
            }
        }

        g2.drawImage(bufferedImage, 0, 0, 16, 16, null);

        if (isHighlighted) {
            g2.setColor(Color.WHITE);
            g2.setStroke(new BasicStroke(2));
            g2.drawRect(0, 0, 16, 16);
        }
    }
}
