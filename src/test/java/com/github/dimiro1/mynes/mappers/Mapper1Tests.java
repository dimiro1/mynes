package com.github.dimiro1.mynes.mappers;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * MMC1, which is loaded one bit at a time.
 * <p>
 * Every register write in here goes through {@link #load}, five writes of one bit each, because
 * that is the only way a game can reach the chip.
 */
class Mapper1Tests {
    /**
     * Control register values. The low two bits are mirroring, bits 2-3 the PRG mode and bit 4
     * the CHR mode.
     */
    private static final int PRG_MODE_32K = 0x00;
    private static final int PRG_MODE_FIXED_FIRST = 0x08;
    private static final int PRG_MODE_FIXED_LAST = 0x0C;
    private static final int CHR_MODE_4K = 0x10;

    // Where the fifth write of a sequence has to land for each register.
    private static final int CONTROL = 0x8000;
    private static final int CHR_BANK_0 = 0xA000;
    private static final int CHR_BANK_1 = 0xC000;
    private static final int PRG_BANK = 0xE000;

    @Nested
    @DisplayName("the serial port")
    class SerialPort {
        @Test
        void takesFiveWritesToCommitAnything() {
            var mapper = mmc1(4);

            for (var i = 0; i < 4; i++) {
                mapper.prgWrite(PRG_BANK, (2 >> i) & 1);
                assertEquals(0, mapper.prgRead(0x8000), "nothing until the fifth bit");
            }

            mapper.prgWrite(PRG_BANK, 0);

            assertEquals(2, mapper.prgRead(0x8000));
        }

        @Test
        void theFifthWritesAddressPicksTheRegister() {
            var mapper = mmc1(4);

            // Four bits shifted in at the control register's address, the fifth at the PRG bank
            // register's. The fifth is the one that decides where all five land.
            for (var i = 0; i < 4; i++) {
                mapper.prgWrite(CONTROL, (2 >> i) & 1);
            }
            mapper.prgWrite(PRG_BANK, 0);

            assertEquals(2, mapper.prgRead(0x8000), "the PRG bank moved, so control was not hit");
            assertEquals(3, mapper.prgRead(0xC000), "and the mode is still the power-on one");
        }

        @Test
        void aWriteWithBitSevenSetThrowsAwayWhatWasHalfLoaded() {
            var mapper = mmc1(4);

            mapper.prgWrite(PRG_BANK, 1);
            mapper.prgWrite(PRG_BANK, 1);
            mapper.prgWrite(PRG_BANK, 0);
            mapper.prgWrite(PRG_BANK, 0x80);
            mapper.prgWrite(PRG_BANK, 0);
            mapper.prgWrite(PRG_BANK, 0);

            assertEquals(0, mapper.prgRead(0x8000), "six writes, and none of them committed");
        }

        @Test
        void aWriteWithBitSevenSetAlsoPutsTheLastBankBackAtCThousand() {
            var mapper = mmc1(4);
            load(mapper, CONTROL, PRG_MODE_FIXED_FIRST);

            assertEquals(0, mapper.prgRead(0xC000), "bank 0 in both halves for now");

            mapper.prgWrite(CONTROL, 0x80);

            assertEquals(3, mapper.prgRead(0xC000), "which is how a game finds its vectors again");
        }
    }

    @Nested
    @DisplayName("PRG banking")
    class PrgBanking {
        @Test
        void powersOnWithTheLastBankFixedAtCThousand() {
            var mapper = mmc1(4);

            assertEquals(0, mapper.prgRead(0x8000), "the switchable window starts on bank 0");
            assertEquals(3, mapper.prgRead(0xC000));
        }

        @Test
        void modeThreeSwitchesTheWindowBelowTheFixedOne() {
            var mapper = mmc1(4);
            load(mapper, CONTROL, PRG_MODE_FIXED_LAST);
            load(mapper, PRG_BANK, 2);

            assertEquals(2, mapper.prgRead(0x8000));
            assertEquals(2, mapper.prgRead(0xBFFF));
            assertEquals(3, mapper.prgRead(0xC000));
        }

        @Test
        void modeTwoFixesTheFirstBankAndSwitchesTheOneAboveIt() {
            var mapper = mmc1(4);
            load(mapper, CONTROL, PRG_MODE_FIXED_FIRST);
            load(mapper, PRG_BANK, 2);

            assertEquals(0, mapper.prgRead(0x8000));
            assertEquals(2, mapper.prgRead(0xC000));
        }

        @ParameterizedTest(name = "control ${0} is one 32KB bank")
        @ValueSource(ints = {PRG_MODE_32K, 0x04})
        void bothLowModesSwitchTheWholeWindowAtOnce(final int control) {
            var mapper = mmc1(4);
            load(mapper, CONTROL, control);
            load(mapper, PRG_BANK, 2);

            assertEquals(2, mapper.prgRead(0x8000));
            assertEquals(3, mapper.prgRead(0xC000), "the bank above it, not the last one");
        }

        @Test
        void theThirtyTwoKilobyteModesIgnoreTheBankNumbersLowBit() {
            var mapper = mmc1(4);
            load(mapper, CONTROL, PRG_MODE_32K);
            load(mapper, PRG_BANK, 3);

            assertEquals(2, mapper.prgRead(0x8000), "bank 3 names the pair that starts at 2");
            assertEquals(3, mapper.prgRead(0xC000));
        }

        @Test
        void theBankNumberIsFoldedThroughHowManyBanksThereAre() {
            var mapper = mmc1(4);
            load(mapper, PRG_BANK, 0x0E);

            assertEquals(2, mapper.prgRead(0x8000), "a four bank board decodes two bits");
        }
    }

    @Nested
    @DisplayName("CHR banking")
    class ChrBanking {
        @Test
        void fourKilobyteModeSwitchesThePatternTablesSeparately() {
            var mapper = mmc1(2, 8);
            load(mapper, CONTROL, PRG_MODE_FIXED_LAST | CHR_MODE_4K);
            load(mapper, CHR_BANK_0, 3);
            load(mapper, CHR_BANK_1, 5);

            assertEquals(3, mapper.charRead(0x0000));
            assertEquals(3, mapper.charRead(0x0FFF));
            assertEquals(5, mapper.charRead(0x1000));
            assertEquals(5, mapper.charRead(0x1FFF));
        }

        @Test
        void eightKilobyteModeTakesAPairAndIgnoresTheSecondRegister() {
            var mapper = mmc1(2, 8);
            load(mapper, CONTROL, PRG_MODE_FIXED_LAST);
            load(mapper, CHR_BANK_0, 5);
            load(mapper, CHR_BANK_1, 7);

            assertEquals(4, mapper.charRead(0x0000), "bank 5 names the pair that starts at 4");
            assertEquals(5, mapper.charRead(0x1000));
        }

        @Test
        void chrRamTakesWrites() {
            var mapper = mmc1(2);

            mapper.charWrite(0x0000, 0x11);
            mapper.charWrite(0x1FFF, 0x22);

            assertEquals(0x11, mapper.charRead(0x0000));
            assertEquals(0x22, mapper.charRead(0x1FFF));
        }

        @Test
        void chrRomDoesNot() {
            var mapper = mmc1(2, 8);

            mapper.charWrite(0x0000, 0x11);

            assertEquals(0, mapper.charRead(0x0000), "bank 0's stamp, not the write");
        }
    }

    @Nested
    @DisplayName("mirroring")
    class MirroringModes {
        @ParameterizedTest(name = "control bits ${0} are ${1}")
        @CsvSource({
                "0, ONE_SCREEN_LOW",
                "1, ONE_SCREEN_HIGH",
                "2, VERTICAL",
                "3, HORIZONTAL",
        })
        void theControlRegisterDrivesTheLine(final int bits, final Mirroring expected) {
            var mapper = mmc1(2);
            load(mapper, CONTROL, PRG_MODE_FIXED_LAST | bits);

            assertEquals(expected, mapper.mirroring());
        }

        @Test
        void theHeaderDoesNot() {
            var mapper = new Mapper1(StampedROM.of(2, 0x4000), new byte[0], Mirroring.VERTICAL);

            assertEquals(
                    Mirroring.ONE_SCREEN_LOW, mapper.mirroring(),
                    "the control register is what the PPU is wired to"
            );
        }
    }

    @Nested
    @DisplayName("cartridge RAM")
    class CartridgeRAM {
        @Test
        void isReadableOnPowerUp() {
            var mapper = mmc1(2);

            mapper.prgRAMWrite(0x6000, 0x42);
            mapper.prgRAMWrite(0x7FFF, 0x43);

            assertEquals(0x42, mapper.prgRAMRead(0x6000));
            assertEquals(0x43, mapper.prgRAMRead(0x7FFF), "the whole 8KB window is there");
        }

        @Test
        void theDisableBitHidesItWithoutLosingWhatIsInIt() {
            var mapper = mmc1(2);
            mapper.prgRAMWrite(0x6123, 0x42);

            load(mapper, PRG_BANK, 0x10);

            assertEquals(0, mapper.prgRAMRead(0x6123), "nothing is driving the bus");

            mapper.prgRAMWrite(0x6123, 0x99);
            load(mapper, PRG_BANK, 0x00);

            assertEquals(0x42, mapper.prgRAMRead(0x6123), "and the dropped write left it alone");
        }
    }

    /**
     * Writes one register the way a game has to: five writes of one bit each, least significant
     * bit first, with the fifth landing on the address that names the register.
     */
    private static void load(final Mapper1 mapper, final int address, final int value) {
        for (var i = 0; i < 5; i++) {
            mapper.prgWrite(address, (value >> i) & 1);
        }
    }

    /**
     * An MMC1 with bank-stamped PRG ROM and 8KB of CHR RAM.
     */
    private static Mapper1 mmc1(final int prgBanks) {
        return new Mapper1(StampedROM.of(prgBanks, 0x4000), new byte[0], Mirroring.HORIZONTAL);
    }

    /**
     * An MMC1 with bank-stamped PRG ROM and bank-stamped CHR ROM, in 4KB banks.
     */
    private static Mapper1 mmc1(final int prgBanks, final int chrBanks) {
        return new Mapper1(
                StampedROM.of(prgBanks, 0x4000),
                StampedROM.of(chrBanks, 0x1000),
                Mirroring.HORIZONTAL
        );
    }
}
