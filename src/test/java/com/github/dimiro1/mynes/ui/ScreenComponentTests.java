package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.ui.palette.Palettes;
import com.github.dimiro1.mynes.video.FrameRenderer;
import org.junit.jupiter.api.Test;

import java.awt.Dimension;
import java.awt.image.BufferedImage;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
     * Paints the component at 1:1 and reads a pixel back, in framebuffer coordinates.
     */
    private static int painted(final ScreenComponent screen, final int x, final int y) {
        var target = new BufferedImage(
                PPU.SCREEN_WIDTH, VISIBLE_HEIGHT, BufferedImage.TYPE_INT_RGB);

        screen.setSize(PPU.SCREEN_WIDTH, VISIBLE_HEIGHT);

        var g = target.createGraphics();
        try {
            screen.paintComponent(g);
        } finally {
            g.dispose();
        }

        return target.getRGB(x, y - OVERSCAN_TOP) & 0xFFFFFF;
    }

    private static int[] frameOf(final int entry) {
        var frame = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];
        Arrays.fill(frame, entry);

        return frame;
    }

    @Test
    void aFrameIsColouredThroughTheChosenPalette() {
        var screen = new ScreenComponent();

        screen.present(frameOf(0x21));

        assertEquals(
                Palettes.defaultPalette().colour(0x21) & 0xFFFFFF,
                painted(screen, 0, OVERSCAN_TOP));
    }

    @Test
    void emphasisPicksTheDimmedCopyOfTheColour() {
        var screen = new ScreenComponent();

        // What the PPU writes with all three emphasis bits set: entry 0x21, emphasis 7.
        screen.present(frameOf((7 << 6) | 0x21));

        assertEquals(
                Palettes.defaultPalette().colours()[(7 << 6) | 0x21] & 0xFFFFFF,
                painted(screen, 0, OVERSCAN_TOP));
    }

    @Test
    void changingThePaletteRecoloursTheFrameAlreadyHere() {
        var screen = new ScreenComponent();
        var classic = Palettes.byId("nes-classic");

        screen.present(frameOf(0x21));
        screen.setPalette(classic);

        // No new frame arrives, which is the case that matters: with the emulator paused there is
        // not going to be one, and the preview has to work anyway.
        assertEquals(classic.colour(0x21) & 0xFFFFFF, painted(screen, 0, OVERSCAN_TOP));
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
        screen.present(frameOf(0x21));

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
