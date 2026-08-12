package com.github.dimiro1.mynes.mappers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GxROM: Color Dreams' latch with the two halves swapped over.
 */
class Mapper66Tests {
    @Nested
    @DisplayName("banking")
    class Banking {
        @Test
        void bitsFourAndFiveChooseThirtyTwoKilobytesOfProgram() {
            var mapper = gxrom(4, 4);

            assertEquals(0, mapper.prgRead(0x8000), "bank 0 on power up");

            mapper.prgWrite(0x8000, 0x20);

            assertEquals(2, mapper.prgRead(0x8000));
            assertEquals(2, mapper.prgRead(0xFFFF), "for the whole window, vectors included");
        }

        @Test
        void theLowTwoBitsChooseEightKilobytesOfCharacter() {
            var mapper = gxrom(4, 4);

            mapper.prgWrite(0x8000, 0x03);

            assertEquals(3, mapper.charRead(0x0000));
            assertEquals(3, mapper.charRead(0x1FFF), "for the whole window");
        }

        @Test
        void oneWriteMovesBothChips() {
            var mapper = gxrom(4, 4);

            mapper.prgWrite(0x8000, 0x31);

            assertEquals(3, mapper.prgRead(0x8000));
            assertEquals(1, mapper.charRead(0x0000));
        }

        @Test
        void theNibblesAreTheOtherWayRoundFromColorDreams() {
            var gxrom = gxrom(4, 4);
            var colorDreams = new Mapper11(
                    StampedROM.of(4, 0x8000), StampedROM.of(4, 0x2000), Mirroring.VERTICAL);

            gxrom.prgWrite(0x8000, 0x12);
            colorDreams.prgWrite(0x8000, 0x12);

            assertEquals(1, gxrom.prgRead(0x8000));
            assertEquals(2, colorDreams.prgRead(0x8000));
        }

        @Test
        void eachBankNumberIsFoldedThroughItsOwnChip() {
            var mapper = gxrom(2, 2);

            mapper.prgWrite(0x8000, 0x33);

            assertEquals(1, mapper.prgRead(0x8000), "a two bank chip decodes one bit");
            assertEquals(1, mapper.charRead(0x0000), "and so does the other one");
        }

        @Test
        void aChipSmallerThanTheWindowFoldsBackOnItself() {
            var prgROM = new byte[0x4000];
            prgROM[0x0000] = 0x5A;

            var mapper = new Mapper66(prgROM, StampedROM.of(2, 0x2000), Mirroring.VERTICAL);

            assertEquals(0x5A, mapper.prgRead(0x8000));
            assertEquals(0x5A, mapper.prgRead(0xC000), "the same half seen twice");
        }
    }

    @Nested
    @DisplayName("the rest of the board")
    class Board {
        @Test
        void mirroringIsWhateverWasSolderedIn() {
            assertEquals(Mirroring.VERTICAL, gxrom(2, 2).mirroring());
            assertEquals(
                    Mirroring.HORIZONTAL,
                    new Mapper66(new byte[0x8000], new byte[0x2000], Mirroring.HORIZONTAL).mirroring()
            );
        }

        @Test
        void thePatternTablesAreReadOnly() {
            var chrROM = new byte[0x2000];
            chrROM[0x100] = 0x5A;

            var mapper = new Mapper66(new byte[0x8000], chrROM, Mirroring.VERTICAL);
            mapper.charWrite(0x100, 0x99);

            assertEquals(0x5A, mapper.charRead(0x100), "ROM ignores the write");
        }

        @Test
        void thereIsNoCartridgeRamAtSixThousand() {
            var mapper = gxrom(2, 2);

            mapper.prgRAMWrite(0x6000, 0x42);

            assertEquals(0, mapper.prgRAMRead(0x6000), "nothing on the board answers there");
            assertEquals(0, mapper.prgRAM().length, "an empty array is what says there is no chip");
        }
    }

    private static Mapper66 gxrom(final int prgBanks, final int chrBanks) {
        return new Mapper66(
                StampedROM.of(prgBanks, 0x8000), StampedROM.of(chrBanks, 0x2000), Mirroring.VERTICAL);
    }
}
