package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Where the sixty four sprites are, drawn over the screen they are on.
 * <p>
 * The picture already shows the sprites that are on screen. What it cannot show is the ones that are
 * <em>not</em> -- parked at Y=$F0 because the game is done with them, left over from the last level,
 * or sitting one pixel off an edge -- and "why has that enemy not appeared" is a question about
 * exactly those. So every sprite is drawn here whatever its coordinates, and the eight the hardware
 * would have kept on a given line are still eight in the picture rather than here.
 * <p>
 * <b>The frame goes behind them with its brightness taken off</b>, because half of that question is
 * where in the level the sprite is: a lone eight by eight square on a flat field says a sprite is at
 * (72, 140) and nothing about whether that is inside the pipe it is supposed to come out of. The
 * sprites are drawn over the top at full strength, so an on-screen one lands on itself and the ones
 * the picture never had stand out from it.
 * <p>
 * <b>All 240 lines are here, and the sixteen a television hid are shaded</b> rather than cropped
 * away: a sprite that has been pushed into the overscan is one the player cannot see, which is the
 * same question as a sprite parked at Y=$F0 and deserves the same answer. It also keeps this picture
 * the same shape as the one in the game window, which draws the 224 in front of the bezel.
 * <p>
 * The Y byte in OAM is one less than the line the sprite is drawn on, because the hardware compares
 * it against the line it is evaluating and draws on the next one. The table beside this shows the
 * byte, since that is what is in memory and what a watchpoint would catch; this draws it where the
 * sprite really lands.
 */
final class SpriteFieldPanel extends JComponent {
    private static final Color EDGE = new Color(1.0f, 1.0f, 1.0f, 0.25f);
    private static final Color SELECTED = new Color(1.0f, 0.85f, 0.2f, 0.95f);

    /**
     * Drawn at twice size, which is the difference between eight pixels of sprite and something a
     * person can see.
     */
    private static final int SCALE = 2;

    private final PPU ppu;
    private final BufferedImage[] sprites;

    private final BufferedImage screen = new BufferedImage(
            PPU.SCREEN_WIDTH, PPU.SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);

    private final int[] pixels =
            ((DataBufferInt) screen.getRaster().getDataBuffer()).getData();

    private final int[] x = new int[PaletteUse.SPRITES];
    private final int[] y = new int[PaletteUse.SPRITES];

    private int[] colours;
    private int height = 8;
    private int[] selected = new int[0];

    SpriteFieldPanel(
            final BufferedImage[] sprites, final PPU ppu, final NESPalette palette) {

        this.sprites = sprites;
        this.ppu = ppu;
        this.colours = palette.colours();

        setPreferredSize(new Dimension(
                PPU.SCREEN_WIDTH * SCALE, PPU.SCREEN_HEIGHT * SCALE));
    }

    void setPalette(final NESPalette palette) {
        this.colours = palette.colours();
        repaint();
    }

    /**
     * Where each sprite is, taken in one go with the images beside it and with the frame behind it,
     * so that all three describe the same moment.
     */
    void setPositions(final int[] left, final int[] top, final int spriteHeight) {
        System.arraycopy(left, 0, x, 0, x.length);
        System.arraycopy(top, 0, y, 0, y.length);

        height = spriteHeight;

        Screen.drawDimmed(pixels, ppu, colours, null);

        repaint();
    }

    /**
     * Which sprites to draw a box around. More than one, because a thing on the screen is usually
     * made of several: a boss is a dozen sprites and the question is where all twelve are.
     * <p>
     * They are outlined as one shape rather than one box each -- see {@link #outlineOf}.
     */
    void setSelected(final int[] sprites) {
        selected = sprites.clone();
        repaint();
    }

    /**
     * The edge round a group of sprites, and only the edge.
     * <p>
     * A box each would put a line down every seam of whatever the sprites make up -- a six sprite
     * Mario would come out as six boxes with four lines through him, which hides the shape the
     * selection was made to see. The union has no seams in it, so what is drawn is the outside of
     * the thing and nothing else; a selection whose sprites are nowhere near each other is several
     * shapes, and gets an outline round each of them, which is the right answer to that too.
     * <p>
     * Each rectangle is grown by a pixel on every side first. That is where the outline used to sit
     * anyway, and it is also what makes two sprites drawn edge to edge overlap rather than merely
     * touch, which is the difference between one shape and two with a line between them.
     */
    private Area outlineOf(final int[] sprites) {
        var outline = new Area();

        for (var sprite : sprites) {
            outline.add(new Area(new Rectangle(
                    x[sprite] * SCALE - 1,
                    (y[sprite] + 1) * SCALE - 1,
                    8 * SCALE + 2,
                    height * SCALE + 2)));
        }

        return outline;
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);

        var g2 = (Graphics2D) g.create();

        try {
            g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            g2.drawImage(screen, 0, 0, getWidth(), getHeight(), null);

            // Backwards, so that sprite 0 ends up on top: the hardware gives the lowest numbered
            // sprite priority over the ones after it, and a field that drew them in order would
            // show the opposite.
            for (var i = sprites.length - 1; i >= 0; i--) {
                g2.drawImage(
                        sprites[i],
                        x[i] * SCALE,
                        (y[i] + 1) * SCALE,
                        8 * SCALE,
                        height * SCALE,
                        null);
            }

            // After the sprites, so one parked behind the bezel is still drawn and still visible
            // through the shading -- which is the difference between "you cannot see it" and "it
            // is not there".
            Screen.paintOverscan(g2, 0, 0, SCALE);

            g2.setColor(EDGE);
            g2.drawRect(0, 0, getWidth() - 1, getHeight() - 1);

            if (selected.length > 0) {
                g2.setColor(SELECTED);
                g2.setStroke(new BasicStroke(2.0f));
                g2.draw(outlineOf(selected));
            }
        } finally {
            g2.dispose();
        }
    }
}
