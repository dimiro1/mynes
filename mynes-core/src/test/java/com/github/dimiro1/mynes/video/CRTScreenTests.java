package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The screen the picture ends up on.
 * <p>
 * Every assertion here is made against a flat white frame, which is the one input that says
 * something about the screen rather than about the picture: whatever comes out is the light the
 * screen took away and where it took it from.
 */
class CRTScreenTests {
    private static final int WIDTH = PPU.SCREEN_WIDTH;
    private static final int LINES = FrameRenderer.VISIBLE_HEIGHT;
    private static final int TOP = FrameRenderer.OVERSCAN_TOP;
    private static final Crop CROP = Crop.TELEVISION;

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;

    /**
     * A frame with every pixel at full white, so a row of the answer is the mask and nothing else.
     */
    private static int[] white() {
        var frame = new int[WIDTH * PPU.SCREEN_HEIGHT];
        java.util.Arrays.fill(frame, WHITE);

        return frame;
    }

    /**
     * Draws a white frame and hands back the green channel of the first pixel of each row, which
     * for white is how much light the row was left with out of 255.
     */
    private static int[] rows(
            final int scale, final FilterStrength strength, final boolean warp) {
        return column(0, scale, strength, warp);
    }

    private static int[] column(
            final int x, final int scale, final FilterStrength strength, final boolean warp) {
        var width = WIDTH * scale;
        var height = LINES * scale;
        var out = new int[width * height];

        CRTScreen.draw(white(), CROP, out, width, height, strength, warp);

        var light = new int[height];

        for (var y = 0; y < height; y++) {
            light[y] = (out[y * width + x] >> 8) & 0xFF;
        }

        return light;
    }

    /**
     * The heart of it. Two rows to a line is the commonest magnification there is, and a mask
     * centred between the lines instead of on one would split it exactly in half and come out with
     * two identical rows.
     */
    @Test
    void atTwoRowsALineOneIsLitAndTheOtherIsNot() {
        var light = rows(2, FilterStrength.MEDIUM, false);

        for (var y = 0; y + 1 < light.length; y += 2) {
            assertTrue(light[y] > light[y + 1] + 40,
                    "row " + y + " at " + light[y] + " should be well clear of "
                            + light[y + 1]);
        }
    }

    /**
     * And four rows a line is two of each, since half the raster is beam and half is the gap the
     * other field would have used.
     */
    @Test
    void atFourRowsALineTwoAreLitAndTwoAreNot() {
        var light = rows(4, FilterStrength.MEDIUM, false);

        assertEquals(light[0], light[1], "the two rows the beam is on");
        assertEquals(light[2], light[3], "and the two it is not");
        assertTrue(light[0] > light[2] + 40, light[0] + " against " + light[2]);
    }

    /**
     * Three rows a line has no half to give each of them, so what it has instead is a gradient --
     * which is the profile being read at three points rather than a pattern being repeated.
     */
    @Test
    void atThreeRowsALineTheRowsRunBrightToDark() {
        var light = rows(3, FilterStrength.MEDIUM, false);

        assertTrue(light[0] > light[1], light[0] + " then " + light[1]);
        assertTrue(light[1] > light[2], light[1] + " then " + light[2]);
    }

    /**
     * A stronger setting digs the gaps deeper without moving the lit rows anywhere near as far,
     * which is what says the strength is the depth of the mask rather than the brightness of the
     * picture.
     */
    @Test
    void aStrongerSettingDigsDeeperGaps() {
        var low = rows(4, FilterStrength.LOW, false);
        var medium = rows(4, FilterStrength.MEDIUM, false);
        var strong = rows(4, FilterStrength.STRONG, false);

        assertTrue(low[2] > medium[2], low[2] + " then " + medium[2]);
        assertTrue(medium[2] > strong[2], medium[2] + " then " + strong[2]);

        // The lit rows move too -- the profile is a curve rather than a pair of levels, so a row
        // in the light still has a little of the gap in it -- but nothing like as far.
        assertTrue(low[0] - strong[0] < (low[2] - strong[2]) / 3,
                "the lit rows should hardly move: " + low[0] + " to " + strong[0]);
    }

    /**
     * The numbers {@link FilterStrength} quotes, which are quoted at two rows to a line because
     * that is where somebody will be looking at them. They are not the depth itself: a row of the
     * picture is a slice of the raster half a line thick, so even the dark one has some of the
     * light either side of it in it.
     */
    @Test
    void aTwoTimesPictureKeepsTheLightTheStrengthNames() {
        assertKept(FilterStrength.LOW, 95, 75);
        assertKept(FilterStrength.MEDIUM, 90, 55);
        assertKept(FilterStrength.STRONG, 85, 30);
    }

    private static void assertKept(
            final FilterStrength strength, final int lit, final int dark) {
        var light = rows(2, strength, false);

        assertEquals(lit, percent(light[0]), 1, strength + "'s lit row");
        assertEquals(dark, percent(light[1]), 1, strength + "'s dark row");
    }

    private static int percent(final int light) {
        return Math.round(light * 100f / 255);
    }

    /**
     * A gap needs a row to be in. At one row per line there is not one, so rather than taking the
     * same light off every row -- a dimmer switch wearing a television's clothes -- the mask fades
     * to nothing and the picture is the picture.
     */
    @Test
    void atOneRowALineThereIsNoRoomForAGapAndNothingIsTaken() {
        for (var strength : FilterStrength.values()) {
            for (var value : rows(1, strength, false)) {
                assertEquals(255, value, "nothing to take at 1x, and " + strength + " took some");
            }
        }
    }

    /**
     * The claim the whole table exists for: a magnification that is not a whole number is a
     * magnification a window gets by being dragged, and the mask has to be the same mask there.
     * Point sampling a periodic profile at a period that does not divide is aliasing, and aliasing
     * here is a moire crawling up the picture -- so what is checked is that the light lost overall
     * is the same at a fraction as at a whole number, which is what an integrated window gives and
     * a sampled one does not.
     */
    @Test
    void aFractionalMagnificationLosesTheSameLightAsAWholeOne() {
        assertEquals(averageLight(2.0), averageLight(2.5), 3.0);
        assertEquals(averageLight(2.0), averageLight(3.7), 3.0);
        assertEquals(averageLight(4.0), averageLight(4.3), 3.0);
    }

    private static double averageLight(final double rowsPerLine) {
        var width = WIDTH;
        var height = (int) Math.round(LINES * rowsPerLine);
        var out = new int[width * height];

        CRTScreen.draw(white(), CROP, out, width, height, FilterStrength.MEDIUM, false);

        var total = 0L;

        for (var y = 0; y < height; y++) {
            total += (out[y * width] >> 8) & 0xFF;
        }

        return total / (double) height;
    }

    /**
     * Bending the glass cuts the corners off and leaves the middle of every edge where it was,
     * which is the whole of what "warp the corners" means.
     */
    @Test
    void theGlassTakesTheCornersAndNothingInTheMiddleOfAnEdge() {
        var width = WIDTH * 3;
        var height = LINES * 3;
        var out = new int[width * height];

        CRTScreen.draw(white(), CROP, out, width, height, FilterStrength.MEDIUM, true);

        assertEquals(BLACK, out[0], "the top left corner is off the tube");
        assertEquals(BLACK, out[width - 1], "and the top right");
        assertEquals(BLACK, out[(height - 1) * width], "and the bottom left");
        assertEquals(BLACK, out[height * width - 1], "and the bottom right");

        assertNotEquals(BLACK, out[width / 2], "the middle of the top edge stays");
        assertNotEquals(BLACK, out[(height / 2) * width], "and the middle of the left edge");
        assertNotEquals(BLACK, out[(height / 2) * width + width / 2], "and the middle");
    }

    /**
     * And it moves the picture rather than only masking it: a straight edge drawn down the frame
     * comes out at a different column at the top than in the middle. That is the difference between
     * a bend and a vignette.
     */
    @Test
    void theGlassBendsWhatIsDrawnOnItRatherThanJustDarkeningTheCorners() {
        var frame = white();

        // A black column an eighth of the way in, straight from top to bottom.
        for (var y = 0; y < PPU.SCREEN_HEIGHT; y++) {
            frame[y * WIDTH + WIDTH / 8] = BLACK;
        }

        var scale = 4;
        var width = WIDTH * scale;
        var height = LINES * scale;
        var out = new int[width * height];

        CRTScreen.draw(frame, CROP, out, width, height, FilterStrength.MEDIUM, true);

        assertNotEquals(edgeAt(out, width, height / 2), edgeAt(out, width, height / 12),
                "the same straight line should not land on the same column at both heights");

        var straight = new int[width * height];
        CRTScreen.draw(frame, CROP, straight, width, height, FilterStrength.MEDIUM, false);

        assertEquals(edgeAt(straight, width, height / 2), edgeAt(straight, width, height / 12),
                "and it should land on the same column at both heights when it is not bent");
    }

    /**
     * Which column of a row the black line is on, or -1.
     */
    private static int edgeAt(final int[] picture, final int width, final int y) {
        for (var x = 1; x < width; x++) {
            if ((picture[y * width + x] & 0xFFFFFF) == 0
                    && (picture[y * width + x - 1] & 0xFFFFFF) != 0) {
                return x;
            }
        }

        return -1;
    }

    /**
     * A picture off the tube is black rather than whatever the edge pixel happened to be, since the
     * alternative is a column of colour smeared into the corner out of nowhere.
     */
    @Test
    void whatFallsOffTheGlassIsBlackRatherThanSmeared() {
        var frame = new int[WIDTH * PPU.SCREEN_HEIGHT];
        java.util.Arrays.fill(frame, 0xFFFF0000);

        var width = WIDTH * 3;
        var height = LINES * 3;
        var out = new int[width * height];

        CRTScreen.draw(frame, CROP, out, width, height, FilterStrength.MEDIUM, true);

        assertEquals(BLACK, out[0]);
        assertEquals(BLACK, out[1]);
    }

    /**
     * Nothing about the machine is a fraction, so nothing about this should be either: two draws of
     * the same frame at the same size are the same bytes.
     */
    @Test
    void theSameFrameGoesOnTheTubeTheSameWayEveryTime() {
        var first = column(7, 3, FilterStrength.MEDIUM, true);
        var second = column(7, 3, FilterStrength.MEDIUM, true);

        org.junit.jupiter.api.Assertions.assertArrayEquals(first, second);
    }

    @Test
    void aPictureWithNoRoomInItIsRefusedRatherThanDrawnWrong() {
        var out = new int[16];

        assertThrows(IllegalArgumentException.class, () -> CRTScreen.draw(
                white(), CROP, out, 0, 4, FilterStrength.MEDIUM, false));

        assertThrows(IllegalArgumentException.class, () -> CRTScreen.draw(
                white(), CROP, out, 5, 4, FilterStrength.MEDIUM, false),
                "and a buffer too small for the picture, which is a window that has been resized");

        // A rectangle that is not on a frame is refused where it is named rather than where it is
        // used, which is the point of its being a type.
        assertThrows(IllegalArgumentException.class, () -> new Crop(TOP, 0, 0, WIDTH));
        assertThrows(IllegalArgumentException.class,
                () -> new Crop(8, 0, PPU.SCREEN_HEIGHT, WIDTH));
        assertThrows(IllegalArgumentException.class, () -> new Crop(TOP, 8, LINES, WIDTH));
    }
}
