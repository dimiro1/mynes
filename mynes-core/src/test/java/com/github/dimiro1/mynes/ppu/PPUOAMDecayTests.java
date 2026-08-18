package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OAM decay, which is the same idea as the open bus decay next door one level coarser.
 * <p>
 * OAM is DRAM with no refresh circuit of its own. The sprite evaluation hardware reads all of it
 * once per scanline, which is enough to keep it alive -- but only while rendering is enabled, and
 * nothing refreshes it while rendering is off. A row left alone for longer than a vertical blank or
 * so loses its charge, and the bits that have gone read back as zero.
 * <p>
 * "A vertical blank or so" is the part that is not the same on both machines, and {@link Blanking}
 * is what holds the two of them apart.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_OAM">NESdev: PPU OAM</a>
 */
class PPUOAMDecayTests extends PPUFixture {
    /**
     * How many dots a row of OAM holds its charge for. Taken from the region rather than written
     * out, because it is one figure calibrated against several things at once and a copy of it
     * here would only ever be a second place to have to change.
     */
    private static final int DECAY_DOTS = Region.NTSC.oamDecayDots();

    /**
     * Two bytes of OAM in different eight byte rows, and one more sharing a row with the first.
     */
    private static final int FIRST_ROW_BYTE = 0x10;
    private static final int SAME_ROW_BYTE = 0x14;
    private static final int OTHER_ROW_BYTE = 0x20;

    private static final int BACKDROP = 0x0F;
    private static final int SPRITE_COLOUR = 0x16;
    private static final int SOLID_TILE = 1;

    private static final int SHOW_EVERYTHING = 0x1E;

    @BeforeEach
    void setUp() {
        createWarmPPU();
    }

    @Nested
    @DisplayName("with rendering off")
    class RenderingOff {
        @Test
        void aByteSurvivesJustUnderTheDecayTime() {
            writeOAM(FIRST_ROW_BYTE, 0xAA);

            run(DECAY_DOTS - 1);

            assertEquals(0xAA, readOAM(FIRST_ROW_BYTE));
        }

        @Test
        void aByteReadsBackAsZeroJustOverIt() {
            writeOAM(FIRST_ROW_BYTE, 0xAA);

            run(DECAY_DOTS);

            assertEquals(0x00, readOAM(FIRST_ROW_BYTE), "the charge leaked away");
        }

        @Test
        void aReadRefreshesItsOwnRowAndOnlyItsOwnRow() {
            writeOAM(FIRST_ROW_BYTE, 0xAA);
            writeOAM(OTHER_ROW_BYTE, 0xBB);

            run(DECAY_DOTS - 100);
            readOAM(FIRST_ROW_BYTE);  // which starts that row's clock again
            run(200);

            assertEquals(0xAA, readOAM(FIRST_ROW_BYTE), "the row the read touched is still up");
            assertEquals(0x00, readOAM(OTHER_ROW_BYTE), "the one it did not is not");
        }

        @Test
        void aWriteHoldsUpTheWholeRowItLandsIn() {
            writeOAM(FIRST_ROW_BYTE, 0xAA);
            writeOAM(OTHER_ROW_BYTE, 0xBB);

            run(DECAY_DOTS - 100);
            writeOAM(SAME_ROW_BYTE, 0x00);  // a different byte of the first row
            run(200);

            assertEquals(0xAA, readOAM(FIRST_ROW_BYTE), "one cell of a row refreshes all eight");
            assertEquals(0x00, readOAM(OTHER_ROW_BYTE), "and only those eight");
        }
    }

    @Nested
    @DisplayName("with rendering on")
    class RenderingOn {
        @Test
        void theWholeOfOamStaysAliveIndefinitely() {
            writeOAM(FIRST_ROW_BYTE, 0xAA);

            // The sprite evaluation reads all of OAM once per visible scanline, so several
            // frames' worth of decay time goes by without anything decaying.
            ppu.write(PPUMASK, SHOW_EVERYTHING);
            advanceFrames(4);
            ppu.write(PPUMASK, 0x00);
            run(4);

            assertEquals(0xAA, readOAM(FIRST_ROW_BYTE));
        }
    }

    /**
     * The one thing about decay that is not the same on both machines, and the reason it is a
     * property of the region rather than a constant.
     */
    @Nested
    @DisplayName("across a vertical blank")
    class Blanking {
        @Test
        void oamOutlastsTheBlankingIntervalOnBothMachines() {
            // Nothing refreshes OAM between the last visible scanline of one frame and the first
            // of the next, so the charge has to cover that gap or a game has no sprites at all.
            // It is 23 scanlines on NTSC and 73 on PAL -- and 9000 dots, which comfortably covers
            // the first, does not come close to covering the second.
            for (var region : Region.values()) {
                var blanking = (region.scanlinesPerFrame() - 239) * 341;

                assertTrue(region.oamDecayDots() > blanking,
                        region.label() + " loses every sprite it has once a frame: " + blanking
                                + " dots of blanking against " + region.oamDecayDots()
                                + " of charge");
            }
        }

        @Test
        void aSpriteSurvivesAWholePALFrameOfRendering() {
            // The same thing again through the picture rather than through the numbers, because
            // this is what it looked like when it was wrong: the falling piece in Tetris, and
            // Mario himself, simply were not drawn.
            createPPU(Region.PAL);
            warmUp();

            var sprites = new SpriteHardware();
            sprites.placeASprite();
            sprites.startRendering();
            renderFrames(3);

            assertEquals(sprites.colour(SPRITE_COLOUR), pixelAt(20, 11));
        }
    }

    @Nested
    @DisplayName("what the sprite hardware sees")
    class SpriteHardware {
        @Test
        void aSpriteStillChargedIsDrawn() {
            placeASprite();

            run(DECAY_DOTS / 2);
            startRendering();
            renderFrames(2);

            assertEquals(colour(SPRITE_COLOUR), pixelAt(20, 11));
        }

        @Test
        void aSpriteThatHasDecayedIsNot() {
            placeASprite();

            run(DECAY_DOTS * 2);
            startRendering();
            renderFrames(2);

            // Every byte of OAM decayed together, Y coordinates included, so what the evaluation
            // now finds is sixty four sprites of tile 0 stacked in the top left corner -- and
            // tile 0 is transparent.
            assertEquals(colour(BACKDROP), pixelAt(20, 11), "the sprite went with the rest of OAM");
        }

        /**
         * A visible sprite at (20,11), on an otherwise empty screen.
         */
        void placeASprite() {
            for (var row = 0; row < 8; row++) {
                writeVRAM(SOLID_TILE * 16 + row, 0xFF);
            }

            writeVRAM(0x3F00, BACKDROP);
            writeVRAM(0x3F11, SPRITE_COLOUR);

            // OAM powers up as sixty four sprites at Y=0, which would cover the top of the screen
            // before this one had said anything.
            ppu.write(OAMADDR, 0x00);

            for (var sprite = 0; sprite < 64; sprite++) {
                ppu.write(OAMDATA, 0xFF);
                ppu.write(OAMDATA, 0x00);
                ppu.write(OAMDATA, 0x00);
                ppu.write(OAMDATA, 0x00);
            }

            ppu.write(OAMADDR, 0x00);
            ppu.write(OAMDATA, 10);           // Y
            ppu.write(OAMDATA, SOLID_TILE);
            ppu.write(OAMDATA, 0x00);         // attributes
            ppu.write(OAMDATA, 20);           // X
        }

        void startRendering() {
            ppu.read(PPUSTATUS);
            ppu.write(PPUSCROLL, 0);
            ppu.write(PPUSCROLL, 0);
            ppu.write(PPUMASK, SHOW_EVERYTHING);
        }

        /**
         * What the framebuffer holds for a palette entry drawn with no emphasis. The PPU writes colour
         * indices rather than colours, so this is only the six bits the hardware keeps.
         */
        int colour(final int entry) {
            return entry & 0x3F;
        }
    }

    // ---------------------------------------------------------------- helpers

    private void writeOAM(final int address, final int value) {
        ppu.write(OAMADDR, address);
        ppu.write(OAMDATA, value);
    }

    private int readOAM(final int address) {
        ppu.write(OAMADDR, address);
        return ppu.read(OAMDATA);
    }
}
