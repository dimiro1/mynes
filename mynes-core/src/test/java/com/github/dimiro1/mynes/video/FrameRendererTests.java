package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Turning colour indices into a picture, which is the half of the job the chip does not do.
 * <p>
 * Nothing here needs a display: a {@link java.awt.image.BufferedImage} is memory with an opinion
 * about pixel formats, and the emulator's own window has always been tested the same way.
 */
class FrameRendererTests {
    private static final int[] PALETTE = Palettes.defaultPalette().colours();

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
        var image = FrameRenderer.render(frameOf(0x21), PALETTE, true, 1);

        assertEquals(PALETTE[0x21] & 0xFFFFFF, image.getRGB(0, 0) & 0xFFFFFF);
    }

    /**
     * The emphasis bits sit above the colour index, so entry 0x21 and entry 0x61 are the same
     * colour with one of them dimmed. Getting this wrong would show up as a picture that ignored
     * $2001 entirely.
     */
    /**
     * The other overload, which takes a decoder in place of the palette. What it shares with the
     * palette one is everything below the colours -- the crop, the magnification, the size of the
     * picture -- and that is what is checked here; what the decoder itself does is
     * {@code NTSCFilterTests}.
     */
    @Test
    void aFilteredFrameIsCroppedAndMagnifiedTheSameWay() {
        var image = FrameRenderer.render(frameOf(0x21), new NTSCFilter(), 0, true, 3);

        assertEquals(PPU.SCREEN_WIDTH * 3, image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT * 3, image.getHeight());

        var full = FrameRenderer.render(frameOf(0x21), new NTSCFilter(), 0, false, 1);

        assertEquals(PPU.SCREEN_HEIGHT, full.getHeight());
    }

    /**
     * The point of the overload: the palette is not a parameter because it is not consulted.
     */
    @Test
    void aFilteredFrameIsNotColouredThroughThePalette() {
        var palette = FrameRenderer.render(frameOf(0x21), PALETTE, true, 1).getRGB(0, 0);
        var filtered = FrameRenderer.render(frameOf(0x21), new NTSCFilter(), 0, true, 1).getRGB(0, 0);

        org.junit.jupiter.api.Assertions.assertNotEquals(palette, filtered);
    }

    @Test
    void emphasisPicksTheDimmedCopyOfTheColour() {
        var plain = FrameRenderer.render(frameOf(0x21), PALETTE, true, 1).getRGB(0, 0);
        var emphasised = FrameRenderer.render(frameOf(0x61), PALETTE, true, 1).getRGB(0, 0);

        assertEquals(PALETTE[0x61] & 0xFFFFFF, emphasised & 0xFFFFFF);
        org.junit.jupiter.api.Assertions.assertNotEquals(plain, emphasised);
    }

    @Test
    void theCropTakesEightScanlinesFromEachEnd() {
        var image = FrameRenderer.render(rowNumberedFrame(), PALETTE, true, 1);

        assertEquals(PPU.SCREEN_WIDTH, image.getWidth());
        assertEquals(FrameRenderer.VISIBLE_HEIGHT, image.getHeight());
        assertEquals(
                PALETTE[FrameRenderer.OVERSCAN_TOP] & 0xFFFFFF,
                image.getRGB(0, 0) & 0xFFFFFF,
                "the top row of the picture is framebuffer row eight");
    }

    @Test
    void theFullFrameKeepsAllTwoHundredAndFortyLines() {
        var image = FrameRenderer.render(rowNumberedFrame(), PALETTE, false, 1);

        assertEquals(PPU.SCREEN_HEIGHT, image.getHeight());
        assertEquals(PALETTE[0] & 0xFFFFFF, image.getRGB(0, 0) & 0xFFFFFF);
    }

    @Test
    void scalingRepeatsEveryPixel() {
        var image = FrameRenderer.render(rowNumberedFrame(), PALETTE, true, 3);

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
    void aScaleOutsideWhatItWillDoIsRefused() {
        assertThrows(IllegalArgumentException.class,
                () -> FrameRenderer.render(frameOf(0), PALETTE, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> FrameRenderer.render(frameOf(0), PALETTE, true, FrameRenderer.MAX_SCALE + 1));
    }
}
