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
 * The eight registers the CPU can reach, and the three internal ones behind them.
 * <p>
 * The scroll registers are the interesting part: $2005 and $2006 are two ways of writing the same
 * fifteen bit counter, they share one write latch, and reading $2002 resets that latch. Getting
 * that wrong is invisible until a game scrolls.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_scrolling">NESdev: PPU scrolling</a>
 */
class PPURegisterTests extends PPUFixture {
    @BeforeEach
    void setUp() {
        createPPU();
    }

    @Nested
    @DisplayName("the scroll registers")
    class Scrolling {
        /**
         * The worked example from the NESdev scrolling page, one write at a time.
         */
        @Test
        void followTheDocumentedWriteSequence() {
            ppu.write(PPUCTRL, 0x03);
            assertEquals(0x0C00, ppu.getT(), "$2000 supplies the two nametable bits");

            ppu.read(PPUSTATUS);
            assertFalse(ppu.isWriteLatchSet(), "reading $2002 resets the latch");

            ppu.write(PPUSCROLL, 0x7D);
            assertEquals(0x0C0F, ppu.getT(), "the coarse part of X");
            assertEquals(5, ppu.getFineX(), "and the fine part, which never touches t");
            assertTrue(ppu.isWriteLatchSet());

            ppu.write(PPUSCROLL, 0x5E);
            assertEquals(0x6D6F, ppu.getT(), "fine Y at the top, coarse Y in the middle");
            assertFalse(ppu.isWriteLatchSet());

            ppu.write(PPUADDR, 0x3D);
            assertEquals(0x3D6F, ppu.getT(), "the high six bits, and bit 14 cleared with them");

            ppu.write(PPUADDR, 0xF0);
            assertEquals(0x3DF0, ppu.getT());
            assertEquals(0x3DF0, ppu.getV(), "the second write copies t into v");
        }

        @Test
        void theHighAddressWriteClearsBitFourteen() {
            // Fine Y of 7 is the only way to get bit 14 set in the first place.
            ppu.write(PPUSCROLL, 0x00);
            ppu.write(PPUSCROLL, 0xFF);
            assertNotEquals(0, ppu.getT() & 0x4000, "fine Y 7 sets bit 14");

            ppu.read(PPUSTATUS);
            ppu.write(PPUADDR, 0xFF);

            assertEquals(0, ppu.getT() & 0x4000, "$2006 only carries six bits, so bit 14 goes");
        }

        @Test
        void scrollAndAddressWritesShareOneLatch() {
            ppu.write(PPUSCROLL, 0x00);  // takes the latch
            ppu.write(PPUADDR, 0x21);    // and this is the *second* write, not the first

            assertFalse(ppu.isWriteLatchSet());
            assertEquals(0x21, ppu.getT() & 0xFF, "landed as the low byte of the address");
        }

        @Test
        void readingStatusMidSequenceRestartsIt() {
            ppu.write(PPUADDR, 0x21);
            ppu.read(PPUSTATUS);
            ppu.write(PPUADDR, 0x21);

            assertTrue(ppu.isWriteLatchSet(), "the second write started over as a first one");
            assertEquals(0x2100, ppu.getT());
        }
    }

    @Nested
    @DisplayName("object attribute memory")
    class ObjectAttributeMemory {
        @Test
        void writingOamDataMovesTheAddressOnButReadingDoesNot() {
            ppu.write(OAMADDR, 0x10);
            ppu.write(OAMDATA, 0xAA);
            ppu.write(OAMDATA, 0xBB);

            ppu.write(OAMADDR, 0x10);
            assertEquals(0xAA, ppu.read(OAMDATA));
            assertEquals(0xAA, ppu.read(OAMDATA), "a read leaves the address alone");

            ppu.write(OAMADDR, 0x11);
            assertEquals(0xBB, ppu.read(OAMDATA));
        }

        @Test
        void theAddressWrapsAtTheEndOfOam() {
            ppu.write(OAMADDR, 0xFF);
            ppu.write(OAMDATA, 0x42);
            ppu.write(OAMDATA, 0x43);

            ppu.write(OAMADDR, 0xFF);
            assertEquals(0x42, ppu.read(OAMDATA));
            ppu.write(OAMADDR, 0x00);
            assertEquals(0x43, ppu.read(OAMDATA), "wrapped round to the start");
        }

        @Test
        void attributeBytesLoseTheThreeBitsThatHaveNoStorage() {
            ppu.write(OAMADDR, 0x02);
            ppu.write(OAMDATA, 0xFF);

            ppu.write(OAMADDR, 0x02);
            assertEquals(0xE3, ppu.read(OAMDATA), "bits 2 to 4 of an attribute byte do not exist");
        }

        @Test
        void everyFourthByteIsAnAttributeByte() {
            for (var sprite = 0; sprite < 64; sprite++) {
                ppu.write(OAMADDR, sprite * 4 + 2);
                ppu.write(OAMDATA, 0xFF);
            }

            for (var sprite = 0; sprite < 64; sprite++) {
                ppu.write(OAMADDR, sprite * 4 + 2);
                assertEquals(0xE3, ppu.read(OAMDATA), "sprite " + sprite);
            }
        }

        @Test
        void writesAreDroppedDuringRenderingButTheAddressStillMovesByFour() {
            ppu.write(OAMADDR, 0x00);
            ppu.write(OAMDATA, 0x11);

            ppu.write(PPUMASK, 0x08);
            runTo(100, 100);

            ppu.write(OAMADDR, 0x00);
            ppu.write(OAMDATA, 0x99);

            ppu.write(PPUMASK, 0x00);
            run(4);

            // Rendering is off again, so this one lands -- at wherever the dropped write left
            // the address.
            ppu.write(OAMDATA, 0x77);

            ppu.write(OAMADDR, 0x00);
            assertEquals(0x11, ppu.read(OAMDATA), "the dropped byte never landed");

            ppu.write(OAMADDR, 0x04);
            assertEquals(0x77, ppu.read(OAMDATA), "and the address had moved on by four, not one");
        }

        @Test
        void readsDuringSecondaryOamClearSeeTheFillValue() {
            ppu.write(OAMADDR, 0x00);
            ppu.write(OAMDATA, 0x11);
            ppu.write(PPUMASK, 0x08);

            runTo(0, 10);
            ppu.write(OAMADDR, 0x00);
            assertEquals(0xFF, ppu.read(OAMDATA), "dots 1 to 64 are the secondary OAM clear");

            runTo(0, 70);
            ppu.write(OAMADDR, 0x00);
            assertEquals(0x11, ppu.read(OAMDATA), "and afterwards OAM is visible again");
        }
    }

    @Nested
    @DisplayName("the status register")
    class Status {
        @Test
        void readingClearsTheVBlankFlag() {
            runTo(241, 2);

            assertEquals(0x80, ppu.read(PPUSTATUS) & 0x80);
            assertEquals(0x00, ppu.read(PPUSTATUS) & 0x80, "the first read took it");
        }

        @Test
        void writingDoesNotAffectTheVBlankFlag() {
            runTo(241, 2);
            ppu.write(PPUSTATUS, 0x00);

            assertEquals(0x80, ppu.read(PPUSTATUS) & 0x80);
        }

        @Test
        void readingOnTheDotTheFlagWouldGoUpStopsItEntirely() {
            runTo(241, 1);

            assertEquals(0x00, ppu.read(PPUSTATUS) & 0x80, "not up yet");

            ppu.tick();
            assertEquals(0x00, ppu.peek(PPUSTATUS) & 0x80, "and now it never will be");

            runTo(250, 0);
            assertEquals(0x00, ppu.peek(PPUSTATUS) & 0x80, "still nothing, a whole frame's worth");
        }
    }
}
