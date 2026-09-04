package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning colour indices into a picture, which is the half of the job the chip does not do.
 * <p>
 * Nothing here needs a display: a {@link java.awt.image.BufferedImage} is memory with an opinion
 * about pixel formats, and the emulator's own window has always been tested the same way.
 */
class FrameRendererTests {
    private static final int[] PALETTE = Palettes.defaultPalette().colours();

    private static final double SQUARE = FrameRenderer.SQUARE_PIXELS;

    private static int[] frameOf(final int entry) {
        var frame = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];
        Arrays.fill(frame, entry);

        return frame;
    }

    /**
     * A frame whose every row holds its own row number, so that a crop is visible as an offset.
     */
    private static int[] rowNumberedFrame() {
        var frame = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];

        for (var y = 0; y < PPU.SCREEN_HEIGHT; y++) {
            Arrays.fill(frame, y * PPU.SCREEN_WIDTH, (y + 1) * PPU.SCREEN_WIDTH, y);
        }

        return frame;
    }

    @Test
    void aFrameIsColouredThroughTheGivenPalette() {
        var image = FrameRenderer.render(frameOf(0x21), PALETTE, SQUARE, Crop.TELEVISION, 1);

        assertEquals(PALETTE[0x21] & 0xFFFFFF, image.getRGB(0, 0) & 0xFFFFFF);
    }

    /**
     * The other overload, which takes a decoder in place of the palette. What it shares with the
     * palette one is everything below the colours -- the crop, the magnification, the size of the
     * picture -- and that is what is checked here; what the decoder itself does is
     * {@code NTSCFilterTests}.
     */
    @Test
    void aFilteredFrameIsCroppedAndMagnifiedTheSameWay() {
        var image = FrameRenderer.render(frameOf(0x21), new NTSCFilter(), 0, SQUARE, Crop.TELEVISION, 3);

        assertEquals(PPU.SCREEN_WIDTH * 3, image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT * 3, image.getHeight());

        var full = FrameRenderer.render(frameOf(0x21), new NTSCFilter(), 0, SQUARE, Crop.FULL_FRAME, 1);

        assertEquals(PPU.SCREEN_HEIGHT, full.getHeight());
    }

    /**
     * The point of the overload: the palette is not a parameter because it is not consulted.
     */
    @Test
    void aFilteredFrameIsNotColouredThroughThePalette() {
        var palette = FrameRenderer.render(frameOf(0x21), PALETTE, SQUARE, Crop.TELEVISION, 1).getRGB(0, 0);
        var filtered = FrameRenderer.render(frameOf(0x21), new NTSCFilter(), 0, SQUARE, Crop.TELEVISION, 1).getRGB(0, 0);

        org.junit.jupiter.api.Assertions.assertNotEquals(palette, filtered);
    }

    /**
     * The third overload, which is the palette again with a tube in front of it. The colours are
     * the palette's, unlike the decoder's, and what the tube changes is where the light goes.
     */
    @Test
    void aTubeColoursThroughThePaletteAndThenTakesTheLightOffTheGaps() {
        var image = FrameRenderer.render(
                frameOf(0x20), PALETTE, FilterStrength.MEDIUM, false, SQUARE, Crop.TELEVISION, 2);

        assertEquals(PPU.SCREEN_WIDTH * 2, image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT * 2, image.getHeight());

        var lit = image.getRGB(0, 0) & 0xFF;
        var dark = image.getRGB(0, 1) & 0xFF;
        var plain = FrameRenderer.render(frameOf(0x20), PALETTE, SQUARE, Crop.TELEVISION, 2).getRGB(0, 0) & 0xFF;

        assertTrue(lit < plain, "even a lit row loses a little: " + lit + " of " + plain);
        assertTrue(dark * 3 < lit * 2, "and the gap loses far more: " + dark + " against " + lit);
    }

    /**
     * At one row per line there is nowhere to put a gap, so the tube draws exactly what the palette
     * draws. The front ends refuse the combination; this says what happens to anything that does
     * not, which is nothing.
     */
    @Test
    void aTubeAtOneTimesIsThePaletteStraightThrough() {
        var plain = FrameRenderer.render(rowNumberedFrame(), PALETTE, SQUARE, Crop.TELEVISION, 1);
        var tube = FrameRenderer.render(
                rowNumberedFrame(), PALETTE, FilterStrength.STRONG, false, SQUARE, Crop.TELEVISION, 1);

        for (var y = 0; y < plain.getHeight(); y++) {
            assertEquals(plain.getRGB(0, y), tube.getRGB(0, y), "row " + y);
        }
    }

    /**
     * And the crop is the crop whatever is drawing it.
     */
    @Test
    void aTubeCropsAndMagnifiesTheSameWay() {
        var cropped = FrameRenderer.render(
                rowNumberedFrame(), PALETTE, FilterStrength.MEDIUM, true, SQUARE, Crop.TELEVISION, 2);
        var full = FrameRenderer.render(
                rowNumberedFrame(), PALETTE, FilterStrength.MEDIUM, true, SQUARE, Crop.FULL_FRAME, 2);

        assertEquals(FrameRenderer.VISIBLE_HEIGHT * 2, cropped.getHeight());
        assertEquals(PPU.SCREEN_HEIGHT * 2, full.getHeight());
    }

    /**
     * The emphasis bits sit above the colour index, so entry 0x21 and entry 0x61 are the same
     * colour with one of them dimmed. Getting this wrong would show up as a picture that ignored
     * $2001 entirely.
     */
    @Test
    void emphasisPicksTheDimmedCopyOfTheColour() {
        var plain = FrameRenderer.render(frameOf(0x21), PALETTE, SQUARE, Crop.TELEVISION, 1).getRGB(0, 0);
        var emphasised = FrameRenderer.render(frameOf(0x61), PALETTE, SQUARE, Crop.TELEVISION, 1).getRGB(0, 0);

        assertEquals(PALETTE[0x61] & 0xFFFFFF, emphasised & 0xFFFFFF);
        org.junit.jupiter.api.Assertions.assertNotEquals(plain, emphasised);
    }

    @Test
    void theCropTakesEightScanlinesFromEachEnd() {
        var image = FrameRenderer.render(rowNumberedFrame(), PALETTE, SQUARE, Crop.TELEVISION, 1);

        assertEquals(PPU.SCREEN_WIDTH, image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT, image.getHeight());
        assertEquals(
                PALETTE[FrameRenderer.OVERSCAN_TOP] & 0xFFFFFF,
                image.getRGB(0, 0) & 0xFFFFFF,
                "the top row of the picture is framebuffer row eight");
    }

    /**
     * A frame whose every column holds its own column number, so that the horizontal crop is
     * visible as an offset the way the vertical one is.
     */
    private static int[] columnNumberedFrame() {
        var frame = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];

        for (var i = 0; i < frame.length; i++) {
            frame[i] = i % PPU.SCREEN_WIDTH;
        }

        return frame;
    }

    @Test
    void theLeftEdgeCropTakesEightColumnsFromTheLeft() {
        var image = FrameRenderer.render(
                columnNumberedFrame(), PALETTE, SQUARE, Crop.TELEVISION.withoutLeftEdge(), 1);

        assertEquals(FrameRenderer.VISIBLE_WIDTH, image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT, image.getHeight());
        assertEquals(
                PALETTE[FrameRenderer.OVERSCAN_LEFT] & 0xFFFFFF,
                image.getRGB(0, 0) & 0xFFFFFF,
                "the left column of the picture is framebuffer column eight");
    }

    /**
     * And nothing comes off the other end, which is the whole of why the crop is not symmetrical:
     * the chip's clipping window is at the left and there is nothing at the right to hide.
     */
    @Test
    void theLeftEdgeCropTakesNothingFromTheRight() {
        var image = FrameRenderer.render(
                columnNumberedFrame(), PALETTE, SQUARE, Crop.TELEVISION.withoutLeftEdge(), 1);

        assertEquals(
                PALETTE[PPU.SCREEN_WIDTH - 1] & 0xFFFFFF,
                image.getRGB(image.getWidth() - 1, 0) & 0xFFFFFF);
    }

    /**
     * The two crops are independent questions, so a full frame with the left edge dropped is 248
     * columns of all 240 lines rather than either one of them winning.
     */
    @Test
    void theTwoCropsCompose() {
        var image = FrameRenderer.render(
                columnNumberedFrame(), PALETTE, SQUARE, Crop.FULL_FRAME.withoutLeftEdge(), 1);

        assertEquals(FrameRenderer.VISIBLE_WIDTH, image.getWidth());
        assertEquals(PPU.SCREEN_HEIGHT, image.getHeight());
    }

    /**
     * And the left edge goes whatever is drawing, the way the scanlines do.
     */
    @Test
    void aTubeAndADecoderDropTheLeftEdgeTheSameWay() {
        var tube = FrameRenderer.render(
                columnNumberedFrame(),
                PALETTE,
                FilterStrength.MEDIUM,
                false,
                SQUARE,
                Crop.TELEVISION.withoutLeftEdge(),
                2);
        var decoded = FrameRenderer.render(
                columnNumberedFrame(),
                new NTSCFilter(),
                0,
                SQUARE,
                Crop.TELEVISION.withoutLeftEdge(),
                2);

        assertEquals(FrameRenderer.VISIBLE_WIDTH * 2, tube.getWidth());
        assertEquals(FrameRenderer.VISIBLE_WIDTH * 2, decoded.getWidth());
    }

    @Test
    void theFullFrameKeepsAllTwoHundredAndFortyLines() {
        var image = FrameRenderer.render(rowNumberedFrame(), PALETTE, SQUARE, Crop.FULL_FRAME, 1);

        assertEquals(PPU.SCREEN_HEIGHT, image.getHeight());
        assertEquals(PALETTE[0] & 0xFFFFFF, image.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void scalingRepeatsEveryPixel() {
        var image = FrameRenderer.render(rowNumberedFrame(), PALETTE, SQUARE, Crop.TELEVISION, 3);

        assertEquals(PPU.SCREEN_WIDTH * 3, image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT * 3, image.getHeight());

        // Framebuffer row 8 becomes picture rows 0, 1 and 2, and each of its pixels becomes three.
        var expected = PALETTE[FrameRenderer.OVERSCAN_TOP] & 0xFFFFFF;

        for (var y = 0; y < 3; y++) {
            for (var x = 0; x < 3; x++) {
                assertEquals(expected, image.getRGB(x, y) & 0xFFFFFF);
            }
        }

        assertEquals(
                PALETTE[FrameRenderer.OVERSCAN_TOP + 1] & 0xFFFFFF,
                image.getRGB(0, 3) & 0xFFFFFF,
                "the fourth row of the picture is the next framebuffer row");
    }

    @Test
    void aTelevisionsPixelsMakeThePictureWiderAndLeaveItAsTall() {
        var square = FrameRenderer.render(frameOf(0x21), PALETTE, SQUARE, Crop.TELEVISION, 2);
        var television = FrameRenderer.render(
                frameOf(0x21), PALETTE, Region.NTSC.pixelAspect(), Crop.TELEVISION, 2);

        assertEquals(512, square.getWidth());
        assertEquals(585, television.getWidth(), "512 stretched by 8:7, rounded");
        assertEquals(square.getHeight(), television.getHeight(), "nothing happens to the height");
    }

    /**
     * The stretch is a stretch and not a crop or a resample: every column the chip drew is still
     * there, in order, and the ones that are drawn twice are what makes up the extra width.
     */
    @Test
    void aStretchedPictureHoldsEveryColumnInOrder() {
        // Coloured through a table that hands each index straight back, so that a pixel of the
        // picture says which column of the framebuffer it came from. A measured palette could not
        // answer that: two entries of it are allowed to be the same colour.
        var identity = new int[512];

        for (var i = 0; i < identity.length; i++) {
            identity[i] = i;
        }

        var image = FrameRenderer.render(
                columnNumberedFrame(), identity, Region.NTSC.pixelAspect(), Crop.TELEVISION, 1);

        assertEquals(293, image.getWidth());

        var seen = 0;

        for (var x = 0; x < image.getWidth(); x++) {
            var column = image.getRGB(x, 0) & 0xFFFFFF;

            assertTrue(column == seen || column == seen + 1,
                    "column " + x + " of the picture is framebuffer column " + column
                            + ", after " + seen);

            seen = column;
        }

        assertEquals(PPU.SCREEN_WIDTH - 1, seen, "and the last one is the last one");
    }

    /**
     * The shape belongs to a pixel and the crop decides how many there are, so the two compose by
     * multiplying: 248 columns at 8:7 rather than 256 stretched and then cut.
     */
    @Test
    void theShapeStretchesWhatTheCropLeft() {
        var whole = FrameRenderer.render(
                frameOf(0x21), PALETTE, Region.NTSC.pixelAspect(), Crop.TELEVISION, 1);
        var clipped = FrameRenderer.render(
                frameOf(0x21),
                PALETTE,
                Region.NTSC.pixelAspect(),
                Crop.TELEVISION.withoutLeftEdge(),
                1);

        assertEquals(293, whole.getWidth(), "256 at 8:7");
        assertEquals(283, clipped.getWidth(), "248 at 8:7, not 293 with eight taken off");
    }

    /**
     * The tube gets the shape for nothing, because it was already being asked for a picture of a
     * given size rather than for a magnification.
     */
    @Test
    void aTubeDrawsOnATelevisionsPixelsToo() {
        var image = FrameRenderer.render(
                frameOf(0x20),
                PALETTE,
                FilterStrength.MEDIUM,
                false,
                Region.NTSC.pixelAspect(),
                Crop.TELEVISION,
                2);

        assertEquals(585, image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT * 2, image.getHeight());

        // And the mask is still the mask: what the shape moved is the columns, not the rows.
        assertTrue((image.getRGB(0, 1) & 0xFF) < (image.getRGB(0, 0) & 0xFF));
    }

    @Test
    void aDecodedFrameTakesTheSameShape() {
        var image = FrameRenderer.render(
                frameOf(0x21), new NTSCFilter(), 0, Region.PAL.pixelAspect(), Crop.TELEVISION, 2);

        assertEquals(
                FrameRenderer.widthFor(Crop.TELEVISION, 2, Region.PAL.pixelAspect()),
                image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT * 2, image.getHeight());
    }

    /**
     * The picture the two consoles' televisions drew is a different width, because the two chips
     * put a different number of pixels into the same line.
     */
    @Test
    void theTwoConsolesPixelsAreDifferentShapes() {
        assertEquals(1170, FrameRenderer.widthFor(Crop.TELEVISION, 4, Region.NTSC.pixelAspect()));
        assertEquals(1419, FrameRenderer.widthFor(Crop.TELEVISION, 4, Region.PAL.pixelAspect()));
        assertEquals(1024, FrameRenderer.widthFor(Crop.TELEVISION, 4, FrameRenderer.SQUARE_PIXELS));
    }

    @Test
    void aPixelWithNoShapeIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameRenderer.render(frameOf(0), PALETTE, 0, Crop.TELEVISION, 1));
        assertThrows(IllegalArgumentException.class,
                () -> FrameRenderer.render(frameOf(0), PALETTE, -1, Crop.TELEVISION, 1));
        assertThrows(IllegalArgumentException.class,
                () -> FrameRenderer.render(frameOf(0), PALETTE, Double.NaN, Crop.TELEVISION, 1));
    }

    @Test
    void aScaleOutsideWhatItWillDoIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameRenderer.render(frameOf(0), PALETTE, SQUARE, Crop.TELEVISION, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FrameRenderer.render(frameOf(0), PALETTE, SQUARE, Crop.TELEVISION, FrameRenderer.MAX_SCALE + 1));
    }
}
