package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.Region;

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
 * {@link com.github.dimiro1.mynes.palette.NESPalette}, so that drawing a picture does not oblige
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
     * Columns hidden at the left of the picture, leaving 248 visible -- when anybody asks, which
     * unlike the two above is not by default. {@link Crop} is where the reason is written down.
     * <p>
     * Eight because that is the width of the 2C02's own clipping window and there is no second
     * number to choose: the stripe this hides is exactly the pixels $2001 stops the background
     * being drawn in.
     */
    public static final int OVERSCAN_LEFT = 8;

    /**
     * The first scanline below the picture, so that walking it is
     * {@code for (y = OVERSCAN_TOP; y < VISIBLE_BOTTOM; y++)} everywhere rather than three
     * separate spellings of the same subtraction.
     */
    public static final int VISIBLE_BOTTOM = PPU.SCREEN_HEIGHT - OVERSCAN_BOTTOM;

    public static final int VISIBLE_HEIGHT = VISIBLE_BOTTOM - OVERSCAN_TOP;

    /**
     * How wide the picture is once {@link #OVERSCAN_LEFT} has been taken off it. Nothing comes off
     * the right, so this is the whole of the horizontal crop.
     */
    public static final int VISIBLE_WIDTH = PPU.SCREEN_WIDTH - OVERSCAN_LEFT;

    /**
     * The largest magnification {@link #render} will do. Eight times is a 2048x1792 picture, which
     * is past the point where a bigger number is telling you something new about the frame.
     */
    public static final int MAX_SCALE = 8;

    /**
     * The shape the framebuffer's pixels are: as wide as they are tall.
     * <p>
     * Named rather than written as a bare 1 at every call, because the number beside it in every
     * one of those calls is {@link Region#pixelAspect()} -- and "square" and "the television's" are
     * the two answers to one question rather than a default and an override.
     */
    public static final double SQUARE_PIXELS = 1;

    private FrameRenderer() {
    }

    /**
     * How wide a crop magnified {@code scale} times comes out, once its pixels have been given the
     * shape {@code aspect} says they are.
     * <p>
     * The crop rather than the frame, so that a picture with {@link #OVERSCAN_LEFT} taken off it is
     * stretched as the 248 columns it is rather than as the 256 it came from -- the shape belongs
     * to a pixel, not to a frame, and the two settings compose by multiplying.
     * <p>
     * The height is {@code scale} times the crop's and needs no arithmetic, which is the asymmetry
     * worth knowing about: a picture is made the television's shape by widening it rather than by
     * shortening it, so that nothing the chip drew is thrown away to make room for the shape.
     * <p>
     * Rounded rather than truncated, and the rounding is real: 256 at 8:7 is 292.57 pixels, so at
     * every magnification some columns of the picture come out a pixel wider than others. That is
     * what a non-integer stretch of a blocky picture is, and no interpolation is put in to hide it
     * -- see {@link #magnify}.
     */
    public static int widthFor(final Crop crop, final int scale, final double aspect) {
        checkScale(scale);
        checkAspect(aspect);

        return (int) Math.round(crop.width() * (double) scale * aspect);
    }

    /**
     * Draws a frame, colouring it through a palette.
     *
     * @param frame         a frame of colour indices, {@link PPU#getFrameBuffer()}. Read, never
     *                      kept.
     * @param palette       512 packed ARGB entries indexed {@code emphasis << 6 | entry}, which is
     *                      what {@code NESPalette.colours()} hands out.
     * @param aspect        how much wider than tall to draw one pixel: {@link #SQUARE_PIXELS} for
     *                      the shape the framebuffer holds them in, {@link Region#pixelAspect()}
     *                      for the shape the television drew them.
     * @param crop          which rectangle of the frame is the picture.
     * @param scale         how many times to magnify, 1 to {@link #MAX_SCALE}.
     * @return the picture, {@link BufferedImage#TYPE_INT_RGB}.
     */
    public static BufferedImage render(
            final int[] frame,
            final int[] palette,
            final double aspect,
            final Crop crop,
            final int scale
    ) {
        return magnify(through(frame, palette), aspect, crop, scale);
    }

    /**
     * The same again, put on the screen a picture tube put it on.
     * <p>
     * The palette <em>is</em> a parameter this time, unlike the decoder below, and that is the
     * difference between the two filters in one line: a tube is not a rival answer to what colour
     * entry $21 is, it is what happened to the answer afterwards. So this colours the frame exactly
     * as the call above does and then hands it to {@link CRTScreen}, which magnifies it itself --
     * the crop, the mask and the bend are one pass there, for reasons written down beside them.
     * <p>
     * <strong>At {@code scale} 1 the mask draws nothing.</strong> A scanline is the row a line was
     * not drawn on and one row per line leaves nowhere to put it, so {@link CRTScreen} fades it to
     * nothing rather than dimming the whole picture by way of an answer. The front ends refuse the
     * combination before it gets here; this does the harmless thing so that a caller which does not
     * is wrong about the picture rather than about the arithmetic.
     *
     * @param frame        a frame of colour indices, {@link PPU#getFrameBuffer()}.
     * @param palette      512 packed ARGB entries, as above.
     * @param strength     how dark the gaps between the lines go.
     * @param warp         whether the glass is curved.
     * @param aspect       how much wider than tall to draw one pixel, as above. Free here: the tube
     *                     is already asked for a picture of a given size and works out where on the
     *                     raster each of its pixels lands, so a wider one is a wider raster rather
     *                     than a stretch of a picture that was drawn narrow.
     * @param crop         which rectangle of the frame is the picture.
     * @param scale        how many times to magnify, 1 to {@link #MAX_SCALE}.
     * @return the picture, {@link BufferedImage#TYPE_INT_RGB}.
     */
    public static BufferedImage render(
            final int[] frame,
            final int[] palette,
            final FilterStrength strength,
            final boolean warp,
            final double aspect,
            final Crop crop,
            final int scale
    ) {
        var width = widthFor(crop, scale, aspect);
        var height = crop.height() * scale;

        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        CRTScreen.draw(
                through(frame, palette), crop, pixels, width, height, strength, warp);

        return image;
    }

    private static int[] through(final int[] frame, final int[] palette) {
        var colours = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];

        for (var i = 0; i < colours.length; i++) {
            colours[i] = palette[frame[i]];
        }

        return colours;
    }

    /**
     * The same, decoded as a composite signal rather than looked up in a palette.
     * <p>
     * The palette is not a parameter because it is not consulted: an {@link NTSCFilter} works out
     * its own colours from the waveform the chip would have drawn, and a measured table is a rival
     * answer to that question rather than a stage of it.
     *
     * @param frame        a frame of colour indices, {@link PPU#getFrameBuffer()}.
     * @param filter       the decoder. Stateful, so it must not be shared across threads.
     * @param framePhase   where the frame sits in the subcarrier's cycle,
     *                     {@link PPU#getFramePhase()}.
     * @param aspect       how much wider than tall to draw one pixel, as above. Two separate
     *                     stretches of one picture and neither is the other: the decoder resolves
     *                     the 2C02's line into 256 samples because that is how many pixels the chip
     *                     put in it, and this says how wide the line those samples came off was.
     * @param crop         which rectangle of the frame is the picture.
     * @param scale        how many times to magnify, 1 to {@link #MAX_SCALE}.
     * @return the picture, {@link BufferedImage#TYPE_INT_RGB}.
     */
    public static BufferedImage render(
            final int[] frame,
            final NTSCFilter filter,
            final int framePhase,
            final double aspect,
            final Crop crop,
            final int scale
    ) {
        return magnify(filter.colourise(frame, framePhase), aspect, crop, scale);
    }

    /**
     * Crops and magnifies a frame that is already in colour.
     * <p>
     * Scaling repeats pixels rather than handing the job to {@link java.awt.Graphics2D}, which
     * would need a rendering hint set correctly to avoid blurring a picture whose whole character
     * is that it is blocky. There is no hint to get wrong here.
     * <p>
     * <strong>Which column a pixel comes from is a table rather than a division</strong>, and that
     * is what lets the width be anything: at {@link #SQUARE_PIXELS} it works out to the same run of
     * {@code scale} identical columns the old repetition wrote, and at 8:7 it works out to runs of
     * two and three that add up to the right shape. Nothing is interpolated between them -- a
     * blurred NES picture looks worse than a blocky one, which is the same reason the rest of this
     * class magnifies the way it does, and the uneven columns are what an honest stretch of a
     * blocky picture looks like.
     * <p>
     * Only the columns need it. The height is a whole multiple of the crop's however wide the
     * picture is, so the rows are still written once and copied.
     */
    private static BufferedImage magnify(
            final int[] colours, final double aspect, final Crop crop, final int scale) {
        var width = widthFor(crop, scale, aspect);
        var image = new BufferedImage(
                width, crop.height() * scale, BufferedImage.TYPE_INT_RGB);

        // The image's own storage, written into directly. Reaching for the backing array costs the
        // image its hardware acceleration, which does not matter for a picture that is about to be
        // encoded as a PNG, and saves a call per pixel.
        var pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
        var columns = new int[width];

        for (var x = 0; x < width; x++) {
            columns[x] = Math.min((int) ((x + 0.5) * crop.width() / width), crop.width() - 1);
        }

        for (var y = 0; y < crop.height(); y++) {
            var sourceRow = (crop.top() + y) * PPU.SCREEN_WIDTH + crop.left();
            var targetRow = y * scale * width;

            for (var x = 0; x < width; x++) {
                pixels[targetRow + x] = colours[sourceRow + columns[x]];
            }

            // The other rows of a scaled pixel are the row just written, so copy it rather than
            // looking every colour up again.
            for (var i = 1; i < scale; i++) {
                System.arraycopy(pixels, targetRow, pixels, targetRow + i * width, width);
            }
        }

        return image;
    }

    private static void checkScale(final int scale) {
        if (scale < 1 || scale > MAX_SCALE) {
            throw new IllegalArgumentException("scale must be 1 to " + MAX_SCALE + ", not " + scale);
        }
    }

    /**
     * Said rather than left to the zero-width image that would come out of it. The number arrives
     * from a region rather than from a command line, so anything wrong with it is a bug here and a
     * picture of no pixels is a poor way to report one.
     */
    private static void checkAspect(final double aspect) {
        if (!(aspect > 0) || Double.isInfinite(aspect)) {
            throw new IllegalArgumentException(
                    "a pixel is some positive number of times as wide as it is tall, not " + aspect);
        }
    }
}
