package com.github.dimiro1.mynes.ppu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprite evaluation, the sprite fetch, and what comes out of the multiplexer.
 * <p>
 * The evaluation half of this is where the PPU's best known bug lives: once eight sprites have
 * been found for a scanline, the hardware keeps looking with a counter that steps through OAM
 * wrongly, so the overflow flag ends up reflecting tile numbers and X coordinates rather than Y
 * coordinates. Games depend on the exact pattern, so there are tests here for both a false
 * positive and a false negative.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_sprite_evaluation">NESdev: sprite evaluation</a>
 */
class PPUSpriteTests extends PPUFixture {
    private static final int BACKDROP = 0x0F;
    private static final int BACKGROUND_COLOUR = 0x01;
    private static final int SPRITE_COLOUR = 0x16;
    private static final int SPRITE_PALETTE_1_COLOUR = 0x2A;

    private static final int NAMETABLE_0 = 0x2000;
    private static final int TILE_BYTES = 0x3C0;
    private static final int ATTRIBUTE_BYTES = 0x40;

    private static final int SHOW_BACKGROUND_LEFT = 0x02;
    private static final int SHOW_SPRITES_LEFT = 0x04;
    private static final int SHOW_BACKGROUND = 0x08;
    private static final int SHOW_SPRITES = 0x10;
    private static final int SHOW_EVERYTHING =
            SHOW_BACKGROUND | SHOW_SPRITES | SHOW_BACKGROUND_LEFT | SHOW_SPRITES_LEFT;

    // Sprite attribute bits.
    private static final int BEHIND_BACKGROUND = 0x20;
    private static final int FLIP_HORIZONTALLY = 0x40;
    private static final int FLIP_VERTICALLY = 0x80;

    private static final int SPRITE_OVERFLOW = 0x20;
    private static final int SPRITE_ZERO_HIT = 0x40;
    private static final int VBLANK = 0x80;

    /**
     * A tile of solid pixel value 1, and one whose left half only is solid, for the flip tests.
     */
    private static final int SOLID_TILE = 1;
    private static final int LEFT_HALF_TILE = 4;
    private static final int TOP_HALF_TILE = 5;

    @BeforeEach
    void setUp() {
        createWarmPPU();

        solidTile(SOLID_TILE, 1);

        for (var row = 0; row < 8; row++) {
            writeVRAM(LEFT_HALF_TILE * 16 + row, 0xF0);
            writeVRAM(TOP_HALF_TILE * 16 + row, row < 4 ? 0xFF : 0x00);
        }

        writeVRAM(0x3F00, BACKDROP);
        writeVRAM(0x3F01, BACKGROUND_COLOUR);
        writeVRAM(0x3F11, SPRITE_COLOUR);
        writeVRAM(0x3F15, SPRITE_PALETTE_1_COLOUR);

        clearOAM();
    }

    /**
     * Parks every sprite off the bottom of the screen.
     * <p>
     * OAM powers up here as sixty four sprites at Y=0, which are in range on the first eight
     * scanlines of every frame and would overflow the sprite evaluation before any test had said
     * anything. A game does the same thing on startup for the same reason.
     */
    private void clearOAM() {
        ppu.write(OAMADDR, 0x00);

        for (var sprite = 0; sprite < 64; sprite++) {
            ppu.write(OAMDATA, 0xFF);
            ppu.write(OAMDATA, 0x00);
            ppu.write(OAMDATA, 0x00);
            ppu.write(OAMDATA, 0x00);
        }
    }

    @Nested
    @DisplayName("drawing sprites")
    class Drawing {
        @Test
        void appearOneScanlineBelowTheirYCoordinate() {
            writeSprite(0, 10, SOLID_TILE, 0, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(BACKDROP), pixelAt(20, 10), "the row the Y coordinate names is not it");
            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11), "one below is");
            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 18), "eight rows tall");
            assertEquals(colour(BACKDROP), pixelAt(20, 19));
        }

        @Test
        void areNeverDrawnOnScanlineZero() {
            // Y = 0 is as high as a sprite can be put, and it still starts on scanline 1: the
            // pre-render line does no evaluation, so nothing is ever chosen for scanline 0.
            writeSprite(0, 0, SOLID_TILE, 0, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(BACKDROP), pixelAt(20, 0));
            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 1));
        }

        @Test
        void areEightPixelsWideStartingAtTheirXCoordinate() {
            writeSprite(0, 10, SOLID_TILE, 0, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(BACKDROP), pixelAt(19, 11));
            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11));
            assertEquals(colour(SPRITE_COLOUR), pixelAt(27, 11));
            assertEquals(colour(BACKDROP), pixelAt(28, 11));
        }

        @Test
        void theAttributeBitsChooseOneOfTheFourSpritePalettes() {
            writeSprite(0, 10, SOLID_TILE, 0x01, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(SPRITE_PALETTE_1_COLOUR), pixelAt(20, 11));
        }

        @Test
        void canBeFlippedHorizontally() {
            writeSprite(0, 10, LEFT_HALF_TILE, 0, 20);
            writeSprite(1, 30, LEFT_HALF_TILE, FLIP_HORIZONTALLY, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11), "unflipped: the left half is solid");
            assertEquals(colour(BACKDROP), pixelAt(27, 11));

            assertEquals(colour(BACKDROP), pixelAt(20, 31), "flipped: and now the right half is");
            assertEquals(colour(SPRITE_COLOUR), pixelAt(27, 31));
        }

        @Test
        void canBeFlippedVertically() {
            writeSprite(0, 10, TOP_HALF_TILE, 0, 20);
            writeSprite(1, 30, TOP_HALF_TILE, FLIP_VERTICALLY, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11), "unflipped: the top half is solid");
            assertEquals(colour(BACKDROP), pixelAt(20, 18));

            assertEquals(colour(BACKDROP), pixelAt(20, 31), "flipped: and now the bottom half is");
            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 38));
        }

        @Test
        void aLowerNumberedSpriteCoversAHigherNumberedOne() {
            writeSprite(0, 10, SOLID_TILE, 0x00, 20);   // sprite palette 0
            writeSprite(1, 10, SOLID_TILE, 0x01, 20);   // sprite palette 1, same place
            startRendering();
            renderFrames(2);

            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11), "sprite 0 wins");
        }

        @Test
        void aLowerNumberedSpriteCoversAHigherNumberedOneEvenWhenItIsBehindTheBackground() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, BEHIND_BACKGROUND, 20);
            writeSprite(1, 10, SOLID_TILE, 0x01, 20);
            startRendering();
            renderFrames(2);

            assertEquals(
                    colour(BACKGROUND_COLOUR), pixelAt(20, 11),
                    "sprite 0 still won the multiplexer, and then lost to the background"
            );
        }

        @Test
        void onlyTheFirstEightOnAScanlineAreDrawn() {
            for (var i = 0; i < 9; i++) {
                writeSprite(i, 10, SOLID_TILE, 0, 20 + i * 10);
            }

            startRendering();
            renderFrames(2);

            assertEquals(colour(SPRITE_COLOUR), pixelAt(20 + 7 * 10, 11), "the eighth is drawn");
            assertEquals(colour(BACKDROP), pixelAt(20 + 8 * 10, 11), "the ninth is not");
        }

        @Test
        void tallSpritesTakeTheirPatternTableFromTheTileNumber() {
            // Tile 3 is odd, so an 8x16 sprite using it comes from the second pattern table
            // whatever $2000 says. Put a solid tile there and leave the first table empty.
            for (var row = 0; row < 8; row++) {
                writeVRAM(0x1000 + 2 * 16 + row, 0xFF);       // top half, tile pair 2
                writeVRAM(0x1000 + 3 * 16 + row, 0xFF);       // bottom half
            }

            writeSprite(0, 10, 0x03, 0, 20);
            startRendering(SHOW_EVERYTHING, 0x20);  // tall sprites
            renderFrames(2);

            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11), "the top half");
            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 26), "and sixteen rows of it");
            assertEquals(colour(BACKDROP), pixelAt(20, 27));
        }
    }

    @Nested
    @DisplayName("priority against the background")
    class Priority {
        @Test
        void aSpriteIsDrawnOverOpaqueBackgroundByDefault() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11));
        }

        @Test
        void thePriorityBitPutsItBehind() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, BEHIND_BACKGROUND, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(BACKGROUND_COLOUR), pixelAt(20, 11));
        }

        @Test
        void aSpriteBehindTransparentBackgroundIsStillSeen() {
            // No background tiles at all, so every background pixel is transparent.
            writeSprite(0, 10, SOLID_TILE, BEHIND_BACKGROUND, 20);
            startRendering();
            renderFrames(2);

            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11));
        }

        @Test
        void theLeftmostEightPixelsCanBeHidden() {
            writeSprite(0, 10, SOLID_TILE, 0, 4);
            startRendering(SHOW_BACKGROUND | SHOW_SPRITES | SHOW_BACKGROUND_LEFT);
            renderFrames(2);

            assertEquals(colour(BACKDROP), pixelAt(4, 11), "inside the clipped strip");
            assertEquals(colour(SPRITE_COLOUR), pixelAt(8, 11), "and out the other side of it");
        }
    }

    @Nested
    @DisplayName("the sprite overflow flag")
    class Overflow {
        @Test
        void isSetByNineSpritesOnOneScanline() {
            for (var i = 0; i < 9; i++) {
                writeSprite(i, 10, SOLID_TILE, 0, i * 8);
            }

            startRendering();
            renderThrough(11);

            assertTrue(overflowSet());
        }

        @Test
        void isNotSetByEightSpritesOnOneScanline() {
            for (var i = 0; i < 8; i++) {
                writeSprite(i, 10, SOLID_TILE, 0, i * 8);
            }

            startRendering();
            renderThrough(11);

            assertFalse(overflowSet());
        }

        @Test
        void isClearedAtTheTopOfEveryFrame() {
            for (var i = 0; i < 9; i++) {
                writeSprite(i, 10, SOLID_TILE, 0, i * 8);
            }

            startRendering();
            renderFrames(2);
            runTo(261, 1);

            assertTrue(overflowSet(), "still set right to the end of the frame");

            ppu.tick();
            assertFalse(overflowSet());
        }

        /**
         * A CPU read is one and seven-eighths dots long and only the VBlank flag is latched at the
         * start of it, so a read whose end crosses the clearing dot brings the two halves of
         * $2002 back from either side of it.
         */
        @Test
        void isAlreadyGoneToAReadTheVBlankFlagSurvives() {
            nineSpritesOnAScanline();
            startRendering();
            renderFrames(2);
            runTo(261, 1);

            assertEquals(VBLANK, ppu.read(PPUSTATUS) & 0xE0,
                    "VBlank latched as M2 rose, the sprite flags read a dot later as it fell");
        }

        @Test
        void isStillThereOneDotEarlier() {
            nineSpritesOnAScanline();
            startRendering();
            renderFrames(2);
            runTo(261, 0);

            assertEquals(VBLANK | SPRITE_OVERFLOW, ppu.read(PPUSTATUS) & 0xE0,
                    "M2 falls on dot 0 of the pre-render line, before the clear");
        }

        private void nineSpritesOnAScanline() {
            for (var i = 0; i < 9; i++) {
                writeSprite(i, 10, SOLID_TILE, 0, i * 8);
            }
        }

        /**
         * The bug, in the direction that invents an overflow that is not there.
         * <p>
         * Eight sprites are found, then the ninth is out of range -- and the miss moves the byte
         * index on as well as the sprite index. So the next thing compared against the scanline
         * is not a Y coordinate at all, it is the tenth sprite's <em>tile number</em>, and a tile
         * number that happens to look like a Y coordinate in range sets the flag.
         */
        @Test
        void isSetByATileNumberThatLooksLikeAYCoordinate() {
            for (var i = 0; i < 8; i++) {
                writeSprite(i, 10, SOLID_TILE, 0, i * 8);
            }

            writeSprite(8, 200, SOLID_TILE, 0, 0);  // out of range, so the counter goes wrong
            writeSprite(9, 200, 10, 0, 0);          // tile 10, read as if it were a Y coordinate

            startRendering();
            renderThrough(11);

            assertTrue(overflowSet(), "eight sprites on the line, and the flag set anyway");
        }

        /**
         * The bug, in the direction that misses an overflow that is there.
         * <p>
         * The same wrong counter walks straight past a ninth sprite that really is on the
         * scanline, because by the time it reaches it, it is looking at the wrong byte.
         */
        @Test
        void isNotSetByANinthSpriteTheScanWalksPast() {
            for (var i = 0; i < 8; i++) {
                writeSprite(i, 10, SOLID_TILE, 0, i * 8);
            }

            writeSprite(8, 200, SOLID_TILE, 0, 0);  // out of range, so the counter goes wrong
            writeSprite(9, 10, 0, 0, 0);            // on the scanline, but its tile number is not

            startRendering();
            renderThrough(11);

            assertFalse(overflowSet(), "nine sprites on the line, and no flag");
        }
    }

    @Nested
    @DisplayName("the sprite zero hit flag")
    class SpriteZeroHit {
        @Test
        void isSetWhereSpriteZeroMeetsOpaqueBackground() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 20);

            startRendering();
            renderThrough(11);

            assertTrue(hitSet());
        }

        @Test
        void isSetEvenWhenSpriteZeroIsBehindTheBackground() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, BEHIND_BACKGROUND, 20);

            startRendering();
            renderThrough(11);

            assertTrue(hitSet(), "the flag is about two opaque pixels meeting, not about drawing");
        }

        @Test
        void isNotSetWhereTheBackgroundIsTransparent() {
            writeSprite(0, 10, SOLID_TILE, 0, 20);

            startRendering();
            renderThrough(11);

            assertFalse(hitSet());
        }

        @Test
        void isNotSetByAnySpriteOtherThanZero() {
            fillBackground();
            writeSprite(0, 200, SOLID_TILE, 0, 0);  // sprite 0 well out of the way
            writeSprite(1, 10, SOLID_TILE, 0, 20);

            startRendering();
            renderThrough(11);

            assertFalse(hitSet());
        }

        @Test
        void isNotSetAtTheLastPixelOfAScanline() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 255);

            startRendering();
            renderThrough(11);

            assertFalse(hitSet(), "only the pixel at x=255 overlaps, and that one never counts");
        }

        @Test
        void isNotSetInsideAClippedLeftStrip() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 0);

            // Both left strips clipped, so the only overlap is hidden.
            startRendering(SHOW_BACKGROUND | SHOW_SPRITES);
            renderThrough(11);

            assertFalse(hitSet());
        }

        @Test
        void isNotSetWhenSpritesAreOff() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 20);

            startRendering(SHOW_BACKGROUND | SHOW_BACKGROUND_LEFT);
            renderThrough(11);

            assertFalse(hitSet());
        }

        @Test
        void isClearedAtTheTopOfEveryFrame() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 20);

            startRendering();
            renderFrames(2);
            runTo(261, 1);

            assertTrue(hitSet(), "still set right to the end of the frame");

            ppu.tick();
            assertFalse(hitSet());
        }
    }

    @Nested
    @DisplayName("the debug layer switches")
    class LayerSwitches {
        @Test
        void hidingSpritesRevealsTheBackgroundBehindThem() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 20);
            ppu.setSpriteLayerVisible(false);

            startRendering();
            renderThrough(11);

            assertEquals(colour(BACKGROUND_COLOUR), pixelAt(20, 11),
                    "the background pixel the sprite was covering shows through");
            assertTrue(hitSet(), "the game still sees the hit; only the picture changed");
        }

        @Test
        void hidingTheBackgroundLeavesTheBackdrop() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 20);
            ppu.setBackgroundLayerVisible(false);

            startRendering();
            renderThrough(11);

            assertEquals(colour(BACKDROP), pixelAt(40, 40), "background pixels fall to the backdrop");
            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11), "sprites are still drawn");
            assertTrue(hitSet());
        }

        @Test
        void hidingBothLayersStillSetsTheSpriteZeroHit() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, 0, 20);
            ppu.setBackgroundLayerVisible(false);
            ppu.setSpriteLayerVisible(false);

            startRendering();
            renderThrough(11);

            assertEquals(colour(BACKDROP), pixelAt(20, 11), "nothing is drawn at all");
            assertTrue(hitSet(), "and the game cannot tell");
        }

        @Test
        void spritesBehindTheBackgroundStayHiddenWhenItIsHidden() {
            fillBackground();
            writeSprite(0, 10, SOLID_TILE, BEHIND_BACKGROUND, 20);
            ppu.setBackgroundLayerVisible(false);

            startRendering();
            renderThrough(11);

            assertEquals(colour(BACKDROP), pixelAt(20, 11),
                    "priority still belongs to the background even when it is not drawn");
        }
    }

    @Nested
    @DisplayName("what rendering does to OAMADDR")
    class OamAddress {
        /**
         * There is no way to read OAMADDR back, so this asks where the next $2004 write lands.
         */
        @Test
        void isHeldAtZeroForTheWholeSpriteFetchPhase() {
            startRendering();

            // Set it after one scanline's fetch phase has gone by, so that the next one is what
            // clears it rather than anything that happened before.
            runTo(50, 330);
            ppu.write(OAMADDR, 0x40);

            runTo(52, 0);
            ppu.write(PPUMASK, 0x00);
            run(4);

            ppu.write(OAMDATA, 0x5A);

            ppu.write(OAMADDR, 0x00);
            assertEquals(0x5A, ppu.read(OAMDATA), "the write landed at the start of OAM");

            ppu.write(OAMADDR, 0x40);
            assertNotEquals(0x5A, ppu.read(OAMDATA), "and not where OAMADDR was left pointing");
        }
    }

    // ---------------------------------------------------------------- helpers

    private void solidTile(final int tile, final int value) {
        for (var row = 0; row < 8; row++) {
            writeVRAM(tile * 16 + row, (value & 1) != 0 ? 0xFF : 0x00);
            writeVRAM(tile * 16 + 8 + row, (value & 2) != 0 ? 0xFF : 0x00);
        }
    }

    private void fillBackground() {
        fillVRAM(NAMETABLE_0, TILE_BYTES, SOLID_TILE);
        fillVRAM(NAMETABLE_0 + TILE_BYTES, ATTRIBUTE_BYTES, 0);
    }

    private void writeSprite(final int index, final int y, final int tile, final int attributes, final int x) {
        ppu.write(OAMADDR, index * 4);
        ppu.write(OAMDATA, y);
        ppu.write(OAMDATA, tile);
        ppu.write(OAMDATA, attributes);
        ppu.write(OAMDATA, x);
    }

    private void startRendering() {
        startRendering(SHOW_EVERYTHING, 0x00);
    }

    private void startRendering(final int maskBits) {
        startRendering(maskBits, 0x00);
    }

    private void startRendering(final int maskBits, final int ctrlBits) {
        ppu.write(PPUCTRL, ctrlBits);
        ppu.read(PPUSTATUS);
        ppu.write(PPUSCROLL, 0);
        ppu.write(PPUSCROLL, 0);
        ppu.write(PPUMASK, maskBits);
    }

    /**
     * Renders a settled frame and stops partway down the next one, so that a status flag set on
     * an earlier scanline can be looked at before the pre-render line clears it.
     */
    private void renderThrough(final int scanline) {
        renderFrames(2);
        runTo(scanline + 1, 0);
    }

    private boolean overflowSet() {
        return (ppu.peek(PPUSTATUS) & SPRITE_OVERFLOW) != 0;
    }

    private boolean hitSet() {
        return (ppu.peek(PPUSTATUS) & SPRITE_ZERO_HIT) != 0;
    }

    /**
     * What the framebuffer holds for a palette entry drawn with no emphasis. The PPU writes colour
     * indices rather than colours, so this is only the six bits the hardware keeps.
     */
    private int colour(final int entry) {
        return entry & 0x3F;
    }
}
