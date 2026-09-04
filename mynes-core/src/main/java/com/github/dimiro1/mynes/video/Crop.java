package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;

/**
 * Which rectangle of a frame is the picture.
 * <p>
 * The chip draws 256 by 240 and nobody has ever looked at all of it. A television hid the eight
 * scanlines at either end behind its bezel, and the games know: partial tiles, scroll seams and
 * the pixel or two of the following frame that the emulation loop lets into scanline 0 all live up
 * there. That is {@link #TELEVISION}, and it is what everything draws unless somebody asks for
 * {@link #FULL_FRAME} on purpose.
 * <p>
 * <strong>The eight columns at the left are a separate question with a separate answer</strong>,
 * which is why they are a method rather than a third constant. What is up at the top is rubbish the
 * game did not mean to draw; what is down the left edge is something the <em>chip</em> does, and
 * asked for: $2001 has a bit that stops the background being drawn in the leftmost eight pixels,
 * and a game that scrolls horizontally sets it, because that is where the tile it is part way
 * through would show. The 2C02 fills the gap with the backdrop colour rather than with black, so
 * what a game gets for it is an eight pixel stripe of sky down the side of everything, status bar
 * included. Super Mario Bros. 3 is the one everybody has seen.
 * <p>
 * So it is a stripe of frame that is exactly as real as the picture and exactly as unwanted, and
 * the answer is the one the top and the bottom get -- stop looking at it. There is nothing to take
 * off the right: the clipping window is at the left because that is the end a fine scroll shifts
 * from, and eight columns cut off the other side to make the numbers symmetrical would be picture
 * thrown away to no purpose.
 * <p>
 * <strong>None of this is on the comparability checklist.</strong> Like the video filters and
 * unlike a hack, a crop is a fact about what somebody looked at rather than about what the machine
 * did: {@code FrameAnalysis} measures the frame the chip emitted, so two runs that disagree here
 * are still two measurements of one thing and only their PNGs differ.
 *
 * @param top    the first scanline of the frame that is picture.
 * @param left   the first column of it that is.
 * @param height how many scanlines, from {@code top} down.
 * @param width  how many columns, from {@code left} across.
 */
public record Crop(int top, int left, int height, int width) {

    /**
     * All of it, which is what {@code --full-frame} and Settings &gt; Show Overscan ask for.
     */
    public static final Crop FULL_FRAME =
            new Crop(0, 0, PPU.SCREEN_HEIGHT, PPU.SCREEN_WIDTH);

    /**
     * As much of it as a television showed, which is what everything draws by default.
     */
    public static final Crop TELEVISION = new Crop(
            FrameRenderer.OVERSCAN_TOP,
            0,
            FrameRenderer.VISIBLE_HEIGHT,
            PPU.SCREEN_WIDTH);

    public Crop {
        if (height <= 0 || top < 0 || top + height > PPU.SCREEN_HEIGHT) {
            throw new IllegalArgumentException(
                    "lines " + top + " to " + (top + height) + " are not on a frame");
        }

        if (width <= 0 || left < 0 || left + width > PPU.SCREEN_WIDTH) {
            throw new IllegalArgumentException(
                    "columns " + left + " to " + (left + width) + " are not on a frame");
        }
    }

    /**
     * The same picture with the eight columns the chip clips down its left edge left out.
     * <p>
     * Independent of how many scanlines there are, so it composes with either constant above: a
     * full frame with the left edge dropped is a coherent thing to ask for -- everything the chip
     * drew except the stripe it drew on purpose and nobody wants -- rather than a contradiction to
     * be resolved one way or the other.
     */
    public Crop withoutLeftEdge() {
        return new Crop(
                top, FrameRenderer.OVERSCAN_LEFT, height, FrameRenderer.VISIBLE_WIDTH);
    }

    /**
     * The first scanline below the picture, and the first column right of it.
     */
    public int bottom() {
        return top + height;
    }

    public int right() {
        return left + width;
    }
}
