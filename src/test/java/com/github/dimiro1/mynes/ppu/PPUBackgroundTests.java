package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.mappers.Mirroring;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The background fetch pipeline, checked by looking at what comes out on the screen.
 * <p>
 * Everything here is set up through the registers a game would use and then read back out of the
 * framebuffer, so the tests say what the picture should look like rather than what the shift
 * registers should contain. That is the only part of it a game can see, and it is what breaks
 * visibly when the fetch cycle is a dot out.
 */
class PPUBackgroundTests extends PPUFixture {
    /**
     * Colour indices put into the four entries of background palette 0, chosen so that a wrong
     * palette or a wrong pixel value never happens to match.
     */
    private static final int BACKDROP = 0x0F;
    private static final int COLOUR_1 = 0x01;
    private static final int COLOUR_2 = 0x11;
    private static final int COLOUR_3 = 0x21;

    /**
     * Colour index for the first entry of background palette 1, used to check the attribute path.
     */
    private static final int PALETTE_1_COLOUR = 0x2A;

    private static final int NAMETABLE_0 = 0x2000;
    private static final int NAMETABLE_1 = 0x2400;
    private static final int NAMETABLE_2 = 0x2800;

    private static final int TILE_BYTES = 0x3C0;
    private static final int ATTRIBUTE_BYTES = 0x40;

    private static final int SHOW_BACKGROUND = 0x08;
    private static final int SHOW_BACKGROUND_LEFT = 0x02;

    private int[] masterPalette;

    @BeforeEach
    void setUp() {
        createWarmPPU();
        masterPalette = PPU.getPalette();

        // Four solid tiles, one per pixel value, so a tile number maps straight to a colour.
        solidTile(0, 0);
        solidTile(1, 1);
        solidTile(2, 2);
        solidTile(3, 3);

        writeVRAM(0x3F00, BACKDROP);
        writeVRAM(0x3F01, COLOUR_1);
        writeVRAM(0x3F02, COLOUR_2);
        writeVRAM(0x3F03, COLOUR_3);
        writeVRAM(0x3F05, PALETTE_1_COLOUR);
    }

    @Nested
    @DisplayName("drawing tiles")
    class Tiles {
        @Test
        void aScreenOfOneTileIsAFlatSheetOfItsColour() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);
            startRendering();

            renderFrames(2);

            assertEveryPixelIs(colour(COLOUR_1));
        }

        @Test
        void pixelValueChoosesTheEntryWithinThePalette() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 2);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);
            startRendering();

            renderFrames(2);

            assertEveryPixelIs(colour(COLOUR_2));
        }

        @Test
        void pixelValueZeroIsTheBackdropWhicheverPaletteIsSelected() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 0);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0xFF);
            startRendering();

            renderFrames(2);

            assertEveryPixelIs(colour(BACKDROP));
        }

        @Test
        void tilesAreEightPixelsWide() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            // A single different tile in the top left corner.
            writeVRAM(NAMETABLE_0, 2);
            startRendering();

            renderFrames(2);

            for (var y = 0; y < 8; y++) {
                for (var x = 0; x < 8; x++) {
                    assertEquals(colour(COLOUR_2), pixelAt(x, y), x + "," + y);
                }

                assertEquals(colour(COLOUR_1), pixelAt(8, y), "and the tile next to it is not");
            }

            assertEquals(colour(COLOUR_1), pixelAt(0, 8), "nor the one below");
        }
    }

    @Nested
    @DisplayName("attributes")
    class Attributes {
        @Test
        void chooseThePaletteForEachSixteenBySixteenQuadrant() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            // The first attribute byte covers the top left 32x32 pixels, two bits per quadrant.
            // Palette 1 for the top left quadrant, palette 0 for the other three.
            writeVRAM(NAMETABLE_0 + TILE_BYTES, 0b00_00_00_01);
            startRendering();

            renderFrames(2);

            assertEquals(colour(PALETTE_1_COLOUR), pixelAt(0, 0), "top left quadrant");
            assertEquals(colour(PALETTE_1_COLOUR), pixelAt(15, 15), "and all sixteen pixels of it");
            assertEquals(colour(COLOUR_1), pixelAt(16, 0), "the quadrant to its right");
            assertEquals(colour(COLOUR_1), pixelAt(0, 16), "and the one below it");
        }

        @Test
        void theTopRightQuadrantUsesTheSecondPairOfBits() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);
            writeVRAM(NAMETABLE_0 + TILE_BYTES, 0b00_00_01_00);
            startRendering();

            renderFrames(2);

            assertEquals(colour(COLOUR_1), pixelAt(0, 0));
            assertEquals(colour(PALETTE_1_COLOUR), pixelAt(16, 0));
            assertEquals(colour(COLOUR_1), pixelAt(0, 16));
        }

        @Test
        void theBottomLeftQuadrantUsesTheThirdPairOfBits() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);
            writeVRAM(NAMETABLE_0 + TILE_BYTES, 0b00_01_00_00);
            startRendering();

            renderFrames(2);

            assertEquals(colour(COLOUR_1), pixelAt(0, 0));
            assertEquals(colour(PALETTE_1_COLOUR), pixelAt(0, 16));
            assertEquals(colour(COLOUR_1), pixelAt(16, 16), "the bottom right quadrant is separate");
        }
    }

    @Nested
    @DisplayName("scrolling")
    class Scrolling {
        @Test
        void fineXMovesThePictureLeftWithinATile() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            // Two tiles at the top left, so the seam between them is visible.
            writeVRAM(NAMETABLE_0, 2);

            setScroll(0, 3, 0);
            ppu.write(PPUMASK, SHOW_BACKGROUND | SHOW_BACKGROUND_LEFT);
            renderFrames(2);

            assertEquals(colour(COLOUR_2), pixelAt(4, 0), "still inside the first tile");
            assertEquals(colour(COLOUR_1), pixelAt(5, 0), "the seam has moved three pixels left");
        }

        @Test
        void coarseXCarriesIntoTheNextNametable() {
            mapper.setMirroring(Mirroring.VERTICAL);
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);
            fillVRAM(NAMETABLE_1, TILE_BYTES, 2);
            fillVRAM(NAMETABLE_1 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            // The last tile column of the first nametable, then over the edge.
            setScroll(0, 248, 0);
            ppu.write(PPUMASK, SHOW_BACKGROUND | SHOW_BACKGROUND_LEFT);
            renderFrames(2);

            assertEquals(colour(COLOUR_1), pixelAt(0, 0), "the last column of nametable 0");
            assertEquals(colour(COLOUR_1), pixelAt(7, 0));
            assertEquals(colour(COLOUR_2), pixelAt(8, 0), "and then nametable 1");
            assertEquals(colour(COLOUR_2), pixelAt(255, 0));
        }

        @Test
        void coarseYCarriesIntoTheNametableBelow() {
            mapper.setMirroring(Mirroring.HORIZONTAL);
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);
            fillVRAM(NAMETABLE_2, TILE_BYTES, 2);
            fillVRAM(NAMETABLE_2 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            // The last tile row of the first nametable, then over the edge. A nametable is 30
            // tiles tall, so coarse Y wraps at 29 rather than at 31.
            setScroll(0, 0, 232);
            ppu.write(PPUMASK, SHOW_BACKGROUND | SHOW_BACKGROUND_LEFT);
            renderFrames(2);

            assertEquals(colour(COLOUR_1), pixelAt(0, 0), "the last row of nametable 0");
            assertEquals(colour(COLOUR_1), pixelAt(0, 7));
            assertEquals(colour(COLOUR_2), pixelAt(0, 8), "and then the nametable below");
            assertEquals(colour(COLOUR_2), pixelAt(0, 239));
        }

        @Test
        void theHorizontalPositionIsPutBackAtTheStartOfEveryScanline() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            // A stripe down the first tile column. Without the copy at dot 257 the address would
            // carry on from wherever the last scanline left it and the stripe would slide.
            for (var row = 0; row < 30; row++) {
                writeVRAM(NAMETABLE_0 + row * 32, 2);
            }

            startRendering();
            renderFrames(2);

            for (var y = 0; y < 240; y++) {
                assertEquals(colour(COLOUR_2), pixelAt(0, y), "row " + y);
                assertEquals(colour(COLOUR_1), pixelAt(8, y), "row " + y);
            }
        }

        @Test
        void theVerticalPositionIsPutBackOncePerFrame() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);
            fillVRAM(NAMETABLE_0, 32, 2);  // a stripe along the first tile row

            startRendering();

            renderFrames(2);

            // Three more frames, so a vertical position that drifted would have drifted visibly.
            for (var frame = 0; frame < 3; frame++) {
                assertEquals(colour(COLOUR_2), pixelAt(0, 0), "frame " + frame);
                assertEquals(colour(COLOUR_1), pixelAt(0, 8), "frame " + frame);

                renderFrames(1);
            }
        }
    }

    @Nested
    @DisplayName("what $2001 does to the picture")
    class MaskEffects {
        @Test
        void theLeftmostEightPixelsCanBeHidden() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            startRendering(SHOW_BACKGROUND);  // without SHOW_BACKGROUND_LEFT
            renderFrames(2);

            assertEquals(colour(BACKDROP), pixelAt(0, 0));
            assertEquals(colour(BACKDROP), pixelAt(7, 0));
            assertEquals(colour(COLOUR_1), pixelAt(8, 0));
        }

        @Test
        void turningTheBackgroundOffLeavesTheBackdrop() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            startRendering(0x00);
            renderFrames(2);

            assertEveryPixelIs(colour(BACKDROP));
        }

        @Test
        void greyscaleDropsTheHueFromEveryPixel() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            startRendering(SHOW_BACKGROUND | SHOW_BACKGROUND_LEFT | 0x01);
            renderFrames(2);

            assertEveryPixelIs(colour(COLOUR_1 & 0x30));
        }

        @Test
        void emphasisDimsTheChannelsItDoesNotName() {
            fillVRAM(NAMETABLE_0, TILE_BYTES, 1);
            fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);

            startRendering(SHOW_BACKGROUND | SHOW_BACKGROUND_LEFT | 0x20);  // emphasise red
            renderFrames(2);

            var plain = colour(COLOUR_1);
            var shown = pixelAt(0, 0);

            assertEquals((plain >> 16) & 0xFF, (shown >> 16) & 0xFF, "red is left alone");
            assertEquals((int) (((plain >> 8) & 0xFF) * 0.746), (shown >> 8) & 0xFF, "green is dimmed");
            assertEquals((int) ((plain & 0xFF) * 0.746), shown & 0xFF, "and so is blue");
        }
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Defines tile {@code tile} as a solid block of pixel value {@code value}.
     */
    private void solidTile(final int tile, final int value) {
        for (var row = 0; row < 8; row++) {
            writeVRAM(tile * 16 + row, (value & 1) != 0 ? 0xFF : 0x00);
            writeVRAM(tile * 16 + 8 + row, (value & 2) != 0 ? 0xFF : 0x00);
        }
    }

    /**
     * Sets the scroll position, which also puts the staging address register back to something
     * sane. Filling VRAM leaves it pointing at whatever was written last, and rendering picks it
     * up on the pre-render line, so every test has to say where the picture starts.
     */
    private void setScroll(final int nametable, final int x, final int y) {
        ppu.write(PPUCTRL, nametable & 3);
        ppu.read(PPUSTATUS);
        ppu.write(PPUSCROLL, x);
        ppu.write(PPUSCROLL, y);
    }

    private void startRendering() {
        startRendering(SHOW_BACKGROUND | SHOW_BACKGROUND_LEFT);
    }

    private void startRendering(final int maskBits) {
        setScroll(0, 0, 0);
        ppu.write(PPUMASK, maskBits);
    }

    private int colour(final int entry) {
        return masterPalette[entry & 0x3F];
    }

    private void assertEveryPixelIs(final int expected) {
        for (var y = 0; y < 240; y++) {
            for (var x = 0; x < 256; x++) {
                assertEquals(expected, pixelAt(x, y), x + "," + y);
            }
        }
    }
}
