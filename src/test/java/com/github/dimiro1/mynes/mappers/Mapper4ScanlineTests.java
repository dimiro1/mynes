package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.PPU;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The MMC3 scanline counter clocked by a real PPU rather than by a test poking the address bus.
 * <p>
 * This is what the A12 filter exists for, and the only thing that proves it works: the fetch
 * pipeline flips A12 eight or more times a scanline, and the counter has to see one of them.
 */
class Mapper4ScanlineTests {
    private static final int PPUCTRL = 0;
    private static final int PPUMASK = 1;

    /**
     * 240 visible lines and the pre-render line, which fetches just like they do.
     */
    private static final int RENDERING_LINES = 241;

    @Test
    void clocksOnceALineWithTheBackgroundLowAndTheSpritesHigh() {
        // Super Mario Bros. 3's arrangement, and the one the chip was designed around.
        assertEquals(RENDERING_LINES, clocksInAFrame(0x08));
    }

    @Test
    void clocksOnceALineWithTheTablesTheOtherWayRound() {
        // Here it is the first background fetch after the sprite phase that gets through, around
        // dot 325 instead of dot 260. The extra clock is the pre-render line's first fetch, which
        // follows the whole of VBlank with A12 low and so counts as well.
        assertEquals(RENDERING_LINES + 1, clocksInAFrame(0x10));
    }

    @Test
    void neverClocksWhenBothTablesAreTheSame() {
        // A12 never rises, so a game laid out like this gets no scanline interrupts at all --
        // which is why blargg's test ROMs clock the counter by hand through $2006.
        assertEquals(0, clocksInAFrame(0x00));
    }

    @Test
    void clocksOnceAFrameWhenBothTablesAreTheHighOne() {
        // A12 spends the picture high and only dips for the nametable fetches, which are far too
        // short to count. The one clock is the first fetch after VBlank.
        assertEquals(1, clocksInAFrame(0x18));
    }

    /**
     * Renders a whole frame and counts what the counter saw.
     *
     * @param ctrl what to put in $2000, which is where the two pattern tables are chosen.
     * @return how many times the scanline counter was clocked over that frame.
     */
    private static int clocksInAFrame(final int ctrl) {
        var mapper = new Mapper4(
                StampedROM.of(8, 0x2000), StampedROM.of(64, 0x400), Mirroring.VERTICAL
        );

        var clocks = new int[1];
        mapper.setIRQHandler(asserted -> {
            if (asserted) {
                clocks[0]++;
            }
        });

        // A latch of zero fires on every clock, so the handler ends up counting them.
        mapper.prgWrite(0xC000, 0);
        mapper.prgWrite(0xC001, 0);
        mapper.prgWrite(0xE001, 0);

        var ppu = new PPU(level -> { }, mapper);
        ppu.write(PPUCTRL, ctrl);
        ppu.write(PPUMASK, 0x18);  // show the background and the sprites

        // Start counting at the top of a frame, with rendering long since switched on.
        do {
            ppu.tick();
        } while (ppu.getScanline() != 0 || ppu.getDot() != 0);

        var frame = ppu.getFrame();
        clocks[0] = 0;

        while (ppu.getFrame() == frame) {
            ppu.tick();
        }

        return clocks[0];
    }
}
