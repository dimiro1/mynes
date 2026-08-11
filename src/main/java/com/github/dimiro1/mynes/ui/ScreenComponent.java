package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.ui.palette.NESPalette;
import com.github.dimiro1.mynes.ui.palette.Palettes;
import com.github.dimiro1.mynes.video.FrameRenderer;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * The television. Draws the PPU's framebuffer, scaled and letterboxed to fill the window, in
 * whichever palette the user has picked.
 * <p>
 * The chip emits colour indices rather than colours -- see {@link PPU#getFrameBuffer()} -- so the
 * mapping to RGB happens here, which is what makes the palette a property of this end of the wire:
 * changing it is one field on the event dispatch thread, and it holds across a power cycle without
 * anything having to remember to re-apply it.
 * <p>
 * Two threads meet here. The emulation thread hands over a finished frame through
 * {@link #present(int[])}, the event dispatch thread paints it, and a lock covers both buffers so
 * that a paint never catches a half copied picture. The lock is held for an arraycopy of 240KB and
 * 61440 array lookups, sixty times a second -- tens of microseconds, so neither side waits for
 * long.
 * <p>
 * The picture is cropped, by {@link FrameRenderer#OVERSCAN_TOP} and its neighbours, which is also
 * where the reason for it is written down.
 */
public class ScreenComponent extends JComponent {
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
     * costs the image its hardware acceleration, which is the trade: one pass per frame beats
     * 61440 calls to {@link BufferedImage#setRGB}.
     */
    private final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

    /**
     * The last frame as the PPU handed it over, in colour indices. Kept so that changing the
     * palette can recolour the picture already on screen instead of waiting for another frame --
     * which, with the emulator paused, is not coming.
     */
    private final int[] frame = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];

    /**
     * Whether {@link #frame} holds a picture yet. Without this the window before any ROM is loaded
     * would colourise an all-zero frame to entry 0, which is dark grey rather than the black a
     * television off shows.
     */
    private boolean hasFrame;

    private int[] palette = Palettes.defaultPalette().colours();

    public ScreenComponent() {
        setPreferredSize(new Dimension(
                PPU.SCREEN_WIDTH * DEFAULT_SCALE, FrameRenderer.VISIBLE_HEIGHT * DEFAULT_SCALE));
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
            System.arraycopy(frameBuffer, 0, frame, 0, frame.length);
            hasFrame = true;
            colourise();
        }

        repaint();
    }

    /**
     * Draws everything from now on in {@code palette}, including the frame already on screen.
     * <p>
     * Called on the event dispatch thread, which is what makes flicking through the palettes a
     * live comparison: the picture behind the dialog changes on every selection, and it changes
     * even with the emulator paused, because the recolouring works from the frame that is already
     * here rather than from the next one.
     */
    public void setPalette(final NESPalette palette) {
        synchronized (frameLock) {
            this.palette = palette.colours();
            colourise();
        }

        repaint();
    }

    /**
     * Maps the frame's colour indices through the palette into the image. The caller holds
     * {@link #frameLock}.
     */
    private void colourise() {
        if (!hasFrame) {
            return;
        }

        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = palette[frame[i]];
        }
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
                    getHeight() / (double) FrameRenderer.VISIBLE_HEIGHT);
            var width = (int) (PPU.SCREEN_WIDTH * scale);
            var height = (int) (FrameRenderer.VISIBLE_HEIGHT * scale);
            var x = (getWidth() - width) / 2;
            var y = (getHeight() - height) / 2;

            synchronized (frameLock) {
                g2.drawImage(
                        image,
                        x, y, x + width, y + height,
                        0, FrameRenderer.OVERSCAN_TOP,
                        PPU.SCREEN_WIDTH, FrameRenderer.VISIBLE_BOTTOM,
                        null);
            }
        } finally {
            g2.dispose();
        }
    }
}
