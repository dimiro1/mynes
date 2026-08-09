package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.PPU;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * The television. Draws the PPU's framebuffer, scaled and letterboxed to fill the window.
 * <p>
 * Two threads meet here. The emulation thread hands over a finished frame through
 * {@link #present(int[])}, the event dispatch thread paints it, and a lock covers the pixel array
 * so that a paint never catches a half copied picture. The copy is a single
 * {@link System#arraycopy} of 240KB sixty times a second, so neither side waits for long.
 * <p>
 * The picture is cropped: a real television hides roughly the outer eight scanlines behind the
 * bezel, and games rely on it, drawing partial tiles and scroll seams up there. Showing the whole
 * 240 lines shows that mess.
 */
public class ScreenComponent extends JComponent {
    /**
     * Scanlines hidden at the top and the bottom of the picture, leaving 224 visible.
     */
    private static final int OVERSCAN_TOP = 8;
    private static final int OVERSCAN_BOTTOM = 8;

    private static final int VISIBLE_HEIGHT = PPU.SCREEN_HEIGHT - OVERSCAN_TOP - OVERSCAN_BOTTOM;

    /**
     * How much the picture is magnified when the window first opens. 256x224 is tiny on a modern
     * display; the window is resizable from there.
     */
    private static final int DEFAULT_SCALE = 2;

    private final Object frameLock = new Object();
    private final BufferedImage image = new BufferedImage(
            PPU.SCREEN_WIDTH, PPU.SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);

    /**
     * The image's own storage, written into directly. Reaching for the backing array like this
     * costs the image its hardware acceleration, which is the trade: one arraycopy per frame
     * beats 61440 calls to {@link BufferedImage#setRGB}.
     */
    private final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

    public ScreenComponent() {
        setPreferredSize(new Dimension(
                PPU.SCREEN_WIDTH * DEFAULT_SCALE, VISIBLE_HEIGHT * DEFAULT_SCALE));
        setOpaque(true);
    }

    /**
     * Takes a copy of a completed frame and asks for a repaint.
     * <p>
     * Called from the emulation thread. {@link #repaint()} is one of the few Swing methods that is
     * safe to call from anywhere: it only posts a request to the event queue.
     *
     * @param frameBuffer the PPU's live framebuffer, {@link PPU#getFrameBuffer()}.
     */
    public void present(final int[] frameBuffer) {
        synchronized (frameLock) {
            System.arraycopy(frameBuffer, 0, pixels, 0, pixels.length);
        }

        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);

        var g2 = (Graphics2D) g.create();

        try {
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Nearest neighbour: a blurred NES picture looks worse than a blocky one.
            g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            // Uniform scale, centred, so the aspect ratio survives a resize in either direction.
            var scale = Math.min(
                    getWidth() / (double) PPU.SCREEN_WIDTH,
                    getHeight() / (double) VISIBLE_HEIGHT);
            var width = (int) (PPU.SCREEN_WIDTH * scale);
            var height = (int) (VISIBLE_HEIGHT * scale);
            var x = (getWidth() - width) / 2;
            var y = (getHeight() - height) / 2;

            synchronized (frameLock) {
                g2.drawImage(
                        image,
                        x, y, x + width, y + height,
                        0, OVERSCAN_TOP, PPU.SCREEN_WIDTH, PPU.SCREEN_HEIGHT - OVERSCAN_BOTTOM,
                        null);
            }
        } finally {
            g2.dispose();
        }
    }
}
