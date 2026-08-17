package com.github.dimiro1.mynes.ppu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The PPU's open bus, which is a row of eight tiny capacitors on the data pins rather than a
 * register.
 * <p>
 * Reading a write-only register does not put anything on those pins, so what comes back is
 * whatever charge is left from the last thing that did -- and after a fraction of a second that
 * charge has leaked away to zero. Reads that only drive some of the pins leave the rest alone,
 * which is why $2002 comes back with five bits of stale data underneath its three flags.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_registers">NESdev: PPU registers</a>
 */
class PPUOpenBusTests extends PPUFixture {
    @BeforeEach
    void setUp() {
        createWarmPPU();
    }

    @ParameterizedTest(name = "register {0} reads back the latch")
    @ValueSource(ints = {PPUCTRL, PPUMASK, OAMADDR, PPUSCROLL, PPUADDR})
    void writeOnlyRegistersReadBackWhateverWasLastOnTheBus(final int register) {
        ppu.write(PPUADDR, 0x5A);

        assertEquals(0x5A, ppu.read(register));
    }

    @Test
    void anyWriteChargesAllEightBits() {
        ppu.write(PPUADDR, 0xA5);

        assertEquals(0xA5, ppu.read(PPUCTRL));
    }

    @Test
    void readingStatusDrivesOnlyTheTopThreeBits() {
        ppu.write(PPUADDR, 0x1F);  // charge the low five, and only those
        runTo(245, 0);

        assertEquals(0x9F, ppu.read(PPUSTATUS), "VBlank on top of the five stale bits");
        assertEquals(
                0x9F, ppu.read(PPUCTRL),
                "the low five were never touched, and the top three kept the flags they returned"
        );
    }

    @Test
    void readingOamDataChargesAllEightBits() {
        ppu.write(OAMADDR, 0x00);
        ppu.write(OAMDATA, 0x3C);
        ppu.write(OAMADDR, 0x00);  // leaves $00 on the bus

        assertEquals(0x3C, ppu.read(OAMDATA));
        assertEquals(0x3C, ppu.read(PPUCTRL), "the OAM byte was driven onto every pin");
    }

    @Test
    void readingPaletteOnlyChargesTheSixBitsItDrives() {
        writeVRAM(0x3F00, 0x15);
        setVRAMAddress(0x3F00);

        // Charge the top two bits without disturbing the address that was just set.
        ppu.write(PPUCTRL, 0xC0);

        assertEquals(0xD5, ppu.read(PPUDATA), "six bits of palette under two stale ones");
    }

    @Test
    void aBitDecaysToZeroAfterAboutHalfASecond() {
        ppu.write(PPUADDR, 0xFF);

        advanceFrames(30);
        assertEquals(0xFF, ppu.read(PPUCTRL), "30 frames is still inside the decay time");

        advanceFrames(10);
        assertEquals(0x00, ppu.read(PPUCTRL), "40 frames is not");
    }

    @Test
    void eachBitDecaysOnItsOwnSchedule() {
        ppu.write(PPUADDR, 0xFF);
        advanceFrames(30);

        // Reading $2002 during VBlank recharges bits 7-5 and leaves 4-0 to carry on decaying.
        runTo(245, 0);
        ppu.read(PPUSTATUS);

        advanceFrames(10);

        assertEquals(
                0x80, ppu.read(PPUCTRL),
                "only the bit the status read put a one back into is still charged"
        );
    }
}
