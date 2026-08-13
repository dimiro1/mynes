package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * Turns a frame of the PPU's colour indices into a picture.
 * <p>
 * The chip emits indices rather than colours -- see {@link PPU#getFrameBuffer()} -- so somebody has
 * to do this, and there are two somebodies: the window, which does it sixty times a second into an
 * image it already owns, and the headless mode, which does it once and writes a PNG. They disagree
 * about almost everything except where the picture ends, which is why what they share is this class
 * and not a common superclass.
 * <p>
 * The palette arrives as a plain {@code int[512]} rather than as a
 * {@link com.github.dimiro1.mynes.ui.palette.NESPalette}, so that drawing a picture does not oblige
 * a caller to know what a palette is made of, and so that this package depends on nothing but the
 * PPU and {@code java.awt.image}.
 */
public final class FrameRenderer {
    /**
     * Scanlines hidden at the top and the bottom of the picture, leaving 224 visible.
     * <p>
     * A real television hides roughly this much behind the bezel, and games rely on it, drawing
     * partial tiles and scroll seams up there. Showing the whole 240 lines shows that mess -- along
     * with the pixel or two of the following frame that the emulation loop's three-dots-per-tick
     * granularity lets into scanline 0.
     * <p>
     * That last allowance is wider than a pixel or two while a debugger is watching, and this is the
     * margin it is spent out of. A watched loop advances an instruction at a time rather than a tick
     * at a time, so it can overshoot the frame boundary by a whole instruction: around 518 CPU
     * cycles when the step swallows an OAM DMA transfer, which is 1554 dots, which is four and a
     * half scanlines. Under eight, with about a third to spare -- but anybody thinking of shaving
     * this number should know that is what they would be shaving into.
     */
    public static final int OVERSCAN_TOP = 8;
    public static final int OVERSCAN_BOTTOM = 8;

    /**
     * The first scanline below the picture, so that walking it is
     * {@code for (y = OVERSCAN_TOP; y < VISIBLE_BOTTOM; y++)} everywhere rather than three
     * separate spellings of the same subtraction.
     */
    public static final int VISIBLE_BOTTOM = PPU.SCREEN_HEIGHT - OVERSCAN_BOTTOM;

    public static final int VISIBLE_HEIGHT = VISIBLE_BOTTOM - OVERSCAN_TOP;

    /**
     * The largest magnification {@link #render} will do. Eight times is a 2048x1792 picture, which
     * is past the point where a bigger number is telling you something new about the frame.
     */
    public static final int MAX_SCALE = 8;

    private FrameRenderer() {
    }

    /**
     * Draws a frame.
     * <p>
     * Scaling is done by repeating pixels rather than by handing the job to
     * {@link java.awt.Graphics2D}, which would need a rendering hint set correctly to avoid
     * blurring a picture whose whole character is that it is blocky. There is no hint to get wrong
     * here.
     *
     * @param frame         a frame of colour indices, {@link PPU#getFrameBuffer()}. Read, never
     *                      kept.
     * @param palette       512 packed ARGB entries indexed {@code emphasis << 6 | entry}, which is
     *                      what {@code NESPalette.colours()} hands out.
     * @param cropOverscan  whether to hide the scanlines a television would.
     * @param scale         how many times to magnify, 1 to {@link #MAX_SCALE}.
     * @return the picture, {@link BufferedImage#TYPE_INT_RGB}.
     */
    public static BufferedImage render(
            final int[] frame,
            final int[] palette,
            final boolean cropOverscan,
            final int scale
    ) {
        if (scale < 1 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("scale must be 1 to " + MAX_SCALE + ", not " + scale);
        }

        var top = cropOverscan ? OVERSCAN_TOP : 0;
        var height = cropOverscan ? VISIBLE_HEIGHT : PPU.SCREEN_HEIGHT;

        var image = new BufferedImage(
                PPU.SCREEN_WIDTH * scale, height * scale, BufferedImage.TYPE_INT_RGB);

        // The image's own storage, written into directly. Reaching for the backing array costs the
        // image its hardware acceleration, which does not matter for a picture that is about to be
        // encoded as a PNG, and saves a call per pixel.
        var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        var width = PPU.SCREEN_WIDTH * scale;

        for (var y = 0; y < height; y++) {
            var sourceRow = (top + y) * PPU.SCREEN_WIDTH;
            var targetRow = y * scale * width;

            for (var x = 0; x < PPU.SCREEN_WIDTH; x++) {
                var colour = palette[frame[sourceRow + x]];

                for (var i = 0; i < scale; i++) {
                    pixels[targetRow + x * scale + i] = colour;
                }
            }

            // The other rows of a scaled pixel are the row just written, so copy it rather than
            // looking every colour up again.
            for (var i = 1; i < scale; i++) {
                System.arraycopy(pixels, targetRow, pixels, targetRow + i * width, width);
            }
        }

        return image;
    }
}
