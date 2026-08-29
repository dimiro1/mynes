package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.PPU;
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
        var target = new BufferedImage(
                PPU.SCREEN_WIDTH * scale, VISIBLE_HEIGHT * scale, BufferedImage.TYPE_INT_RGB);

        screen.setSize(PPU.SCREEN_WIDTH * scale, VISIBLE_HEIGHT * scale);

        var g = target.createGraphics();
        try {
            screen.paintComponent(g);
        } finally {
            g.dispose();
        }

        return target;
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

        var throughThePalette = screen.snapshot(ScreenScale.ONE_TIMES).getRGB(0, 0);

        screen.setVideoFilter(VideoFilter.NTSC, FilterStrength.defaultStrength(), false);

        assertNotEquals(
                throughThePalette, screen.snapshot(ScreenScale.ONE_TIMES).getRGB(0, 0));
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

        var plain = screen.snapshot(ScreenScale.TWO_TIMES);

        screen.setVideoFilter(VideoFilter.CRT, FilterStrength.MEDIUM, false);

        var tube = screen.snapshot(ScreenScale.TWO_TIMES);

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

        var tube = screen.snapshot(ScreenScale.FOUR_TIMES);

        assertEquals(0xFF000000, tube.getRGB(0, 0));
        assertNotEquals(0xFF000000, tube.getRGB(tube.getWidth() / 2, tube.getHeight() / 2));
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

        var snapshot = screen.snapshot(ScreenScale.ONE_TIMES);

        assertEquals(
                0,
                drawnOver(snapshot, 0, 0, PPU.SCREEN_WIDTH, VISIBLE_HEIGHT, colour),
                "a screenshot is of the machine, and the machine drew none of this");
    }

    @Test
    void aSnapshotIsTheVisiblePictureAtTheSizeAskedFor() {
        var screen = new ScreenComponent();
        screen.present(frameOf(0x21), 0);

        var image = screen.snapshot(ScreenScale.TWO_TIMES);

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

        var image = screen.snapshot(ScreenScale.ONE_TIMES);

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

        var image = screen.snapshot(ScreenScale.ONE_TIMES);

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
                screen.snapshot(ScreenScale.ONE_TIMES).getRGB(0, 0) & 0xFFFFFF);
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
