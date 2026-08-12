package com.github.dimiro1.mynes.mappers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Color Dreams: one write carries both bank numbers, PRG in the low bits and CHR in the high.
 */
class Mapper11Tests {
    @Nested
    @DisplayName("banking")
    class Banking {
        @Test
        void theLowTwoBitsChooseThirtyTwoKilobytesOfProgram() {
            var mapper = colorDreams(4, 4);

            assertEquals(0, mapper.prgRead(0x8000), "bank 0 on power up");

            mapper.prgWrite(0x8000, 0x02);

            assertEquals(2, mapper.prgRead(0x8000));
            assertEquals(2, mapper.prgRead(0xFFFF), "for the whole window, vectors included");
        }

        @Test
        void theHighFourBitsChooseEightKilobytesOfCharacter() {
            var mapper = colorDreams(4, 16);

            assertEquals(0, mapper.charRead(0x0000), "bank 0 on power up");

            mapper.prgWrite(0x8000, 0x90);

            assertEquals(9, mapper.charRead(0x0000));
            assertEquals(9, mapper.charRead(0x1FFF), "for the whole window");
        }

        @Test
        void oneWriteMovesBothChips() {
            var mapper = colorDreams(4, 16);

            mapper.prgWrite(0x8000, 0x53);

            assertEquals(3, mapper.prgRead(0x8000));
            assertEquals(5, mapper.charRead(0x0000));
        }

        @Test
        void eachBankNumberIsFoldedThroughItsOwnChip() {
            var mapper = colorDreams(2, 2);

            mapper.prgWrite(0x8000, 0xF3);

            assertEquals(1, mapper.prgRead(0x8000), "a two bank chip decodes one bit");
            assertEquals(1, mapper.charRead(0x0000), "and so does the other one");
        }
    }

    @Nested
    @DisplayName("the rest of the board")
    class Board {
        @Test
        void mirroringIsWhateverWasSolderedIn() {
            assertEquals(Mirroring.VERTICAL, colorDreams(2, 2).mirroring());
            assertEquals(
                    Mirroring.HORIZONTAL,
                    new Mapper11(new byte[0x8000], new byte[0x2000], Mirroring.HORIZONTAL).mirroring()
            );
        }

        @Test
        void thePatternTablesAreReadOnly() {
            var chrROM = new byte[0x2000];
            chrROM[0x100] = 0x5A;

            var mapper = new Mapper11(new byte[0x8000], chrROM, Mirroring.VERTICAL);
            mapper.charWrite(0x100, 0x99);

            assertEquals(0x5A, mapper.charRead(0x100), "ROM ignores the write");
        }

        @Test
        void aHeaderNamingNoChrBanksGetsRamToWriteInto() {
            var mapper = new Mapper11(new byte[0x8000], new byte[0], Mirroring.VERTICAL);

            mapper.charWrite(0x0100, 0x99);

            assertEquals(0x99, mapper.charRead(0x0100));
        }

        @Test
        void thereIsNoCartridgeRamAtSixThousand() {
            var mapper = colorDreams(2, 2);

            mapper.prgRAMWrite(0x6000, 0x42);

            assertEquals(0, mapper.prgRAMRead(0x6000), "nothing on the board answers there");
            assertEquals(0, mapper.prgRAM().length, "an empty array is what says there is no chip");
        }
    }

    private static Mapper11 colorDreams(final int prgBanks, final int chrBanks) {
        return new Mapper11(
                StampedROM.of(prgBanks, 0x8000), StampedROM.of(chrBanks, 0x2000), Mirroring.VERTICAL);
    }
}
