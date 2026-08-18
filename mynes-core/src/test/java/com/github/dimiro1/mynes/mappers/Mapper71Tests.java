package com.github.dimiro1.mynes.mappers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Camerica BF909x: UxROM's two windows, with the bank latch moved clear of a mirroring register.
 */
class Mapper71Tests {
    @Nested
    @DisplayName("PRG banking")
    class PrgBanking {
        @Test
        void theLowWindowFollowsTheLatch() {
            var mapper = camerica(4);

            assertEquals(0, mapper.prgRead(0x8000), "bank 0 on power up");

            mapper.prgWrite(0xC000, 2);

            assertEquals(2, mapper.prgRead(0x8000));
            assertEquals(2, mapper.prgRead(0xBFFF), "for the whole window");
        }

        @Test
        void theHighWindowIsAlwaysTheLastBank() {
            var mapper = camerica(4);

            mapper.prgWrite(0xC000, 1);

            assertEquals(3, mapper.prgRead(0xC000), "switching the low window leaves it alone");
            assertEquals(3, mapper.prgRead(0xFFFF), "which is why the vectors can live up here");
        }

        @ParameterizedTest(name = "a write to ${0} loads the latch")
        @ValueSource(ints = {0x8000, 0x8FFF, 0xA000, 0xC000, 0xFFFF})
        void everyPageButNineThousandIsTheBankLatch(final int address) {
            var mapper = camerica(4);

            mapper.prgWrite(address, 1);

            assertEquals(1, mapper.prgRead(0x8000));
        }

        @Test
        void theBankNumberIsFoldedThroughHowManyBanksThereAre() {
            var mapper = camerica(4);

            mapper.prgWrite(0xC000, 0x06);

            assertEquals(2, mapper.prgRead(0x8000), "a four bank board decodes two bits");
        }
    }

    @Nested
    @DisplayName("mirroring")
    class MirroringRegister {
        @Test
        void staysWhatTheHeaderSaidUntilSomethingWritesNineThousand() {
            var mapper = new Mapper71(StampedROM.of(4, 0x4000), new byte[0], Mirroring.HORIZONTAL);

            mapper.prgWrite(0xC000, 1);
            mapper.prgWrite(0x8000, 1);

            assertEquals(
                    Mirroring.HORIZONTAL,
                    mapper.mirroring(),
                    "a BF9093 game never writes there and must keep its soldered wiring"
            );
        }

        @ParameterizedTest(name = "a write to ${0} reaches the mirroring register")
        @ValueSource(ints = {0x9000, 0x9800, 0x9FFF})
        void isSingleScreenAndComesFromBitFourOfNineThousand(final int address) {
            var mapper = camerica(4);

            mapper.prgWrite(address, 0x10);

            assertEquals(Mirroring.ONE_SCREEN_HIGH, mapper.mirroring());

            mapper.prgWrite(address, 0x00);

            assertEquals(Mirroring.ONE_SCREEN_LOW, mapper.mirroring());
        }

        @Test
        void aWriteThereIsNotAlsoABankSwitch() {
            var mapper = camerica(4);

            mapper.prgWrite(0xC000, 2);
            mapper.prgWrite(0x9000, 0x11);

            assertEquals(2, mapper.prgRead(0x8000), "the bank the game asked for is still there");
        }
    }

    @Nested
    @DisplayName("the rest of the board")
    class Board {
        @Test
        void thePatternTablesAreEightKilobytesOfRam() {
            var mapper = camerica(2);

            mapper.charWrite(0x0000, 0x11);
            mapper.charWrite(0x1FFF, 0x22);

            assertEquals(0x11, mapper.charRead(0x0000));
            assertEquals(0x22, mapper.charRead(0x1FFF));
        }

        @Test
        void thereIsNoCartridgeRamAtSixThousand() {
            var mapper = camerica(2);

            mapper.prgRAMWrite(0x6000, 0x42);

            assertEquals(0, mapper.prgRAMRead(0x6000), "nothing on the board answers there");
            assertEquals(0, mapper.prgRAM().length, "an empty array is what says there is no chip");
        }
    }

    private static Mapper71 camerica(final int banks) {
        return new Mapper71(StampedROM.of(banks, 0x4000), new byte[0], Mirroring.VERTICAL);
    }
}
