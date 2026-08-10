package com.github.dimiro1.mynes.mappers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * UxROM: a switchable 16KB window at $8000 and the last bank nailed to $C000.
 */
class Mapper2Tests {
    @Nested
    @DisplayName("PRG banking")
    class PrgBanking {
        @Test
        void theTwoWindowsAreSixteenKilobytesWide() {
            var rom = new byte[2 * 0x4000];
            rom[0x0000] = 0x11;
            rom[0x3FFF] = 0x22;
            rom[0x4000] = 0x33;
            rom[0x7FFF] = 0x44;

            var mapper = new Mapper2(rom, new byte[0], Mirroring.VERTICAL);

            assertEquals(0x11, mapper.prgRead(0x8000));
            assertEquals(0x22, mapper.prgRead(0xBFFF));
            assertEquals(0x33, mapper.prgRead(0xC000));
            assertEquals(0x44, mapper.prgRead(0xFFFF));
        }

        @Test
        void theLowWindowFollowsTheLatch() {
            var mapper = uxrom(4);

            assertEquals(0, mapper.prgRead(0x8000), "bank 0 on power up");

            mapper.prgWrite(0x8000, 2);

            assertEquals(2, mapper.prgRead(0x8000));
            assertEquals(2, mapper.prgRead(0xBFFF), "for the whole window");
        }

        @Test
        void theHighWindowIsAlwaysTheLastBank() {
            var mapper = uxrom(4);

            assertEquals(3, mapper.prgRead(0xC000));

            mapper.prgWrite(0x8000, 1);

            assertEquals(3, mapper.prgRead(0xC000), "switching the low window leaves it alone");
            assertEquals(3, mapper.prgRead(0xFFFF), "which is why the vectors can live up here");
        }

        @ParameterizedTest(name = "a write to ${0} loads the latch")
        @ValueSource(ints = {0x8000, 0xABCD, 0xFFFF})
        void anyAddressAboveEightThousandIsTheLatch(final int address) {
            var mapper = uxrom(4);

            mapper.prgWrite(address, 1);

            assertEquals(1, mapper.prgRead(0x8000));
        }

        @Test
        void theBankNumberIsFoldedThroughHowManyBanksThereAre() {
            var mapper = uxrom(4);

            mapper.prgWrite(0x8000, 0x06);

            assertEquals(2, mapper.prgRead(0x8000), "a four bank board decodes two bits");
        }

        @Test
        void aSingleBankBoardShowsThatBankInBothWindows() {
            var mapper = uxrom(1);

            mapper.prgWrite(0x8000, 0x0F);

            assertEquals(0, mapper.prgRead(0x8000));
            assertEquals(0, mapper.prgRead(0xC000));
        }
    }

    @Nested
    @DisplayName("pattern tables")
    class PatternTables {
        @Test
        void areEightKilobytesOfRamWhenTheCartCarriesNoChrRom() {
            var mapper = uxrom(2);

            mapper.charWrite(0x0000, 0x11);
            mapper.charWrite(0x1FFF, 0x22);

            assertEquals(0x11, mapper.charRead(0x0000));
            assertEquals(0x22, mapper.charRead(0x1FFF));
        }

        @Test
        void areReadOnlyWhenTheCartDoesCarryChrRom() {
            var chrROM = new byte[0x2000];
            chrROM[0x100] = 0x5A;

            var mapper = new Mapper2(new byte[0x4000], chrROM, Mirroring.HORIZONTAL);
            mapper.charWrite(0x100, 0x99);

            assertEquals(0x5A, mapper.charRead(0x100), "ROM ignores the write");
        }
    }

    @Nested
    @DisplayName("the rest of the board")
    class Board {
        @Test
        void mirroringIsWhateverWasSolderedIn() {
            assertEquals(Mirroring.VERTICAL, uxrom(2).mirroring());
            assertEquals(
                    Mirroring.HORIZONTAL,
                    new Mapper2(new byte[0x4000], new byte[0], Mirroring.HORIZONTAL).mirroring()
            );
        }

        @Test
        void thereIsNoCartridgeRamAtSixThousand() {
            var mapper = uxrom(2);

            mapper.prgRAMWrite(0x6000, 0x42);

            assertEquals(0, mapper.prgRAMRead(0x6000), "nothing on the board answers there");
        }
    }

    /**
     * A cartridge whose every byte of PRG says which 16KB bank it is in, so a read tells the test
     * which bank is switched in. No CHR ROM, which is what a real UxROM board looks like.
     */
    private static Mapper2 uxrom(final int banks) {
        return new Mapper2(StampedROM.of(banks, 0x4000), new byte[0], Mirroring.VERTICAL);
    }
}
