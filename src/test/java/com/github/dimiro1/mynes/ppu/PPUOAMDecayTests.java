package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.PPU;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * OAM decay, which is the same idea as the open bus decay next door one level coarser.
 * <p>
 * OAM is DRAM with no refresh circuit of its own. The sprite evaluation hardware reads all of it
 * once per scanline, which is enough to keep it alive -- but only while rendering is enabled, and
 * on an NTSC PPU nothing refreshes it while rendering is off. A row left alone for longer than a
 * vertical blank or so loses its charge, and the bits that have gone read back as zero.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_OAM">NESdev: PPU OAM</a>
 */
class PPUOAMDecayTests extends PPUFixture {
    /**
     * How many dots a row of OAM holds its charge for.
     */
    private static final int DECAY_DOTS = 9000;

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

    private int[] masterPalette;

    @BeforeEach
    void setUp() {
        createWarmPPU();
        masterPalette = PPU.getPalette();
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
        private void placeASprite() {
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

        private void startRendering() {
            ppu.read(PPUSTATUS);
            ppu.write(PPUSCROLL, 0);
            ppu.write(PPUSCROLL, 0);
            ppu.write(PPUMASK, SHOW_EVERYTHING);
        }

        private int colour(final int entry) {
            return masterPalette[entry & 0x3F];
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
