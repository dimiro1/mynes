package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.mappers.Mirroring;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The PPU's address space, reached the only way a game can reach it: through $2006 and $2007.
 * <p>
 * Three separate things live behind those two registers -- cartridge pattern tables, console
 * nametable RAM folded by whatever mirroring the cart wires, and palette RAM inside the chip --
 * and they do not all behave the same way on a read.
 */
class PPUMemoryTests extends PPUFixture {
    @BeforeEach
    void setUp() {
        createPPU();
    }

    @Nested
    @DisplayName("pattern tables")
    class PatternTables {
        @Test
        void routeToTheCartridge() {
            writeVRAM(0x0000, 0x11);
            writeVRAM(0x1FFF, 0x22);

            assertEquals(0x11, mapper.charRead(0x0000), "the PPU should not have its own copy");
            assertEquals(0x22, mapper.charRead(0x1FFF));
            assertEquals(0x11, readVRAM(0x0000));
            assertEquals(0x22, readVRAM(0x1FFF));
        }
    }

    @Nested
    @DisplayName("nametable mirroring")
    class Nametables {
        @ParameterizedTest(name = "horizontal: ${0} and ${1} are the same kilobyte")
        @CsvSource({"0x2000, 0x2400", "0x2800, 0x2C00"})
        void horizontalPairsThemTopAndBottom(final int a, final int b) {
            mapper.setMirroring(Mirroring.HORIZONTAL);

            writeVRAM(a + 0x123, 0x42);
            assertEquals(0x42, readVRAM(b + 0x123));

            writeVRAM(b + 0x123, 0x43);
            assertEquals(0x43, readVRAM(a + 0x123));
        }

        @Test
        void horizontalKeepsTheTwoPairsApart() {
            mapper.setMirroring(Mirroring.HORIZONTAL);

            writeVRAM(0x2000, 0x11);
            writeVRAM(0x2800, 0x22);

            assertEquals(0x11, readVRAM(0x2000));
            assertEquals(0x22, readVRAM(0x2800));
        }

        @ParameterizedTest(name = "vertical: ${0} and ${1} are the same kilobyte")
        @CsvSource({"0x2000, 0x2800", "0x2400, 0x2C00"})
        void verticalPairsThemSideBySide(final int a, final int b) {
            mapper.setMirroring(Mirroring.VERTICAL);

            writeVRAM(a + 0x123, 0x42);
            assertEquals(0x42, readVRAM(b + 0x123));
        }

        @Test
        void verticalKeepsTheTwoPairsApart() {
            mapper.setMirroring(Mirroring.VERTICAL);

            writeVRAM(0x2000, 0x11);
            writeVRAM(0x2400, 0x22);

            assertEquals(0x11, readVRAM(0x2000));
            assertEquals(0x22, readVRAM(0x2400));
        }

        @Test
        void fourScreenKeepsAllFourApart() {
            mapper.setMirroring(Mirroring.FOUR_SCREEN);

            writeVRAM(0x2000, 0x11);
            writeVRAM(0x2400, 0x22);
            writeVRAM(0x2800, 0x33);
            writeVRAM(0x2C00, 0x44);

            assertEquals(0x11, readVRAM(0x2000));
            assertEquals(0x22, readVRAM(0x2400));
            assertEquals(0x33, readVRAM(0x2800));
            assertEquals(0x44, readVRAM(0x2C00));
        }

        @Test
        void the3000RangeIsAPlainMirrorOf2000() {
            writeVRAM(0x2123, 0x42);
            assertEquals(0x42, readVRAM(0x3123));

            writeVRAM(0x3EFF, 0x43);
            assertEquals(0x43, readVRAM(0x2EFF));
        }
    }

    @Nested
    @DisplayName("palette RAM")
    class Palette {
        @Test
        void readsAndWritesBack() {
            writeVRAM(0x3F00, 0x21);
            setVRAMAddress(0x3F00);

            assertEquals(0x21, ppu.read(PPUDATA));
        }

        @Test
        void onlyKeepsSixBits() {
            writeVRAM(0x3F01, 0xFF);
            setVRAMAddress(0x3F01);

            assertEquals(0x3F, ppu.read(PPUDATA) & 0x3F);
        }

        @ParameterizedTest(name = "$3F{0} is the same cell as the backdrop below it")
        @ValueSource(ints = {0x10, 0x14, 0x18, 0x1C})
        void spritePaletteBackdropsMirrorTheBackgroundOnes(final int offset) {
            writeVRAM(0x3F00 + offset, 0x25);
            setVRAMAddress(0x3F00 + (offset & 0x0F));

            assertEquals(0x25, ppu.read(PPUDATA));

            writeVRAM(0x3F00 + (offset & 0x0F), 0x26);
            setVRAMAddress(0x3F00 + offset);

            assertEquals(0x26, ppu.read(PPUDATA));
        }

        @Test
        void ordinarySpriteEntriesAreNotMirrored() {
            writeVRAM(0x3F11, 0x11);
            writeVRAM(0x3F01, 0x22);

            setVRAMAddress(0x3F11);
            assertEquals(0x11, ppu.read(PPUDATA));
        }

        @Test
        void repeatsEveryThirtyTwoBytesUpTo3FFF() {
            writeVRAM(0x3F00, 0x0A);

            for (var mirror = 0x3F20; mirror < 0x4000; mirror += 0x20) {
                setVRAMAddress(mirror);

                // Only six bits are the palette; the top two come off the open bus.
                assertEquals(0x0A, ppu.read(PPUDATA) & 0x3F, String.format("$%04X", mirror));
            }
        }

        @Test
        void readingIsNotBuffered() {
            writeVRAM(0x3F05, 0x2A);
            setVRAMAddress(0x3F05);

            assertEquals(0x2A, ppu.read(PPUDATA) & 0x3F, "no dummy read needed");
        }

        @Test
        void readingStillFillsTheBufferFromTheNametableUnderneath() {
            // $2F05 is the address that shares the bus with $3F05.
            writeVRAM(0x2F05, 0x5A);
            writeVRAM(0x3F05, 0x2A);

            setVRAMAddress(0x3F05);
            assertEquals(0x2A, ppu.read(PPUDATA) & 0x3F, "the palette entry, straight away");

            setVRAMAddress(0x2100);
            assertEquals(0x5A, ppu.read(PPUDATA), "and the buffer was left holding the nametable");
        }

        @Test
        void greyscaleDropsTheHueOnTheWayOut() {
            writeVRAM(0x3F00, 0x25);
            ppu.write(PPUMASK, 0x01);
            run(4);

            setVRAMAddress(0x3F00);
            assertEquals(0x20, ppu.read(PPUDATA) & 0x3F);
        }
    }

    @Nested
    @DisplayName("the $2007 read buffer")
    class ReadBuffer {
        @Test
        void handsOverThePreviousByteFirst() {
            writeVRAM(0x2000, 0xAA);
            writeVRAM(0x2001, 0xBB);

            setVRAMAddress(0x2000);
            assertNotEquals(0xAA, ppu.read(PPUDATA), "the first read is whatever was in the buffer");
            assertEquals(0xAA, ppu.read(PPUDATA), "and now the byte turns up, one read late");
            assertEquals(0xBB, ppu.read(PPUDATA));
        }
    }

    @Nested
    @DisplayName("the address increment")
    class AddressIncrement {
        @Test
        void movesByOneByDefault() {
            ppu.write(PPUCTRL, 0x00);
            setVRAMAddress(0x2000);

            ppu.write(PPUDATA, 0x11);
            ppu.write(PPUDATA, 0x22);

            assertEquals(0x11, readVRAM(0x2000));
            assertEquals(0x22, readVRAM(0x2001));
        }

        @Test
        void movesByThirtyTwoWhenAsked() {
            ppu.write(PPUCTRL, 0x04);
            setVRAMAddress(0x2000);

            ppu.write(PPUDATA, 0x11);
            ppu.write(PPUDATA, 0x22);

            assertEquals(0x11, readVRAM(0x2000));
            assertEquals(0x22, readVRAM(0x2020), "a whole row further down");
        }

        @Test
        void doesBothScrollIncrementsInsteadDuringRendering() {
            ppu.write(PPUCTRL, 0x00);
            ppu.write(PPUMASK, 0x08);
            runTo(50, 100);

            // Zero rather than a nametable address, so that fine Y starts at zero and the two
            // increments are easy to read off: bits 12-14 of v are fine Y, not part of an address.
            setVRAMAddress(0x0000);
            ppu.write(PPUDATA, 0x11);

            // Coarse X moves on by one and fine Y by one, which is 1 + 0x1000 rather than 1.
            assertEquals(0x1001, ppu.getV());
        }
    }
}
