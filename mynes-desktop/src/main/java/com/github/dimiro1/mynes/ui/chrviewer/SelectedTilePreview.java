package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.mappers.Mapper;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/** Enlarged tile with square pixels, and both halves when showing an 8x16 sprite. */
final class SelectedTilePreview extends JComponent {
    private static final int SCALE = 16;
    private final Mapper mapper;
    private int address;
    private boolean tall;
    private boolean grid;
    private int[] palette = TileComponent.DEFAULT_PALETTE.clone();

    SelectedTilePreview(final Mapper mapper) {
        this.mapper = mapper;
        setPreferredSize(new Dimension(8 * SCALE, 16 * SCALE));
    }

    void show(final int address, final boolean tall, final int[] palette) {
        this.address = address;
        this.tall = tall;
        this.palette = palette.clone();
        repaint();
    }

    void setGrid(final boolean grid) {
        this.grid = grid;
        repaint();
    }

    int pixelAt(final int x, final int y) {
        if (x < 0 || x >= 8 || y < 0 || y >= (tall ? 16 : 8)) {
            return -1;
        }
        var tileAddress = address + (y / 8) * 16;
        var row = y % 8;
        var shift = 7 - x;
        return (((mapper.charRead(tileAddress + row + 8) >> shift) & 1) << 1)
                | ((mapper.charRead(tileAddress + row) >> shift) & 1);
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);
        var g2 = (Graphics2D) g.create();
        try {
            var rows = tall ? 16 : 8;
            var top = tall ? 0 : 4 * SCALE;
            for (var y = 0; y < rows; y++) {
                for (var x = 0; x < 8; x++) {
                    g2.setColor(new Color(palette[pixelAt(x, y)]));
                    g2.fillRect(x * SCALE, top + y * SCALE, SCALE, SCALE);
                }
            }
            if (grid) {
                g2.setColor(new Color(255, 255, 255, 90));
                for (var x = 0; x <= 8; x++) {
                    g2.drawLine(x * SCALE, top, x * SCALE, top + rows * SCALE);
                }
                for (var y = 0; y <= rows; y++) {
                    g2.drawLine(0, top + y * SCALE, 8 * SCALE, top + y * SCALE);
                }
            }
        } finally {
            g2.dispose();
        }
    }

    int pixelX(final int componentX) {
        return componentX / SCALE;
    }

    int pixelY(final int componentY) {
        return componentY / SCALE - (tall ? 0 : 4);
    }
}
