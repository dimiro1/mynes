package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.VRAM;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MMC2: pattern tables that switch themselves out of what the PPU last looked at.
 */
class Mapper9Tests {
    @Nested
    @DisplayName("PRG banking")
    class PrgBanking {
        @Test
        void onlyTheFirstEightKilobytesMove() {
            var mapper = mmc2();

            assertEquals(0, mapper.prgRead(0x8000), "bank 0 on power up");
            assertEquals(13, mapper.prgRead(0xA000));
            assertEquals(14, mapper.prgRead(0xC000));
            assertEquals(15, mapper.prgRead(0xE000), "the last bank, with the vectors in it");
        }

        @Test
        void theSwitchableWindowFollowsTheRegisterAtAThousand() {
            var mapper = mmc2();

            mapper.prgWrite(0xA000, 5);

            assertEquals(5, mapper.prgRead(0x8000));
            assertEquals(5, mapper.prgRead(0x9FFF), "for the whole window");
            assertEquals(13, mapper.prgRead(0xA000), "the three fixed banks are untouched");
        }

        @ParameterizedTest(name = "a write to ${0} is not a bank switch")
        @ValueSource(ints = {0x8000, 0x9000, 0x9FFF})
        void nothingOnTheBoardAnswersBelowAThousand(final int address) {
            var mapper = mmc2();

            mapper.prgWrite(address, 5);

            assertEquals(0, mapper.prgRead(0x8000));
        }

        @Test
        void theBankNumberIsFoldedThroughHowManyBanksThereAre() {
            var mapper = new Mapper9(StampedROM.of(8, 0x2000), chrROM(), Mirroring.VERTICAL);

            mapper.prgWrite(0xA000, 0x0B);

            assertEquals(3, mapper.prgRead(0x8000), "an eight bank chip decodes three bits");
        }
    }

    @Nested
    @DisplayName("CHR banking")
    class ChrBanking {
        @Test
        void eachOfTheFourRegistersFillsOneSlotOfOnePair() {
            var mapper = mmc2();

            mapper.prgWrite(0xB000, 1);
            mapper.prgWrite(0xC000, 2);
            mapper.prgWrite(0xD000, 3);
            mapper.prgWrite(0xE000, 4);

            assertEquals(1, mapper.charRead(0x0000), "lower window, the $FD bank");
            assertEquals(3, mapper.charRead(0x1000), "upper window, the $FD bank");

            settle(mapper, 0x0FE8);
            settle(mapper, 0x1FE8);

            assertEquals(2, mapper.charRead(0x0000), "lower window, the $FE bank");
            assertEquals(4, mapper.charRead(0x1000), "upper window, the $FE bank");
        }

        @Test
        void aWindowIsFourKilobytesWide() {
            var mapper = mmc2();

            mapper.prgWrite(0xB000, 6);
            mapper.prgWrite(0xD000, 9);

            assertEquals(6, mapper.charRead(0x0000));
            assertEquals(6, mapper.charRead(0x0FFF));
            assertEquals(9, mapper.charRead(0x1000));
            assertEquals(9, mapper.charRead(0x1FFF));
        }

        @Test
        void theBankNumberIsFiveBitsFoldedThroughHowManyBanksThereAre() {
            var mapper = new Mapper9(prgROM(), StampedROM.of(8, 0x1000), Mirroring.VERTICAL);

            mapper.prgWrite(0xB000, 0xFF);

            assertEquals(7, mapper.charRead(0x0000), "an eight bank chip decodes three bits");
        }
    }

    @Nested
    @DisplayName("the latch")
    class Latch {
        @Test
        void movesOneBusCycleAfterTheAddressThatTripsIt() {
            var mapper = mmc2();
            mapper.prgWrite(0xB000, 3);
            mapper.prgWrite(0xC000, 7);

            mapper.ppuAddress(0x0FE8);

            assertEquals(
                    3,
                    mapper.charRead(0x0000),
                    "the fetch that trips it still reads through the bank already selected"
            );

            mapper.ppuAddress(0x0000);

            assertEquals(7, mapper.charRead(0x0000), "the next access is where it lands");
        }

        @Test
        void doesNotMoveWhenTheAddressIsOnlyPeekedAt() {
            var mapper = mmc2();
            mapper.prgWrite(0xB000, 3);
            mapper.prgWrite(0xC000, 7);

            var vram = new VRAM(mapper);

            vram.peek(0x0FE8);
            vram.peek(0x0FE8);

            assertEquals(3, vram.peek(0x0000), "peek never puts the address on the bus");

            assertEquals(3, vram.read(0x0FE8), "and the fetch that does still reads the old bank");
            assertEquals(7, vram.read(0x0000), "the one after it sees the switch");
        }

        @Test
        void isTrippedByANametableFetchGoingPastAsWell() {
            var mapper = mmc2();
            mapper.prgWrite(0xB000, 3);
            mapper.prgWrite(0xC000, 7);

            mapper.ppuAddress(0x0FE8);
            mapper.ppuAddress(0x2000);

            assertEquals(
                    7,
                    mapper.charRead(0x0000),
                    "the cart sees all fourteen address lines, whatever they are pointing at"
            );
        }

        @ParameterizedTest(name = "$${0} moves the lower latch")
        @ValueSource(ints = {0x0FD8, 0x0FE8})
        void answersToTwoExactAddressesInTheLowerWindow(final int address) {
            var mapper = mmc2();
            mapper.prgWrite(0xB000, 3);
            mapper.prgWrite(0xC000, 7);

            settle(mapper, 0x0FE8);
            settle(mapper, address);

            assertEquals(address == 0x0FD8 ? 3 : 7, mapper.charRead(0x0000));
        }

        @ParameterizedTest(name = "$${0} does not")
        @ValueSource(ints = {0x0FD9, 0x0FDF, 0x0FE9, 0x0FEF})
        void andToNothingElseAroundThem(final int address) {
            var mapper = mmc2();
            mapper.prgWrite(0xB000, 3);
            mapper.prgWrite(0xC000, 7);

            settle(mapper, address);

            assertEquals(
                    3,
                    mapper.charRead(0x0000),
                    "MMC2 decodes every address line of the lower half"
            );
        }

        @ParameterizedTest(name = "$${0} moves the upper latch")
        @ValueSource(ints = {0x1FE8, 0x1FE9, 0x1FEF})
        void answersToAnEightByteRangeInTheUpperWindow(final int address) {
            var mapper = mmc2();
            mapper.prgWrite(0xD000, 4);
            mapper.prgWrite(0xE000, 9);

            settle(mapper, address);

            assertEquals(9, mapper.charRead(0x1000));
        }

        @ParameterizedTest(name = "$${0} does not")
        @ValueSource(ints = {0x1FE7, 0x1FF0})
        void andToNothingOnEitherSideOfIt(final int address) {
            var mapper = mmc2();
            mapper.prgWrite(0xD000, 4);
            mapper.prgWrite(0xE000, 9);

            settle(mapper, address);

            assertEquals(4, mapper.charRead(0x1000));
        }

        @Test
        void keepsTheTwoWindowsIndependent() {
            var mapper = mmc2();
            mapper.prgWrite(0xB000, 1);
            mapper.prgWrite(0xC000, 2);
            mapper.prgWrite(0xD000, 3);
            mapper.prgWrite(0xE000, 4);

            settle(mapper, 0x0FE8);

            assertEquals(2, mapper.charRead(0x0000), "the lower window moved");
            assertEquals(3, mapper.charRead(0x1000), "and the upper one did not");
        }
    }

    @Nested
    @DisplayName("the rest of the board")
    class Board {
        @Test
        void mirroringComesFromBitZeroOfEfThousand() {
            var mapper = mmc2();

            assertEquals(Mirroring.VERTICAL, mapper.mirroring(), "as the header asked for");

            mapper.prgWrite(0xF000, 1);

            assertEquals(Mirroring.HORIZONTAL, mapper.mirroring());

            mapper.prgWrite(0xF000, 0);

            assertEquals(Mirroring.VERTICAL, mapper.mirroring());
        }

        @Test
        void thePatternTablesAreReadOnly() {
            var mapper = mmc2();

            mapper.charWrite(0x0100, 0x99);

            assertEquals(0, mapper.charRead(0x0100), "ROM ignores the write");
        }

        @Test
        void thereIsNoCartridgeRamAtSixThousand() {
            var mapper = mmc2();

            mapper.prgRAMWrite(0x6000, 0x42);

            assertEquals(0, mapper.prgRAMRead(0x6000), "Punch-Out!! has no save to make");
            assertEquals(0, mapper.prgRAM().length, "an empty array is what says there is no chip");
        }
    }

    /**
     * Puts one address on the bus and then a harmless one behind it, because the latch is decoded
     * a cycle late and a test wants to see where it ended up rather than where it is going.
     */
    private static void settle(final Mapper9 mapper, final int address) {
        mapper.ppuAddress(address);
        mapper.ppuAddress(0x2000);
    }

    /**
     * A Punch-Out!! shaped cartridge: 128KB of PRG in 8KB banks and 128KB of CHR in 4KB ones,
     * every byte saying which bank it is in.
     */
    private static Mapper9 mmc2() {
        return new Mapper9(prgROM(), chrROM(), Mirroring.VERTICAL);
    }

    private static byte[] prgROM() {
        return StampedROM.of(16, 0x2000);
    }

    private static byte[] chrROM() {
        return StampedROM.of(32, 0x1000);
    }
}
