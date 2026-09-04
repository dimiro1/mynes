package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;

/**
 * The screen the picture ended up on: the unlit half of the raster, and the curve of the glass.
 * <p>
 * A NES sends 240 lines and never interlaces, into a television built to draw 480 of them. So the
 * beam lays down a line, skips the position the other field would have used, and lays down the
 * next -- and the gaps are not a defect of the picture but half of the screen it was drawn on. The
 * glass in front of them was a section of a sphere, so the raster drawn flat on it came out bowed.
 * Neither is something the console does, which is why this is a filter rather than a hack: nothing
 * about the machine changes, and what comes out is the picture the hardware always made once it
 * reached a tube.
 * <p>
 * <strong>Magnifying, masking and bending are one pass</strong>, and that is worth saying because
 * the obvious arrangement is three. A picture bent after it has been masked has to resample a
 * pattern that repeats every two rows, which is where a scanline filter picks up its moiré; a
 * picture masked after it has been bent has straight scanlines lying across a curved raster, which
 * is a tube nobody built. Doing all three at once dodges both: every output pixel works out where
 * on the raster it is, reads the colour there, and takes its share of light from the same place.
 * <p>
 * <strong>The mask is a table of phases rather than a row of rows.</strong> Once the picture is
 * bent, "which row of the raster is this" stops being a whole number and stops being the same
 * answer along a row -- so what is wanted is the light at an arbitrary point between two lines,
 * which is one lookup. {@link #PHASES} entries is finer than a unit of alpha at every depth, so the
 * table is the exact answer rounded rather than an approximation of it.
 * <p>
 * <strong>Under two rows to a line the mask fades out and stops.</strong> A gap needs a row to be
 * in, and at 1x there is not one: all a mask could do there is take the same light off every row,
 * which is a dimmer switch wearing a television's clothes. So the depth is scaled by how much room
 * there is and reaches nothing at 1x, which makes a window dragged down through 2x fade the
 * scanlines out rather than break them up. The command line, where the magnification is a number
 * somebody typed rather than a corner somebody dragged, refuses 1x instead and says why.
 * <p>
 * The picture is dimmer with the mask on, and no gain is put back. That is not an omission: half
 * the raster is unlit and the light really is gone, which is why every one of these televisions was
 * brighter than its picture. Turning it back up would mean clipping the highlights that are already
 * at white, which trades the scanline away exactly where it shows most.
 *
 * @see <a href="https://www.nesdev.org/wiki/NTSC_video">NESdev: NTSC video</a>
 */
public final class CRTScreen {

    /**
     * Rows per line at which the mask reaches its full depth, and below which it fades away. Two,
     * because two is where the second row -- the one the gap lives in -- arrives.
     * <p>
     * Public because a front end whose magnification is a number somebody typed refuses anything
     * under it rather than writing out a picture with no scanlines in it. A window, whose
     * magnification is wherever a corner was dragged to, takes the fade instead.
     */
    public static final int MINIMUM_ROWS_PER_LINE = 2;

    /**
     * How far the glass bulges, as the fraction of a half-width that the picture's corner is pulled
     * in by.
     * <p>
     * Four percent, which is a living room television rather than a fishbowl. The number is small
     * on purpose and the reason is arithmetic rather than taste: the bend is a shift of about
     * twenty pixels at the corner of a 4x picture and nothing anywhere near the middle, so the
     * raster is locally a translation and resampling it costs the picture nothing. Wind it up far
     * enough to see it as a lens and the corners start being scaled rather than moved, which is
     * where a nearest-neighbour fetch of a blocky picture stops being free.
     */
    private static final double CURVE = 0.04;

    /**
     * How finely the mask is tabulated across one line of the raster. A power of two for no reason
     * except that it costs nothing; 1024 puts the worst rounding error under half a unit of alpha
     * at the deepest setting, which is under the resolution of the thing being tabulated.
     */
    private static final int PHASES = 1024;

    /**
     * How far apart two rows of the frame are in {@code colours}. A whole frame's width rather than
     * the picture's, since a {@link Crop} narrower than the frame reads a window out of the middle
     * of each row rather than a shorter row.
     */
    private static final int STRIDE = PPU.SCREEN_WIDTH;

    private static final double TWO_PI = 2 * Math.PI;

    private CRTScreen() {
    }

    /**
     * Puts a frame on the tube.
     * <p>
     * Nearest neighbour rather than anything smoother, for the reason the rest of this emulator
     * magnifies that way: a blurred NES picture looks worse than a blocky one, and the bend is
     * close enough to a rigid shift that a smoother fetch would buy nothing but the blur.
     *
     * @param colours  the frame already in colour, {@code PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT}
     *                 packed RGB. Read, never kept.
     * @param crop     which rectangle of the frame to show.
     * @param out      {@code width * height} packed RGB, written. The caller's, so that sixty
     *                 frames a second cost no allocation.
     * @param width    how wide the picture is being drawn.
     * @param height   how tall, which together with the crop's own height is what says how many
     *                 rows a line gets and so how deep the mask can go.
     * @param strength how dark the gaps between the lines go.
     * @param warp     whether the glass is curved.
     */
    public static void draw(
            final int[] colours,
            final Crop crop,
            final int[] out,
            final int width,
            final int height,
            final FilterStrength strength,
            final boolean warp) {

        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException(
                    "a picture is at least one pixel each way, not " + width + "x" + height);
        }

        // Said here rather than left to the first row that runs off the end, because the caller
        // that gets this wrong is a window that has been resized and has not rebuilt its buffer --
        // and the picture it would draw before the throw is half of the last size.
        if (out.length < width * height) {
            throw new IllegalArgumentException(
                    "a " + width + "x" + height + " picture needs " + width * height
                            + " pixels to go in, and there are " + out.length);
        }

        var table = table(height / (double) crop.height(), strength);

        if (warp) {
            bent(colours, crop, out, width, height, table);
        } else {
            flat(colours, crop, out, width, height, table);
        }
    }

    /**
     * A picture on flat glass, which is every row reading the same columns and every column reading
     * the same row.
     * <p>
     * That independence is the whole of why this is a loop of its own rather than {@link #bent}
     * with the bend left out: where a pixel reads from and how much light it keeps both collapse to
     * one table lookup each, and the inner line has no arithmetic left in it at all. It is what the
     * bend costs, written down.
     */
    private static void flat(
            final int[] colours,
            final Crop crop,
            final int[] out,
            final int width,
            final int height,
            final int[] table) {

        var lines = crop.height();
        var visible = crop.width();
        var columns = new int[width];

        for (var x = 0; x < width; x++) {
            columns[x] = crop.left() + Math.min((int) ((x + 0.5) * visible / width), visible - 1);
        }

        for (var y = 0; y < height; y++) {
            // Fractional: the whole number finds the line and what is left over says where between
            // two of them this row of the picture sits.
            var v = (y + 0.5) * lines / height;
            var source = (crop.top() + Math.min((int) v, lines - 1)) * STRIDE;
            var alpha = table[phase(v)];
            var row = y * width;

            for (var x = 0; x < width; x++) {
                out[row + x] = darken(colours[source + columns[x]], alpha);
            }
        }
    }

    /**
     * The same on curved glass, where neither of those holds: a row's columns depend on how far
     * down the picture it is, and a column's line on how far across.
     */
    private static void bent(
            final int[] colours,
            final Crop crop,
            final int[] out,
            final int width,
            final int height,
            final int[] table) {

        var lines = crop.height();
        var visible = crop.width();

        // How far each axis is bowed at each column. Only the horizontal one varies with the row,
        // so the vertical one is worked out once for the whole picture rather than per pixel.
        var across = new double[width];
        var bend = new double[width];

        for (var x = 0; x < width; x++) {
            var nx = (x + 0.5) * 2 / width - 1;

            across[x] = nx;
            bend[x] = 1 + CURVE * nx * nx;
        }

        for (var y = 0; y < height; y++) {
            var ny = (y + 0.5) * 2 / height - 1;
            var stretch = 1 + CURVE * ny * ny;
            var row = y * width;

            for (var x = 0; x < width; x++) {
                // Each axis is bowed by how far out the other one is, which is what puts the whole
                // of the bend in the corners: the middle of every edge is where it was.
                var sx = across[x] * stretch;
                var sy = ny * bend[x];

                if (sx < -1 || sx > 1 || sy < -1 || sy > 1) {
                    out[row + x] = 0xFF000000;
                    continue;
                }

                var v = (sy + 1) * 0.5 * lines;
                var source = (crop.top() + Math.min((int) v, lines - 1)) * STRIDE;
                var column =
                        crop.left() + Math.min((int) ((sx + 1) * 0.5 * visible), visible - 1);

                out[row + x] = darken(colours[source + column], table[phase(v)]);
            }
        }
    }

    /**
     * Which entry of the mask a point this far down the raster reads, out of the fraction of a line
     * it is at.
     * <p>
     * The nearest entry rather than the one below, and the wrap rather than a clamp, because the
     * profile is periodic and both of those are what keep it symmetrical: a magnification of four
     * puts two rows either side of the beam and they have to come out equal, which they do not if
     * every row is rounded the same way round.
     */
    private static int phase(final double v) {
        return (int) (Math.round((v - (int) v) * PHASES) % PHASES);
    }

    /**
     * How dark the raster is at each of {@link #PHASES} points across one line of it, as the alpha
     * of black laid over the picture.
     * <p>
     * An alpha rather than a multiplier because it is not only this that draws the mask: the
     * window's rewind marker and everything else it paints go through {@link java.awt.Graphics2D},
     * and compositing black at alpha {@code a} <em>is</em> multiplying by {@code 1 - a/255}. Naming
     * the same number both ways round would be two arithmetics to keep in step.
     * <p>
     * Each entry is the light missing from a whole output row rather than at a point, which is what
     * makes this work at a magnification that is not a whole number. Point sampling a periodic
     * profile at a period that does not divide is aliasing, and aliasing here is a slow moire
     * crawling up the picture as the window is resized.
     */
    private static int[] table(final double rowsPerLine, final FilterStrength strength) {
        var alphas = new int[PHASES];
        var depth = strength.depth()
                * Math.clamp(rowsPerLine - 1, 0, MINIMUM_ROWS_PER_LINE - 1);

        if (depth == 0) {
            return alphas;
        }

        // One output row, in lines. The window is centred on the phase rather than starting at it,
        // so that the entry for a phase is the light over the row that phase is the middle of.
        var row = 1 / rowsPerLine;

        for (var i = 0; i < PHASES; i++) {
            var centre = i / (double) PHASES;
            var missing = antiderivative(centre + row / 2) - antiderivative(centre - row / 2);

            alphas[i] = Math.clamp(Math.round(255 * depth * missing / row), 0, 255);
        }

        return alphas;
    }

    /**
     * The integral, from the top of a line's share of the raster to {@code u} lines down it, of how
     * much of the light is missing there.
     * <p>
     * The profile itself is {@code 0.5 - 0.5 sin(2*pi*u)}: nothing missing a quarter of the way
     * down, where the beam is, and all of it three quarters of the way down, where the other
     * field's line would have been.
     * <p>
     * <strong>A quarter of the way down rather than halfway is the one decision here that is not
     * arithmetic.</strong> A gap centred on the boundary between two lines is the prettier model
     * and it is useless: any even number of rows to a line splits it exactly in half, so the
     * commonest magnification of all -- two -- would come out with two identical rows and no
     * scanline anywhere. Putting the beam where the even field put it gives 2x a lit row and a dark
     * one, 4x two of each, and 3x a bright row, a middling one and a dark one.
     * <p>
     * A raised cosine rather than a hard edge because a beam has a width and a phosphor spreads,
     * and because a hard edge is the one profile whose integral over a fractional row is
     * discontinuous in the row's position. Its own gain is one half rather than one, which is where
     * the light goes: a mask at full depth takes half of it. That is the tube's arithmetic and not
     * a number chosen here.
     */
    private static double antiderivative(final double u) {
        return 0.5 * u + Math.cos(TWO_PI * u) / (2 * TWO_PI);
    }

    /**
     * Takes an alpha's worth of light out of one pixel, which is what compositing that much black
     * over it would do.
     * <p>
     * {@code (n * 257 + 257) >> 16} rather than {@code n / 255}, which it equals exactly for every
     * n a channel times a light can be -- 0 to 65025 -- and which matters because this is three
     * divisions on the innermost line of the filter. A million pixels a frame is a million
     * divisions saved, and the divide is the slowest instruction in the loop by an order of
     * magnitude.
     */
    private static int darken(final int colour, final int alpha) {
        if (alpha == 0) {
            return colour;
        }

        var light = 255 - alpha;

        return 0xFF000000
                | (((colour >> 16) & 0xFF) * light * 257 + 257 >> 16) << 16
                | (((colour >> 8) & 0xFF) * light * 257 + 257 >> 16) << 8
                | ((colour & 0xFF) * light * 257 + 257 >> 16);
    }
}
