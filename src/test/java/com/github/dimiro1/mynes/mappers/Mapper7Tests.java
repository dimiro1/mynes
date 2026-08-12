package com.github.dimiro1.mynes.mappers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AxROM: one 32KB window that moves as a whole, and a mirroring bit in the same latch.
 */
class Mapper7Tests {
    @Nested
    @DisplayName("PRG banking")
    class PrgBanking {
        @Test
        void theWindowIsThirtyTwoKilobytesWide() {
            var rom = new byte[2 * 0x8000];
            rom[0x0000] = 0x11;
            rom[0x7FFF] = 0x22;
            rom[0x8000] = 0x33;
            rom[0xFFFF] = 0x44;

            var mapper = axrom(rom);

            assertEquals(0x11, mapper.prgRead(0x8000));
            assertEquals(0x22, mapper.prgRead(0xFFFF));

            mapper.prgWrite(0x8000, 1);

            assertEquals(0x33, mapper.prgRead(0x8000));
            assertEquals(0x44, mapper.prgRead(0xFFFF), "the vectors moved with everything else");
        }

        @Test
        void nothingStaysStill() {
            var mapper = axrom(4);

            assertEquals(0, mapper.prgRead(0x8000), "bank 0 on power up");

            mapper.prgWrite(0x8000, 3);

            assertEquals(3, mapper.prgRead(0x8000));
            assertEquals(3, mapper.prgRead(0xFFFF), "including where the vectors are");
        }

        @ParameterizedTest(name = "a write to ${0} loads the latch")
        @ValueSource(ints = {0x8000, 0xABCD, 0xFFFF})
        void anyAddressAboveEightThousandIsTheLatch(final int address) {
            var mapper = axrom(4);

            mapper.prgWrite(address, 2);

            assertEquals(2, mapper.prgRead(0x8000));
        }

        @Test
        void theBankNumberIsFoldedThroughHowManyBanksThereAre() {
            var mapper = axrom(4);

            mapper.prgWrite(0x8000, 0x06);

            assertEquals(2, mapper.prgRead(0x8000), "a four bank board decodes two bits");
        }

        @Test
        void theMirroringBitIsNotPartOfTheBankNumber() {
            var mapper = axrom(4);

            mapper.prgWrite(0x8000, 0x11);

            assertEquals(1, mapper.prgRead(0x8000), "bit 4 belongs to CIRAM A10, not to the bank");
        }

        @Test
        void aChipSmallerThanTheWindowFoldsBackOnItself() {
            var rom = new byte[0x4000];
            rom[0x0000] = 0x5A;

            var mapper = axrom(rom);

            assertEquals(0x5A, mapper.prgRead(0x8000));
            assertEquals(0x5A, mapper.prgRead(0xC000), "the same half seen twice");
        }
    }

    @Nested
    @DisplayName("mirroring")
    class MirroringRegister {
        @Test
        void isSingleScreenAndComesFromBitFour() {
            var mapper = axrom(4);

            assertEquals(Mirroring.ONE_SCREEN_LOW, mapper.mirroring(), "the bit is clear on power up");

            mapper.prgWrite(0x8000, 0x10);

            assertEquals(Mirroring.ONE_SCREEN_HIGH, mapper.mirroring());

            mapper.prgWrite(0x8000, 0x00);

            assertEquals(Mirroring.ONE_SCREEN_LOW, mapper.mirroring());
        }

        @Test
        void ignoresWhateverTheHeaderSaid() {
            var mapper = new Mapper7(StampedROM.of(2, 0x8000), new byte[0], Mirroring.HORIZONTAL);

            assertEquals(
                    Mirroring.ONE_SCREEN_LOW,
                    mapper.mirroring(),
                    "the board cannot wire the nametables side by side, so the header is noise"
            );
        }
    }

    @Nested
    @DisplayName("the rest of the board")
    class Board {
        @Test
        void thePatternTablesAreEightKilobytesOfRam() {
            var mapper = axrom(2);

            mapper.charWrite(0x0000, 0x11);
            mapper.charWrite(0x1FFF, 0x22);

            assertEquals(0x11, mapper.charRead(0x0000));
            assertEquals(0x22, mapper.charRead(0x1FFF));
        }

        @Test
        void aCartCarryingChrRomIsReadOnlyInstead() {
            var chrROM = new byte[0x2000];
            chrROM[0x100] = 0x5A;

            var mapper = new Mapper7(StampedROM.of(2, 0x8000), chrROM, Mirroring.VERTICAL);
            mapper.charWrite(0x100, 0x99);

            assertEquals(0x5A, mapper.charRead(0x100));
        }

        @Test
        void thereIsNoCartridgeRamAtSixThousand() {
            var mapper = axrom(2);

            mapper.prgRAMWrite(0x6000, 0x42);

            assertEquals(0, mapper.prgRAMRead(0x6000), "nothing on the board answers there");
            assertEquals(0, mapper.prgRAM().length, "an empty array is what says there is no chip");
        }
    }

    private static Mapper7 axrom(final int banks) {
        return new Mapper7(StampedROM.of(banks, 0x8000), new byte[0], Mirroring.VERTICAL);
    }

    private static Mapper7 axrom(final byte[] prgROM) {
        return new Mapper7(prgROM, new byte[0], Mirroring.VERTICAL);
    }
}
