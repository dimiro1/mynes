package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.Cart;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which bank each board says is in each window, against what a read from that window actually
 * fetches.
 * <p>
 * That second half is the point. {@code banks()} is worked out from {@link Mapper#prgOffset} and
 * {@link Mapper#charOffset}, which are the same arithmetic {@code prgRead} and {@code charRead} do
 * -- so the way for it to be wrong is for those two to have come apart, and the way to catch that
 * is to fill every bank with its own number and read one back.
 */
class MapperBanksTests {
    private static final int PRG_WINDOW = Mapper.Banks.PRG_WINDOW;
    private static final int CHR_WINDOW = Mapper.Banks.CHR_WINDOW;

    /**
     * An unbanked board is four consecutive program windows and eight consecutive character ones,
     * forever. Said out loud because it is the baseline every other answer here is read against.
     */
    @Test
    void anUnbankedBoardIsTheWholeChipInOrder() {
        var banks = load(0, 32, 8).banks();

        assertArrayEquals(new int[]{0, 1, 2, 3}, banks.prg());
        assertArrayEquals(new int[]{0, 1, 2, 3, 4, 5, 6, 7}, banks.chr());
    }

    /**
     * A 16KB NROM is mirrored into both halves of the window, which the numbers say plainly.
     */
    @Test
    void aHalfSizedNROMShowsTheSameTwoBanksTwice() {
        assertArrayEquals(new int[]{0, 1, 0, 1}, load(0, 16, 8).banks().prg());
    }

    /**
     * UxROM switches 16KB at $8000 and pins the last 16KB above it, so the first pair of windows
     * moves together and the second pair never moves.
     */
    @Test
    void uxROMMovesTheFirstPairAndPinsTheLast() {
        // With character ROM rather than the RAM a real UxROM has, so that the read-back below has
        // something written in every window to read.
        var mapper = load(2, 128, 8);

        assertArrayEquals(new int[]{0, 1, 14, 15}, mapper.banks().prg());

        mapper.prgWrite(0x8000, 3);

        assertArrayEquals(new int[]{6, 7, 14, 15}, mapper.banks().prg());
        assertReadsAgree(mapper);
    }

    /**
     * CNROM switches the whole of character memory at once, so all eight windows move together.
     */
    @Test
    void cnROMMovesEveryCharacterWindowTogether() {
        var mapper = load(3, 32, 32);

        mapper.prgWrite(0x8000, 2);

        assertArrayEquals(new int[]{16, 17, 18, 19, 20, 21, 22, 23}, mapper.banks().chr());
        assertReadsAgree(mapper);
    }

    /**
     * MMC1 in its power-on mode: the last 16KB pinned at $C000 and the switchable one below it.
     */
    @Test
    void mmc1PinsTheLastBankUntilItIsToldOtherwise() {
        var mapper = load(1, 128, 32);

        assertArrayEquals(new int[]{0, 1, 14, 15}, mapper.banks().prg());

        write(mapper, 0xE000, 3);

        assertArrayEquals(new int[]{6, 7, 14, 15}, mapper.banks().prg());
        assertReadsAgree(mapper);
    }

    /**
     * MMC3 is the one board here that moves everything: eight 1KB character windows in two sizes,
     * two switchable 8KB program windows, and a scanline counter.
     */
    @Test
    void mmc3MovesSixPiecesOfCharacterMemorySeparately() {
        var mapper = load(4, 128, 128);

        // R2 to R5 are the four 1KB windows of the second pattern table.
        for (var register = 2; register < 6; register++) {
            mapper.prgWrite(0x8000, register);
            mapper.prgWrite(0x8001, 0x20 + register);
        }

        var chr = mapper.banks().chr();

        for (var window = 4; window < 8; window++) {
            assertEquals(0x20 + window - 2, chr[window], "window " + window);
        }

        assertReadsAgree(mapper);
    }

    @Test
    void mmc3SwapsWhichEndOfTheProgramWindowIsFixed() {
        var mapper = load(4, 128, 128);

        mapper.prgWrite(0x8000, 6);
        mapper.prgWrite(0x8001, 5);

        assertEquals(5, mapper.banks().prg()[0], "the switchable page is at $8000");
        assertEquals(14, mapper.banks().prg()[2], "and the second to last is pinned above it");

        mapper.prgWrite(0x8000, 0x46);

        assertEquals(14, mapper.banks().prg()[0], "bit 6 swaps the two");
        assertEquals(5, mapper.banks().prg()[2]);
        assertEquals(15, mapper.banks().prg()[3], "the vectors never move");

        assertReadsAgree(mapper);
    }

    /**
     * The two boards that can switch their RAM off, and the one that can protect it without.
     */
    @Test
    void theRamFlagsSayWhyAHexViewIsFullOfZeroes() {
        assertTrue(load(0, 32, 8).prgRAMEnabled(), "a board with nothing to switch says yes");

        var mmc3 = load(4, 128, 128);

        assertTrue(mmc3.prgRAMEnabled());
        assertTrue(mmc3.prgRAMWritable());

        mmc3.prgWrite(0xA001, 0xC0);
        assertTrue(mmc3.prgRAMEnabled(), "still answering...");
        assertFalse(mmc3.prgRAMWritable(), "...but a write no longer lands");

        mmc3.prgWrite(0xA001, 0x00);
        assertFalse(mmc3.prgRAMEnabled(), "and now not even that");
    }

    @Test
    void onlyMMC3CountsScanlines() {
        assertNull(load(0, 32, 8).irq());
        assertNull(load(1, 128, 32).irq());

        var mmc3 = load(4, 128, 128);

        mmc3.prgWrite(0xC000, 40);
        mmc3.prgWrite(0xE001, 0);

        var counter = mmc3.irq();

        assertNotNull(counter);
        assertEquals(40, counter.latch());
        assertTrue(counter.enabled());
    }

    /**
     * Every window says the same thing a read from it does, which is the whole claim: the numbers
     * come from the same arithmetic the reads do, and this is what catches the two coming apart.
     * <p>
     * Every bank is filled with its own number, so one byte out of a window names the bank it came
     * from.
     */
    private static void assertReadsAgree(final Mapper mapper) {
        var banks = mapper.banks();

        for (var window = 0; window < banks.prg().length; window++) {
            assertEquals(
                    banks.prg()[window] & 0xFF,
                    mapper.prgRead(0x8000 + window * PRG_WINDOW),
                    "program window " + window);
        }

        for (var window = 0; window < banks.chr().length; window++) {
            assertEquals(
                    banks.chr()[window] & 0xFF,
                    mapper.charRead(window * CHR_WINDOW),
                    "character window " + window);
        }
    }

    /**
     * A cartridge whose every 8KB of program ROM and every 1KB of character ROM begins with its own
     * bank number, so that a byte read out of a window names the bank it came from.
     */
    private static Mapper load(final int mapper, final int prgK, final int chrK) {
        var prg = prgK * 1024;
        var chr = chrK * 1024;
        var image = new byte[16 + prg + chr];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = (byte) (prg / 0x4000);
        image[5] = (byte) (chr / 0x2000);
        image[6] = (byte) ((mapper & 0x0F) << 4);
        image[7] = (byte) (mapper & 0xF0);

        for (var bank = 0; bank * PRG_WINDOW < prg; bank++) {
            image[16 + bank * PRG_WINDOW] = (byte) bank;
        }

        for (var bank = 0; bank * CHR_WINDOW < chr; bank++) {
            image[16 + prg + bank * CHR_WINDOW] = (byte) bank;
        }

        return Cart.load(image, "banks.nes").mapper();
    }

    /**
     * MMC1 takes its registers five bits at a time, lowest first, through the same address.
     */
    private static void write(final Mapper mapper, final int address, final int value) {
        for (var bit = 0; bit < 5; bit++) {
            mapper.prgWrite(address, (value >> bit) & 1);
        }
    }
}
