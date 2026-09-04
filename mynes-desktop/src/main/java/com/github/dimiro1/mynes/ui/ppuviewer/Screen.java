package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.video.FrameRenderer;
import org.jetbrains.annotations.Nullable;

import java.awt.Color;
import java.awt.Graphics2D;

/**
 * The picture the chip is drawing, put behind the two windows that answer "where".
 * <p>
 * A viewer over OAM or over palette RAM can say what is in a byte perfectly and still leave the
 * only question worth asking unanswered, which is where on the screen that byte lands. So both of
 * them draw the frame underneath and take the brightness off everything the question is not about.
 * Dimming rather than outlining, because the regions are not always rectangles a box could go
 * round: a background palette's share of the screen is whatever shape the attribute bytes make.
 * <p>
 * <b>All 240 lines of it, unlike everywhere else in the front end</b>, which is why the shading at
 * the top and the bottom is here too. The window draws the 224 a television left outside its bezel,
 * and a debug picture that quietly did the same would hide a sprite parked at Y=$E8 -- which is
 * exactly the kind of sprite somebody comes to these windows looking for. So the extra sixteen lines
 * stay and are marked instead, which also stops the picture being seven per cent taller than the one
 * in the game window for no visible reason.
 * <p>
 * The frame is read while the emulation thread is drawing into it, deliberately unsynchronised
 * exactly as the rest of this package is. An {@code int} read cannot tear, so the worst case is a
 * picture with the top of one frame and the bottom of the one before it -- which is what a
 * television showed anyway.
 */
final class Screen {
    /**
     * How much light a pixel keeps when it is not what is being asked about. Low enough that a lit
     * region reads as lit at a glance, high enough that the picture underneath is still a picture
     * rather than a silhouette -- knowing <em>where</em> in the level a sprite is needs the level.
     */
    private static final int DIM_PERCENT = 30;

    /**
     * How much darker the scanlines behind a television's bezel are drawn than the ones in front of
     * it. Enough to read as a border at a glance, not enough to hide a sprite sitting in it.
     */
    private static final Color HIDDEN = new Color(0, 0, 0, 110);

    /**
     * The frame, coloured.
     *
     * @param into    a raster of {@link PPU#SCREEN_WIDTH} by {@link PPU#SCREEN_HEIGHT} packed ARGB.
     * @param ppu     the machine, read without stopping it.
     * @param colours 512 packed ARGB entries indexed {@code emphasis << 6 | entry}, which is what
     *                {@code NESPalette.colours()} hands out and what the framebuffer indexes.
     */
    static void draw(final int[] into, final PPU ppu, final int[] colours) {
        var frame = ppu.getFrameBuffer();

        for (var i = 0; i < into.length; i++) {
            into[i] = colours[frame[i] & 0x1FF];
        }
    }

    /**
     * The same frame with the light taken off everything that is not being asked about.
     *
     * @param lit which pixels keep their brightness. <b>Null dims the whole picture</b>, which is
     *            what a window that draws its own answer over the top wants.
     */
    static void drawDimmed(
            final int[] into,
            final PPU ppu,
            final int[] colours,
            final @Nullable boolean[] lit) {

        var frame = ppu.getFrameBuffer();

        for (var i = 0; i < into.length; i++) {
            var colour = colours[frame[i] & 0x1FF];

            into[i] = lit != null && lit[i] ? colour : dim(colour);
        }
    }

    /**
     * Shades the scanlines a television hid, over whatever has been drawn on them.
     * <p>
     * Over rather than instead: a sprite up there is still drawn, and still visible through this,
     * because "it is on the screen and the player cannot see it" is an answer and "it is not on the
     * screen" is a different one.
     *
     * @param left  where the picture's left edge is in {@code g2}'s coordinates.
     * @param top   where its top edge is.
     * @param scale how many times magnified the picture is drawn.
     */
    static void paintOverscan(
            final Graphics2D g2, final int left, final int top, final int scale) {

        g2.setColor(HIDDEN);
        g2.fillRect(
                left, top, PPU.SCREEN_WIDTH * scale, FrameRenderer.OVERSCAN_TOP * scale);
        g2.fillRect(
                left,
                top + FrameRenderer.VISIBLE_BOTTOM * scale,
                PPU.SCREEN_WIDTH * scale,
                FrameRenderer.OVERSCAN_BOTTOM * scale);
    }

    private static int dim(final int argb) {
        return 0xFF000000
                | (((argb >> 16) & 0xFF) * DIM_PERCENT / 100) << 16
                | (((argb >> 8) & 0xFF) * DIM_PERCENT / 100) << 8
                | (argb & 0xFF) * DIM_PERCENT / 100;
    }

    private Screen() {
    }
}
