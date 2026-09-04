package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.FrameRenderer;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the half of the picture that used to be the PPU's job: turning the colour indices in
 * the framebuffer into colours on screen.
 * <p>
 * Everything here goes through {@link java.awt.Graphics}, painting the component into an image of
 * exactly the size it draws 1:1 at, which is also the only way to see what it would put on screen
 * without reaching inside it.
 */
class ScreenComponentTests {
    /**
     * The visible picture, taken from the component's own definition of it rather than restated:
     * painting at this size maps a screen pixel to a framebuffer pixel eight rows down.
     */
    private static final int VISIBLE_HEIGHT = FrameRenderer.VISIBLE_HEIGHT;
    private static final int OVERSCAN_TOP = FrameRenderer.OVERSCAN_TOP;
    private static final int OVERSCAN_LEFT = FrameRenderer.OVERSCAN_LEFT;
    private static final int VISIBLE_WIDTH = FrameRenderer.VISIBLE_WIDTH;

    /**
     * Paints the component at 1:1, which is the only way to see what it would put on screen without
     * reaching inside it.
     */
    private static BufferedImage paint(final ScreenComponent screen) {
        return paint(screen, 1);
    }

    /**
     * The same, magnified -- which the tube has an opinion about and the other two filters do not,
     * since a scanline lives between two rows of the picture on screen.
     */
    private static BufferedImage paint(final ScreenComponent screen, final int scale) {
        return paint(screen, PPU.SCREEN_WIDTH * scale, VISIBLE_HEIGHT * scale);
    }

    /**
     * And at whatever size, which is what the overscan wants: a component showing all 240 lines
     * into a window 224 tall would fit them rather than draw them, and fitting is not the question.
     */
    private static BufferedImage paint(
            final ScreenComponent screen, final int width, final int height) {
        var target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        screen.setSize(width, height);

        var g = target.createGraphics();
        try {
            screen.paintComponent(g);
        } finally {
            g.dispose();
        }

        return target;
    }

    /**
     * The snapshot, insisted upon. It answers null only for a component that has never been given
     * a frame -- which every caller here has done first, so a null is the test's own setup having
     * gone wrong, and saying that is better than an NPE three lines further on.
     */
    private static BufferedImage snapshotOf(final ScreenComponent screen, final ScreenScale scale) {
        var image = screen.snapshot(scale);

        assertNotNull(image, "the component was given a frame and still drew nothing");

        return image;
    }

    /**
     * Paints the component at 1:1 and reads a pixel back, in framebuffer coordinates.
     */
    private static int painted(final ScreenComponent screen, final int x, final int y) {
        return paint(screen).getRGB(x, y - OVERSCAN_TOP) & 0xFFFFFF;
    }

    /**
     * How many pixels of a painted region are not the flat colour the frame was filled with, which
     * is how anything drawn <em>over</em> the picture is found without naming where it is.
     */
    private static int drawnOver(
            final BufferedImage image,
            final int left, final int top, final int right, final int bottom,
            final int colour) {
        var count = 0;

        for (var y = top; y < bottom; y++) {
            for (var x = left; x < right; x++) {
                if ((image.getRGB(x, y) & 0xFFFFFF) != colour) {
                    count++;
                }
            }
        }

        return count;
    }

    private static int[] frameOf(final int entry) {
        var frame = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];
        Arrays.fill(frame, entry);

        return frame;
    }

    @Test
    void aFrameIsColouredThroughTheChosenPalette() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x21), 0);

        assertEquals(
                Palettes.defaultPalette().colour(0x21) & 0xFFFFFF,
                painted(screen, 0, OVERSCAN_TOP));
    }

    @Test
    void emphasisPicksTheDimmedCopyOfTheColour() {
        var screen = new ScreenComponent();

        // What the PPU writes with all three emphasis bits set: entry 0x21, emphasis 7.
        screen.present(frameOf((7 << 6) | 0x21), 0);

        assertEquals(
                Palettes.defaultPalette().colours()[(7 << 6) | 0x21] & 0xFFFFFF,
                painted(screen, 0, OVERSCAN_TOP));
    }

    @Test
    void changingThePaletteRecoloursTheFrameAlreadyHere() {
        var screen = new ScreenComponent();
        var classic = Palettes.byId("nes-classic");

        screen.present(frameOf(0x21), 0);
        screen.setPalette(classic);

        // No new frame arrives, which is the case that matters: with the emulator paused there is
        // not going to be one, and the preview has to work anyway.
        assertEquals(classic.colour(0x21) & 0xFFFFFF, painted(screen, 0, OVERSCAN_TOP));
    }

    /**
     * The same property the palette relies on, for the same reason: with the emulator paused there
     * is no next frame to apply the change to, and switching the filter has to work anyway.
     */
    @Test
    void switchingTheFilterRecoloursTheFrameAlreadyHere() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x21), 0);

        var throughThePalette = painted(screen, 0, OVERSCAN_TOP);

        screen.setVideoFilter(VideoFilter.NTSC, FilterStrength.defaultStrength(), false);

        var decoded = painted(screen, 0, OVERSCAN_TOP);

        assertNotEquals(throughThePalette, decoded, "the decoder is not the palette");

        screen.setVideoFilter(VideoFilter.NONE, FilterStrength.defaultStrength(), false);

        assertEquals(throughThePalette, painted(screen, 0, OVERSCAN_TOP), "and back again");
    }

    /**
     * The strength is the same property again, and for the same reason: the way to see what it does
     * is to take one frame at two settings, which wants the picture already here to be redrawn.
     */
    @Test
    void changingTheStrengthRecoloursTheFrameAlreadyHere() {
        var screen = new ScreenComponent();

        // A vertical edge, since a flat field decodes the same at every strength on purpose and
        // would say nothing about whether the setting arrived.
        var frame = frameOf(0x0F);
        for (var y = 0; y < PPU.SCREEN_HEIGHT; y++) {
            frame[y * PPU.SCREEN_WIDTH + 1] = 0x30;
        }

        screen.present(frame, 0);
        screen.setVideoFilter(VideoFilter.NTSC, FilterStrength.STRONG, false);

        var soft = painted(screen, 2, OVERSCAN_TOP);

        screen.setVideoFilter(VideoFilter.NTSC, FilterStrength.LOW, false);

        assertNotEquals(soft, painted(screen, 2, OVERSCAN_TOP),
                "less of the pixel next door should reach this one");
    }

    /**
     * A screenshot is the picture, so it is whatever is on screen rather than whatever the palette
     * would have made of it.
     */
    @Test
    void aSnapshotIsTakenThroughTheFilterToo() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x21), 0);

        var throughThePalette = snapshotOf(screen, ScreenScale.ONE_TIMES).getRGB(0, 0);

        screen.setVideoFilter(VideoFilter.NTSC, FilterStrength.defaultStrength(), false);

        assertNotEquals(
                throughThePalette, snapshotOf(screen, ScreenScale.ONE_TIMES).getRGB(0, 0));
    }

    /**
     * The window's picture goes on the tube too, and not only its screenshots -- which is the one
     * thing about this filter that a screenshot cannot tell you, since the window is where the
     * magnification is whatever a corner was dragged to rather than a whole number.
     */
    @Test
    void thePaintedPictureGoesOnTheTubeAsWellAsTheSnapshot() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x20), 0);
        screen.setVideoFilter(VideoFilter.CRT, FilterStrength.MEDIUM, false);

        var painted = paint(screen, 2);

        assertTrue((painted.getRGB(0, 1) & 0xFF) < (painted.getRGB(0, 0) & 0xFF),
                "the row the beam missed is the darker of the two");

        var bent = new ScreenComponent();

        bent.present(frameOf(0x20), 0);
        bent.setVideoFilter(VideoFilter.CRT, FilterStrength.MEDIUM, true);

        assertEquals(0xFF000000, paint(bent, 3).getRGB(0, 0), "and the corners come off");
    }

    /**
     * And at 1x it is the picture, because a gap needs a row to be in and there is not one. The
     * command line refuses that combination; a window cannot, since it is a size somebody drags
     * through on the way to another one.
     */
    @Test
    void aWindowTooSmallForAScanlineDrawsThePictureRatherThanDimmingIt() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x20), 0);

        var plain = painted(screen, 0, OVERSCAN_TOP);

        screen.setVideoFilter(VideoFilter.CRT, FilterStrength.STRONG, false);

        assertEquals(plain, painted(screen, 0, OVERSCAN_TOP));
    }

    /**
     * The tube is the third answer, and unlike the decoder it is the palette's colours -- what it
     * changes is where the light goes. So a lit row is very nearly the palette's pixel and the row
     * under it is not.
     */
    @Test
    void aSnapshotOnATubeKeepsThePalettesColoursAndTakesTheGapsOut() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x20), 0);

        var plain = snapshotOf(screen, ScreenScale.TWO_TIMES);

        screen.setVideoFilter(VideoFilter.CRT, FilterStrength.MEDIUM, false);

        var tube = snapshotOf(screen, ScreenScale.TWO_TIMES);

        assertTrue((tube.getRGB(0, 0) & 0xFF) < (plain.getRGB(0, 0) & 0xFF));
        assertTrue((tube.getRGB(0, 1) & 0xFF) < (tube.getRGB(0, 0) & 0xFF),
                "the row the beam missed is the darker of the two");
    }

    /**
     * And bending the glass cuts the corners off, which is the one thing about it that can be
     * checked without looking at it.
     */
    @Test
    void aWarpedSnapshotHasNoCorners() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x20), 0);
        screen.setVideoFilter(VideoFilter.CRT, FilterStrength.MEDIUM, true);

        var tube = snapshotOf(screen, ScreenScale.FOUR_TIMES);

        assertEquals(0xFF000000, tube.getRGB(0, 0));
        assertNotEquals(0xFF000000, tube.getRGB(tube.getWidth() / 2, tube.getHeight() / 2));
    }

    /**
     * The same question the headless mode's {@code --full-frame} asks, asked of the window. The
     * framebuffer holds all 240 lines either way; this is only how many of them anybody sees, so
     * the picture that is already here shows them without another frame arriving -- which is what
     * makes it usable with the emulator paused, like the palette and the filters.
     */
    @Test
    void showingTheOverscanPaintsTheLinesATelevisionHid() {
        var screen = new ScreenComponent();
        var frame = frameOf(0x21);

        // The eight scanlines the crop hides, in a colour nothing else in the frame is.
        Arrays.fill(frame, 0, OVERSCAN_TOP * PPU.SCREEN_WIDTH, 0x11);
        screen.present(frame, 0);

        var picture = Palettes.defaultPalette().colour(0x21) & 0xFFFFFF;
        var hidden = Palettes.defaultPalette().colour(0x11) & 0xFFFFFF;

        assertEquals(picture, paint(screen).getRGB(0, 0) & 0xFFFFFF, "the crop starts eight down");

        screen.setOverscan(true);

        assertEquals(
                hidden,
                paint(screen, PPU.SCREEN_WIDTH, PPU.SCREEN_HEIGHT).getRGB(0, 0) & 0xFFFFFF,
                "and now the frame starts where the chip started it");

        screen.setOverscan(false);

        assertEquals(picture, paint(screen).getRGB(0, 0) & 0xFFFFFF, "and back again");
    }

    /**
     * The window is given the sixteen rows rather than the picture being squeezed into the height
     * it already had -- the lines somebody asked to see arriving by shrinking everything else is
     * not what was asked for.
     */
    @Test
    void showingTheOverscanAsksTheWindowForSixteenMoreRows() {
        var screen = new ScreenComponent();
        screen.setScale(ScreenScale.THREE_TIMES);

        assertEquals(
                new Dimension(PPU.SCREEN_WIDTH * 3, VISIBLE_HEIGHT * 3),
                screen.getPreferredSize());

        screen.setOverscan(true);

        assertEquals(
                new Dimension(PPU.SCREEN_WIDTH * 3, PPU.SCREEN_HEIGHT * 3),
                screen.getPreferredSize());

        // And the magnification is remembered across it, which is the whole reason the component
        // keeps the scale rather than only the size it worked out from one.
        screen.setScale(ScreenScale.TWO_TIMES);

        assertEquals(
                new Dimension(PPU.SCREEN_WIDTH * 2, PPU.SCREEN_HEIGHT * 2),
                screen.getPreferredSize());
    }

    /**
     * A screenshot is the picture, so it is the whole frame when the whole frame is what is being
     * shown -- the same rule that puts the filter in it.
     */
    @Test
    void aSnapshotIsTheWholeFrameWhileTheOverscanIsShown() {
        var screen = new ScreenComponent();
        var frame = frameOf(0x21);

        Arrays.fill(frame, 0, OVERSCAN_TOP * PPU.SCREEN_WIDTH, 0x11);
        screen.present(frame, 0);
        screen.setOverscan(true);

        var image = snapshotOf(screen, ScreenScale.TWO_TIMES);

        assertEquals(PPU.SCREEN_WIDTH * 2, image.getWidth());
        assertEquals(PPU.SCREEN_HEIGHT * 2, image.getHeight());
        assertEquals(
                Palettes.defaultPalette().colour(0x11) & 0xFFFFFF,
                image.getRGB(0, 0) & 0xFFFFFF,
                "starting at the line the chip started at");
    }

    /**
     * The other half of the same question, asked the other way up. A game that scrolls sideways
     * tells the chip not to draw the background in the leftmost eight pixels and the chip fills
     * them with the backdrop colour, so what this hides is a stripe of sky rather than a mess --
     * which is why it is drawn until somebody says otherwise.
     */
    @Test
    void hidingTheLeftEdgeDropsTheColumnsTheChipCanClip() {
        var screen = new ScreenComponent();
        var frame = frameOf(0x21);

        // The eight columns the chip clips, in a colour nothing else in the frame is.
        for (var y = 0; y < PPU.SCREEN_HEIGHT; y++) {
            Arrays.fill(frame, y * PPU.SCREEN_WIDTH, y * PPU.SCREEN_WIDTH + OVERSCAN_LEFT, 0x11);
        }

        screen.present(frame, 0);

        var picture = Palettes.defaultPalette().colour(0x21) & 0xFFFFFF;
        var stripe = Palettes.defaultPalette().colour(0x11) & 0xFFFFFF;

        assertEquals(stripe, paint(screen).getRGB(0, 0) & 0xFFFFFF, "the stripe is drawn");

        screen.setLeftEdge(false);

        assertEquals(
                picture,
                paint(screen, VISIBLE_WIDTH, VISIBLE_HEIGHT).getRGB(0, 0) & 0xFFFFFF,
                "and now the picture starts where the chip stopped clipping");

        screen.setLeftEdge(true);

        assertEquals(stripe, paint(screen).getRGB(0, 0) & 0xFFFFFF, "and back again");
    }

    /**
     * Eight columns off the left and none off the right, which is the whole of why the crop is not
     * symmetrical: the chip's clipping window is at the left and there is nothing at the other end
     * to hide.
     */
    @Test
    void hidingTheLeftEdgeTakesNothingOffTheRight() {
        var screen = new ScreenComponent();
        var frame = frameOf(0x21);

        for (var y = 0; y < PPU.SCREEN_HEIGHT; y++) {
            frame[y * PPU.SCREEN_WIDTH + PPU.SCREEN_WIDTH - 1] = 0x11;
        }

        screen.present(frame, 0);
        screen.setLeftEdge(false);

        var picture = paint(screen, VISIBLE_WIDTH, VISIBLE_HEIGHT);

        assertEquals(
                Palettes.defaultPalette().colour(0x11) & 0xFFFFFF,
                picture.getRGB(VISIBLE_WIDTH - 1, 0) & 0xFFFFFF);
    }

    /**
     * And the window is given the eight columns rather than squeezing the picture into the width it
     * already had, the way it is given the sixteen rows. The two compose: neither wins.
     */
    @Test
    void hidingTheLeftEdgeAsksTheWindowForEightFewerColumns() {
        var screen = new ScreenComponent();
        screen.setScale(ScreenScale.THREE_TIMES);
        screen.setLeftEdge(false);

        assertEquals(
                new Dimension(VISIBLE_WIDTH * 3, VISIBLE_HEIGHT * 3),
                screen.getPreferredSize());

        screen.setOverscan(true);

        assertEquals(
                new Dimension(VISIBLE_WIDTH * 3, PPU.SCREEN_HEIGHT * 3),
                screen.getPreferredSize());
    }

    @Test
    void aSnapshotDropsTheLeftEdgeWhileItIsHidden() {
        var screen = new ScreenComponent();
        var frame = frameOf(0x21);

        for (var y = 0; y < PPU.SCREEN_HEIGHT; y++) {
            Arrays.fill(frame, y * PPU.SCREEN_WIDTH, y * PPU.SCREEN_WIDTH + OVERSCAN_LEFT, 0x11);
        }

        screen.present(frame, 0);
        screen.setLeftEdge(false);

        var image = snapshotOf(screen, ScreenScale.TWO_TIMES);

        assertEquals(VISIBLE_WIDTH * 2, image.getWidth());
        assertEquals(VISIBLE_HEIGHT * 2, image.getHeight());
        assertEquals(
                Palettes.defaultPalette().colour(0x21) & 0xFFFFFF,
                image.getRGB(0, 0) & 0xFFFFFF,
                "starting at the column the chip stopped clipping at");
    }

    @Test
    void theScaleSetsHowBigTheComponentAsksToBe() {
        var screen = new ScreenComponent();
        var scale = ScreenScale.defaultScale().factor();

        assertEquals(
                new Dimension(PPU.SCREEN_WIDTH * scale, VISIBLE_HEIGHT * scale),
                screen.getPreferredSize(),
                "the size a window with nothing said about it opens at");

        screen.setScale(ScreenScale.THREE_TIMES);

        assertEquals(
                new Dimension(PPU.SCREEN_WIDTH * 3, VISIBLE_HEIGHT * 3),
                screen.getPreferredSize());
    }

    /**
     * The other half of what the overscan does to the component's preferred size: that one asks for
     * more rows and this one for more columns.
     */
    @Test
    void theTelevisionsPixelsMakeTheComponentAskForAWiderWindow() {
        var screen = new ScreenComponent();

        screen.setPixelAspect(Region.NTSC.pixelAspect());
        screen.setScale(ScreenScale.TWO_TIMES);

        assertEquals(new Dimension(585, VISIBLE_HEIGHT * 2), screen.getPreferredSize());

        screen.setPixelAspect(FrameRenderer.SQUARE_PIXELS);
        screen.setScale(ScreenScale.TWO_TIMES);

        assertEquals(
                new Dimension(PPU.SCREEN_WIDTH * 2, VISIBLE_HEIGHT * 2),
                screen.getPreferredSize());
    }

    /**
     * And a snapshot is the shape on screen, for the reason it is the filter on screen: a picture
     * of the window that was a different shape from the window would be a picture of nothing.
     */
    @Test
    void aSnapshotTakesTheTelevisionsPixelsToo() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x21), 0);

        assertEquals(
                PPU.SCREEN_WIDTH * 2,
                snapshotOf(screen, ScreenScale.TWO_TIMES).getWidth());

        screen.setPixelAspect(Region.NTSC.pixelAspect());

        var stretched = snapshotOf(screen, ScreenScale.TWO_TIMES);

        assertEquals(585, stretched.getWidth());
        assertEquals(VISIBLE_HEIGHT * 2, stretched.getHeight());
    }

    /**
     * A window still the square picture's shape letterboxes a television's rather than distorting
     * it, which is the same rule the component has always followed for a window dragged to any
     * other shape: the black goes above and below instead of down the sides.
     */
    @Test
    void aWindowShapedForSquarePixelsLetterboxesATelevisions() {
        var screen = new ScreenComponent();

        screen.setPixelAspect(Region.NTSC.pixelAspect());
        screen.present(frameOf(0x21), 0);

        var painted = paint(screen);
        var colour = Palettes.defaultPalette().colour(0x21) & 0xFFFFFF;

        assertEquals(0, painted.getRGB(0, 0) & 0xFFFFFF, "black along the top");
        assertEquals(colour, painted.getRGB(0, VISIBLE_HEIGHT / 2) & 0xFFFFFF, "picture across");
        assertEquals(
                colour,
                painted.getRGB(PPU.SCREEN_WIDTH - 1, VISIBLE_HEIGHT / 2) & 0xFFFFFF,
                "all the way across, since the width is what it ran out of");
    }

    /**
     * And the two are independent, which is the whole reason {@link ScreenComponent} works the sum
     * again rather than either setting owning one dimension: 240 lines of the television's pixels
     * is wider <em>and</em> taller than 224 of the framebuffer's.
     */
    @Test
    void theOverscanAndTheTelevisionsPixelsBothMoveTheSizeAskedFor() {
        var screen = new ScreenComponent();

        screen.setPixelAspect(Region.NTSC.pixelAspect());
        screen.setOverscan(true);
        screen.setScale(ScreenScale.TWO_TIMES);

        assertEquals(
                new Dimension(585, PPU.SCREEN_HEIGHT * 2),
                screen.getPreferredSize());

        screen.setOverscan(false);

        assertEquals(
                new Dimension(585, VISIBLE_HEIGHT * 2),
                screen.getPreferredSize(),
                "dropping the overscan leaves the width where the pixel shape put it");
    }

    @Test
    void aWholeMultipleLeavesNoLetterbox() {
        var screen = new ScreenComponent();
        screen.setScale(ScreenScale.THREE_TIMES);
        screen.present(frameOf(0x21), 0);

        var size = screen.getPreferredSize();
        var target = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_RGB);

        screen.setSize(size);

        var g = target.createGraphics();
        try {
            screen.paintComponent(g);
        } finally {
            g.dispose();
        }

        // The corners, because the black the component fills with first is what shows through
        // anywhere the picture lands short of an edge -- which is the whole reason for offering
        // whole multiples rather than leaving the window to be dragged to a size.
        var colour = Palettes.defaultPalette().colour(0x21) & 0xFFFFFF;

        assertEquals(colour, target.getRGB(0, 0) & 0xFFFFFF, "top left");
        assertEquals(colour, target.getRGB(size.width - 1, size.height - 1) & 0xFFFFFF, "bottom right");
    }

    /**
     * A flat frame paints flat, so anything that is not the fill colour is the marker -- which is
     * both halves of what the marker has to be: visible, and only where it is meant to be.
     * <p>
     * The top half is checked separately and has to be untouched. That is where a NES game keeps
     * its score and its lives, and a marker sitting on Super Mario Bros.'s timer would be a marker
     * in the way.
     */
    @Test
    void theRewindMarkerIsDrawnOverTheBottomCornerOfThePicture() {
        var screen = new ScreenComponent();
        var colour = Palettes.defaultPalette().colour(0x21) & 0xFFFFFF;

        screen.present(frameOf(0x21), 0);

        assertEquals(
                0,
                drawnOver(paint(screen), 0, 0, PPU.SCREEN_WIDTH, VISIBLE_HEIGHT, colour),
                "nothing over it yet");

        screen.setRewinding(true);

        var image = paint(screen);

        assertTrue(
                drawnOver(image, 0, VISIBLE_HEIGHT / 2, PPU.SCREEN_WIDTH / 3, VISIBLE_HEIGHT, colour)
                        > 0,
                "the marker belongs in the bottom left");
        assertEquals(
                0,
                drawnOver(image, 0, 0, PPU.SCREEN_WIDTH, VISIBLE_HEIGHT / 2, colour),
                "and nowhere near the top, where the game keeps its score");

        screen.setRewinding(false);

        assertEquals(
                0,
                drawnOver(paint(screen), 0, 0, PPU.SCREEN_WIDTH, VISIBLE_HEIGHT, colour),
                "and it goes away again");
    }

    /**
     * Over the picture rather than in it. A marker that reached the framebuffer would turn up in
     * screenshots and in the frame hashes the headless mode compares runs with, where it would be a
     * lie about what the machine drew.
     */
    @Test
    void theRewindMarkerStaysOutOfScreenshots() {
        var screen = new ScreenComponent();
        var colour = Palettes.defaultPalette().colour(0x21) & 0xFFFFFF;

        screen.present(frameOf(0x21), 0);
        screen.setRewinding(true);

        var snapshot = snapshotOf(screen, ScreenScale.ONE_TIMES);

        assertEquals(
                0,
                drawnOver(snapshot, 0, 0, PPU.SCREEN_WIDTH, VISIBLE_HEIGHT, colour),
                "a screenshot is of the machine, and the machine drew none of this");
    }

    @Test
    void aSnapshotIsTheVisiblePictureAtTheSizeAskedFor() {
        var screen = new ScreenComponent();
        screen.present(frameOf(0x21), 0);

        var image = snapshotOf(screen, ScreenScale.TWO_TIMES);

        assertEquals(PPU.SCREEN_WIDTH * 2, image.getWidth());
        assertEquals(VISIBLE_HEIGHT * 2, image.getHeight());
        assertEquals(
                Palettes.defaultPalette().colour(0x21) & 0xFFFFFF,
                image.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void aSnapshotStartsAtTheFirstLineATelevisionWouldHaveShown() {
        var screen = new ScreenComponent();
        var frame = frameOf(0x21);

        // The eight scanlines the crop hides, in a colour nothing else in the frame is.
        Arrays.fill(frame, 0, OVERSCAN_TOP * PPU.SCREEN_WIDTH, 0x11);
        screen.present(frame, 0);

        var image = snapshotOf(screen, ScreenScale.ONE_TIMES);

        assertEquals(
                Palettes.defaultPalette().colour(0x21) & 0xFFFFFF,
                image.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void aSnapshotIgnoresWhatSizeTheWindowHasBeenDraggedTo() {
        var screen = new ScreenComponent();
        screen.present(frameOf(0x21), 0);

        // A corner dragged by hand, which is what the picture on screen is fitted to: a fractional
        // magnification, letterboxed. A screenshot is of the machine rather than of the window, so
        // none of that may reach it.
        screen.setSize(517, 300);

        var image = snapshotOf(screen, ScreenScale.ONE_TIMES);

        assertEquals(PPU.SCREEN_WIDTH, image.getWidth());
        assertEquals(VISIBLE_HEIGHT, image.getHeight());
    }

    @Test
    void aSnapshotIsTakenThroughTheChosenPalette() {
        var screen = new ScreenComponent();
        var classic = Palettes.byId("nes-classic");

        screen.present(frameOf(0x21), 0);
        screen.setPalette(classic);

        assertEquals(
                classic.colour(0x21) & 0xFFFFFF,
                snapshotOf(screen, ScreenScale.ONE_TIMES).getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void thereIsNothingToPhotographBeforeTheFirstFrame() {
        // The menu item is greyed out until a machine starts, which still leaves the sixtieth of a
        // second between it starting and finishing a frame.
        assertNull(new ScreenComponent().snapshot(ScreenScale.ONE_TIMES));
    }

    @Test
    void thereIsNothingToSeeBeforeTheFirstFrame() {
        var screen = new ScreenComponent();

        // Entry 0 is dark grey in every palette here, so colourising a framebuffer that has never
        // been written would light the window up before a ROM is even loaded.
        assertEquals(0, painted(screen, 0, OVERSCAN_TOP));

        screen.setPalette(Palettes.byId("nes-classic"));

        assertEquals(0, painted(screen, 0, OVERSCAN_TOP), "and still nothing after a palette change");
    }
}
