package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Where the sixty four sprites are, drawn on a screen-sized field of their own.
 * <p>
 * The picture already shows the sprites that are on screen. What it cannot show is the ones that are
 * <em>not</em> -- parked at Y=$F0 because the game is done with them, left over from the last level,
 * or sitting one pixel off an edge -- and "why has that enemy not appeared" is a question about
 * exactly those. So every sprite is drawn here whatever its coordinates, and the eight the hardware
 * would have kept on a given line are still eight in the picture rather than here.
 * <p>
 * The Y byte in OAM is one less than the line the sprite is drawn on, because the hardware compares
 * it against the line it is evaluating and draws on the next one. The table beside this shows the
 * byte, since that is what is in memory and what a watchpoint would catch; this draws it where the
 * sprite really lands.
 */
final class SpriteFieldPanel extends JComponent {
    private static final Color BACKGROUND = new Color(0x20, 0x20, 0x28);
    private static final Color EDGE = new Color(1.0f, 1.0f, 1.0f, 0.25f);
    private static final Color SELECTED = new Color(1.0f, 0.85f, 0.2f, 0.95f);

    /**
     * Drawn at twice size, which is the difference between eight pixels of sprite and something a
     * person can see.
     */
    private static final int SCALE = 2;

    private final BufferedImage[] sprites;
    private final int[] x = new int[64];
    private final int[] y = new int[64];

    private int height = 8;
    private int selected = -1;

    SpriteFieldPanel(final BufferedImage[] sprites) {
        this.sprites = sprites;

        setPreferredSize(new Dimension(
                PPU.SCREEN_WIDTH * SCALE, PPU.SCREEN_HEIGHT * SCALE));
    }

    /**
     * Where each sprite is, taken in one go with the images beside it so that the two describe the
     * same moment.
     */
    void setPositions(final int[] left, final int[] top, final int spriteHeight) {
        System.arraycopy(left, 0, x, 0, x.length);
        System.arraycopy(top, 0, y, 0, y.length);

        height = spriteHeight;

        repaint();
    }

    void setSelected(final int sprite) {
        selected = sprite;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);

        var g2 = (Graphics2D) g.create();

        try {
            g2.setColor(BACKGROUND);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Backwards, so that sprite 0 ends up on top: the hardware gives the lowest numbered
            // sprite priority over the ones after it, and a field that drew them in order would
            // show the opposite.
            for (var i = 63; i >= 0; i--) {
                g2.drawImage(
                        sprites[i],
                        x[i] * SCALE,
                        (y[i] + 1) * SCALE,
                        8 * SCALE,
                        height * SCALE,
                        null);
            }

            g2.setColor(EDGE);
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

            if (selected >= 0) {
                g2.setColor(SELECTED);
                g2.setStroke(new BasicStroke(2.0f));
                g2.drawRect(
                        x[selected] * SCALE - 1,
                        (y[selected] + 1) * SCALE - 1,
                        8 * SCALE + 2,
                        height * SCALE + 2);
            }
        } finally {
            g2.dispose();
        }
    }
}
