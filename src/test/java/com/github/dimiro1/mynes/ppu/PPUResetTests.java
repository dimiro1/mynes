package com.github.dimiro1.mynes.ppu;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The console's reset button, which clears the PPU's control side and nothing with memory in it.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_power_up_state">NESdev: PPU power up state</a>
 */
class PPUResetTests extends PPUFixture {
    @BeforeEach
    void setUp() {
        createPPU();
    }

    @Test
    void clearsTheWriteLatch() {
        ppu.write(PPUSCROLL, 0x12);
        assertTrue(ppu.isWriteLatchSet(), "the first write of the pair set the latch");

        ppu.reset();

        assertFalse(ppu.isWriteLatchSet());
    }

    @Test
    void turnsRenderingOff() {
        ppu.write(PPUMASK, 0x18);
        run(2); // the write takes two dots to reach the rendering hardware
        assertTrue(ppu.isRenderingEnabled());

        ppu.reset();

        assertFalse(ppu.isRenderingEnabled());
    }

    @Test
    void abandonsAMaskWriteStillInFlight() {
        ppu.write(PPUMASK, 0x18);
        ppu.reset();
        run(2);

        assertFalse(ppu.isRenderingEnabled(), "the two dot pipeline was emptied by the reset");
    }

    @Test
    void releasesTheNMILine() {
        ppu.write(PPUCTRL, 0x80);
        runTo(241, 2); // the VBlank flag went up at dot 1, taking the line with it
        assertTrue(bus.level());

        ppu.reset();

        assertFalse(bus.level(), "clearing PPUCTRL lets go of /NMI");
    }

    @Test
    void doesNotRaiseNMIAtTheNextVBlank() {
        ppu.write(PPUCTRL, 0x80);
        ppu.reset();

        bus.reset();
        advanceFrames(2);

        assertEquals(0, bus.assertions(), "the NMI enable bit was cleared with the rest of PPUCTRL");
    }

    @Test
    void clearsTheScrollPosition() {
        ppu.write(PPUSCROLL, 0xFF);
        ppu.write(PPUSCROLL, 0xFF);

        ppu.reset();

        assertEquals(0, ppu.getT(), "the staging register is cleared");
        assertEquals(0, ppu.getFineX());
    }

    @Test
    void keepsTheVRAMAddress() {
        setVRAMAddress(0x2A55);
        assertEquals(0x2A55, ppu.getV());

        ppu.reset();

        assertEquals(0x2A55, ppu.getV(), "PPUADDR survives reset; only the scroll side is cleared");
    }

    @Test
    void keepsEveryKindOfMemory() {
        ppu.write(OAMADDR, 0x00);
        ppu.write(OAMDATA, 0x5A);
        writeVRAM(0x3F01, 0x21);
        writeVRAM(0x2005, 0x42);

        ppu.reset();

        ppu.write(OAMADDR, 0x00);
        assertEquals(0x5A, ppu.read(OAMDATA), "OAM is memory, and memory survives");
        assertEquals(0x21, ppu.peekPalette(0x3F01), "so is palette RAM");
        assertEquals(0x42, readVRAM(0x2005), "and the nametables");
    }

    @Test
    void leavesTheBeamWhereItWas() {
        runTo(100, 7);

        ppu.reset();

        assertEquals(100, ppu.getScanline(), "the clock is not the reset line's to stop");
        assertEquals(7, ppu.getDot());
    }
}
