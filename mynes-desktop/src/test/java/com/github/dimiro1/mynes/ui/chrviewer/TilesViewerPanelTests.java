package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.Cart;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TilesViewerPanelTests {
    @Test
    void tallSpriteShowsTheNextTileAsItsBottomHalf() {
        var rom = new byte[16 + 0x4000 + 0x2000];
        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1;
        rom[5] = 1;
        for (var row = 0; row < 8; row++) {
            rom[16 + 0x4000 + 16 + row] = (byte) 0xFF;
        }

        var panel = new TilesViewerPanel(Cart.load(rom, "pairs.nes"));
        panel.setMode(TilesViewerPanel.Mode.MODE_8X16);
        panel.setSize(panel.getPreferredSize());
        var image = new BufferedImage(panel.getWidth(), panel.getHeight(),
                BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics();
        try {
            panel.paint(graphics);
        } finally {
            graphics.dispose();
        }

        assertEquals(TileComponent.DEFAULT_PALETTE[0], image.getRGB(2, 2));
        assertEquals(TileComponent.DEFAULT_PALETTE[1], image.getRGB(2, 34));
    }
}
