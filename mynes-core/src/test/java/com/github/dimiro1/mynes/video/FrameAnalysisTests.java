package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Describing a picture to something that cannot see it.
 * <p>
 * The crop is what most of this is about. A hash taken over all 240 lines would answer a different
 * question from the one anybody is asking -- it would move when the overscan moved, and the
 * overscan is the part games are entitled to leave a mess in.
 */
class FrameAnalysisTests {
    private static int[] frameOf(final int entry) {
        var frame = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];
        Arrays.fill(frame, entry);

        return frame;
    }

    private static int at(final int x, final int y) {
        return y * PPU.SCREEN_WIDTH + x;
    }

    @Test
    void aFlatPictureIsBlank() {
        var analysis = FrameAnalysis.of(frameOf(0x0F));

        assertTrue(analysis.blank());
        assertEquals(1, analysis.uniqueColours());
        assertEquals(0x0F, analysis.dominantColour());
    }

    @Test
    void aPictureWithTwoColoursInItIsNot() {
        var frame = frameOf(0x0F);
        frame[at(0, FrameRenderer.OVERSCAN_TOP)] = 0x21;

        var analysis = FrameAnalysis.of(frame);

        assertFalse(analysis.blank());
        assertEquals(2, analysis.uniqueColours());
    }

    /**
     * The overscan is not part of the picture, so a game scribbling in it -- which they do, drawing
     * partial tiles and scroll seams up there -- must not look like something happening.
     */
    @Test
    void whatIsUnderTheOverscanChangesNothing() {
        var hidden = frameOf(0x0F);

        hidden[at(0, 0)] = 0x21;
        hidden[at(255, FrameRenderer.OVERSCAN_TOP - 1)] = 0x21;
        hidden[at(0, FrameRenderer.VISIBLE_BOTTOM)] = 0x21;
        hidden[at(255, PPU.SCREEN_HEIGHT - 1)] = 0x21;

        var analysis = FrameAnalysis.of(hidden);

        assertTrue(analysis.blank(), "none of those pixels are on screen");
        assertEquals(FrameAnalysis.hash(frameOf(0x0F)), analysis.hash());
    }

    @Test
    void whatIsInsideItChangesEverything() {
        var frame = frameOf(0x0F);
        frame[at(0, FrameRenderer.OVERSCAN_TOP)] = 0x21;

        assertNotEquals(FrameAnalysis.hash(frameOf(0x0F)), FrameAnalysis.hash(frame));
    }

    @Test
    void theLastVisibleRowCounts() {
        var frame = frameOf(0x0F);
        frame[at(128, FrameRenderer.VISIBLE_BOTTOM - 1)] = 0x21;

        assertFalse(FrameAnalysis.of(frame).blank());
    }

    /**
     * Emphasis sits above the colour index, so the same colour dimmed is a different entry and a
     * different picture. A hash that folded only the low six bits would miss a game turning the
     * screen red.
     */
    @Test
    void emphasisIsPartOfTheColour() {
        assertNotEquals(FrameAnalysis.hash(frameOf(0x21)), FrameAnalysis.hash(frameOf(0x61)));
        assertEquals(0x61, FrameAnalysis.of(frameOf(0x61)).dominantColour());
    }

    @Test
    void theCommonestColoursComeFirst() {
        var frame = frameOf(0x0F);

        for (var x = 0; x < 100; x++) {
            frame[at(x, FrameRenderer.OVERSCAN_TOP)] = 0x21;
        }

        for (var x = 0; x < 10; x++) {
            frame[at(x, FrameRenderer.OVERSCAN_TOP + 1)] = 0x30;
        }

        var top = FrameAnalysis.of(frame).topColours();

        assertEquals(3, top.size());
        assertEquals(0x0F, top.get(0).entry());
        assertEquals(0x21, top.get(1).entry());
        assertEquals(0x30, top.get(2).entry());
        assertEquals(100, top.get(1).count());
        assertEquals(10, top.get(2).count());
    }

    @Test
    void theCountsAddUpToTheVisiblePicture() {
        var total = FrameAnalysis.of(frameOf(0x0F)).topColours().getFirst().count();

        assertEquals((long) PPU.SCREEN_WIDTH * FrameRenderer.VISIBLE_HEIGHT, total);
    }

    /**
     * The two entry points walk the frame separately, so they have to agree -- {@link
     * FrameAnalysis#of} folding the hash into its own pass is the only reason it is worth having
     * two.
     */
    @Test
    void bothWaysOfHashingAgree() {
        var frame = frameOf(0x0F);
        frame[at(64, 100)] = 0x21;
        frame[at(200, 30)] = 0x30;

        assertEquals(FrameAnalysis.hash(frame), FrameAnalysis.of(frame).hash());
    }

    @Test
    void theSamePictureAlwaysHashesTheSame() {
        assertEquals(FrameAnalysis.hash(frameOf(0x21)), FrameAnalysis.hash(frameOf(0x21)));
    }
}
