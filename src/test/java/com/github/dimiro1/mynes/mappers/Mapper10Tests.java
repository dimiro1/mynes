package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.VRAM;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MMC4: MMC2's self-switching pattern tables on a board with a bigger window and a battery.
 */
class Mapper10Tests {
    @Nested
    @DisplayName("PRG banking")
    class PrgBanking {
        @Test
        void theTwoWindowsAreSixteenKilobytesWide() {
            var mapper = mmc4();

            assertEquals(0, mapper.prgRead(0x8000), "bank 0 on power up");
            assertEquals(0, mapper.prgRead(0xBFFF));
            assertEquals(7, mapper.prgRead(0xC000), "the last bank");
            assertEquals(7, mapper.prgRead(0xFFFF), "with the vectors in it");
        }

        @Test
        void theLowWindowFollowsTheRegisterAtAThousand() {
            var mapper = mmc4();

            mapper.prgWrite(0xA000, 5);

            assertEquals(5, mapper.prgRead(0x8000));
            assertEquals(5, mapper.prgRead(0xBFFF), "for the whole window");
            assertEquals(7, mapper.prgRead(0xC000), "the fixed half is untouched");
        }

        @ParameterizedTest(name = "a write to ${0} is not a bank switch")
        @ValueSource(ints = {0x8000, 0x9000, 0x9FFF})
        void nothingOnTheBoardAnswersBelowAThousand(final int address) {
            var mapper = mmc4();

            mapper.prgWrite(address, 5);

            assertEquals(0, mapper.prgRead(0x8000));
        }

        @Test
        void theBankNumberIsFoldedThroughHowManyBanksThereAre() {
            var mapper = mmc4();

            mapper.prgWrite(0xA000, 0x0A);

            assertEquals(2, mapper.prgRead(0x8000), "an eight bank chip decodes three bits");
        }
    }

    @Nested
    @DisplayName("CHR banking")
    class ChrBanking {
        @Test
        void eachOfTheFourRegistersFillsOneSlotOfOnePair() {
            var mapper = mmc4();

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
            var mapper = mmc4();

            mapper.prgWrite(0xB000, 6);
            mapper.prgWrite(0xD000, 9);

            assertEquals(6, mapper.charRead(0x0000));
            assertEquals(6, mapper.charRead(0x0FFF));
            assertEquals(9, mapper.charRead(0x1000));
            assertEquals(9, mapper.charRead(0x1FFF));
        }
    }

    @Nested
    @DisplayName("the latch")
    class Latch {
        @Test
        void movesOneBusCycleAfterTheAddressThatTripsIt() {
            var mapper = mmc4();
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
            var mapper = mmc4();
            mapper.prgWrite(0xB000, 3);
            mapper.prgWrite(0xC000, 7);

            var vram = new VRAM(mapper);

            vram.peek(0x0FE8);
            vram.peek(0x0FE8);

            assertEquals(3, vram.peek(0x0000), "peek never puts the address on the bus");

            assertEquals(3, vram.read(0x0FE8), "and the fetch that does still reads the old bank");
            assertEquals(7, vram.read(0x0000), "the one after it sees the switch");
        }

        @ParameterizedTest(name = "$${0} moves the lower latch, where MMC2 wanted $0FE8 exactly")
        @ValueSource(ints = {0x0FE8, 0x0FE9, 0x0FEF})
        void answersToAnEightByteRangeInBothWindows(final int address) {
            var mmc4 = mmc4();
            mmc4.prgWrite(0xB000, 3);
            mmc4.prgWrite(0xC000, 7);

            var mmc2 = new Mapper9(StampedROM.of(8, 0x2000), chrROM(), Mirroring.VERTICAL);
            mmc2.prgWrite(0xB000, 3);
            mmc2.prgWrite(0xC000, 7);

            settle(mmc4, address);
            mmc2.ppuAddress(address);
            mmc2.ppuAddress(0x2000);

            assertEquals(7, mmc4.charRead(0x0000));
            assertEquals(
                    address == 0x0FE8 ? 7 : 3,
                    mmc2.charRead(0x0000),
                    "the one line the two chips disagree on"
            );
        }

        @ParameterizedTest(name = "$${0} moves the upper latch")
        @ValueSource(ints = {0x1FD8, 0x1FDF})
        void andToTheSameRangesInTheUpperOne(final int address) {
            var mapper = mmc4();
            mapper.prgWrite(0xD000, 4);
            mapper.prgWrite(0xE000, 9);

            settle(mapper, 0x1FE8);
            settle(mapper, address);

            assertEquals(4, mapper.charRead(0x1000));
        }

        @ParameterizedTest(name = "$${0} does not")
        @ValueSource(ints = {0x0FD0, 0x0FF0, 0x1FE7, 0x1FF0})
        void andToNothingOutsideThem(final int address) {
            var mapper = mmc4();
            mapper.prgWrite(0xB000, 3);
            mapper.prgWrite(0xC000, 7);
            mapper.prgWrite(0xD000, 4);
            mapper.prgWrite(0xE000, 9);

            settle(mapper, address);

            assertEquals(3, mapper.charRead(0x0000));
            assertEquals(4, mapper.charRead(0x1000));
        }
    }

    @Nested
    @DisplayName("the rest of the board")
    class Board {
        @Test
        void mirroringComesFromBitZeroOfEfThousand() {
            var mapper = mmc4();

            assertEquals(Mirroring.VERTICAL, mapper.mirroring(), "as the header asked for");

            mapper.prgWrite(0xF000, 1);

            assertEquals(Mirroring.HORIZONTAL, mapper.mirroring());

            mapper.prgWrite(0xF000, 0);

            assertEquals(Mirroring.VERTICAL, mapper.mirroring());
        }

        @Test
        void thereIsEightKilobytesOfCartridgeRamAtSixThousand() {
            var mapper = mmc4();

            mapper.prgRAMWrite(0x6000, 0x11);
            mapper.prgRAMWrite(0x7FFF, 0x22);

            assertEquals(0x11, mapper.prgRAMRead(0x6000));
            assertEquals(0x22, mapper.prgRAMRead(0x7FFF));
        }

        @Test
        void thatRamIsWhatABatteryWouldHold() {
            var mapper = mmc4();

            mapper.prgRAMWrite(0x6000, 0x42);

            assertEquals(0x2000, mapper.prgRAM().length, "Fire Emblem has a save to make");
            assertEquals(0x42, mapper.prgRAM()[0], "the chip itself, not the bus in front of it");
        }
    }

    private static void settle(final Mapper10 mapper, final int address) {
        mapper.ppuAddress(address);
        mapper.ppuAddress(0x2000);
    }

    /**
     * A Fire Emblem shaped cartridge: 128KB of PRG in 16KB banks and 128KB of CHR in 4KB ones,
     * every byte saying which bank it is in.
     */
    private static Mapper10 mmc4() {
        return new Mapper10(StampedROM.of(8, 0x4000), chrROM(), Mirroring.VERTICAL);
    }

    private static byte[] chrROM() {
        return StampedROM.of(32, 0x1000);
    }
}
