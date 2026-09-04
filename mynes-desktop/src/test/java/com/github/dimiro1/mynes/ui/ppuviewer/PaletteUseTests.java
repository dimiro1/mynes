package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.PPU;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which pixels a palette is drawing, held against a machine set up so the answer is known.
 * <p>
 * No display needed, which is the point of {@link PaletteUse} being where it is: this is the part
 * of the overlay that can be wrong without looking wrong. A map off by one quadrant, or off by the
 * scroll, lights a region next to the one the palette is really drawing -- and somebody would go
 * and look for a bug in the tile it pointed at.
 */
class PaletteUseTests {
    /**
     * One attribute byte covers four tiles by four and holds a palette number for each of its four
     * two-by-two quadrants: bits 1-0 the top left, 3-2 the top right, 5-4 the bottom left and 7-6
     * the bottom right. {@code 0b11_10_01_00} is one of each, in that order.
     */
    private static final int ONE_OF_EACH = 0b11_10_01_00;

    private NES nes;
    private PPU ppu;
    private final boolean[] map = new boolean[PaletteUse.PIXELS];

    @BeforeEach
    void machine() {
        nes = new NES(Cart.load(rom(), "palette-use.nes"));

        // Past the pre-render line, so the PPU stops ignoring writes to $2000, $2005 and $2006.
        for (var i = 0; i < 40_000; i++) {
            nes.tick();
        }

        ppu = nes.getPPU();

        // The first attribute byte of nametable 0, which covers the top left 32 by 32 pixels.
        writeVRAM(0x23C0, ONE_OF_EACH);
        scrollTo(0, 0);
    }

    @Test
    void eachQuadrantOfAnAttributeByteIsItsOwnPalette() {
        PaletteUse.background(map, ppu, 0);
        assertTrue(lit(0, 0), "the top left quadrant");
        assertFalse(lit(16, 0));

        PaletteUse.background(map, ppu, 1);
        assertTrue(lit(16, 0), "the top right quadrant");
        assertFalse(lit(0, 0));

        PaletteUse.background(map, ppu, 2);
        assertTrue(lit(0, 16), "the bottom left quadrant");
        assertFalse(lit(16, 16));

        PaletteUse.background(map, ppu, 3);
        assertTrue(lit(16, 16), "the bottom right quadrant");
        assertFalse(lit(0, 0));
    }

    /**
     * The edges of a quadrant, which is where an off-by-one lives.
     */
    @Test
    void aQuadrantIsSixteenPixelsWide() {
        PaletteUse.background(map, ppu, 1);

        assertFalse(lit(15, 0), "still the left quadrant");
        assertTrue(lit(16, 0));
        assertTrue(lit(31, 0));
        assertFalse(lit(32, 0), "past the attribute byte, which is palette 0 everywhere else");
    }

    /**
     * The whole point of working the answer out from the world position rather than from the
     * screen: a fine scroll puts the quadrant boundaries somewhere other than a multiple of
     * sixteen, and an overlay that assumed otherwise would be wrong for every scrolling game.
     */
    @Test
    void theMapMovesWithTheScroll() {
        scrollTo(5, 0);

        PaletteUse.background(map, ppu, 1);

        assertFalse(lit(10, 0), "world pixel 15, still the left quadrant");
        assertTrue(lit(11, 0), "world pixel 16");
        assertTrue(lit(26, 0), "world pixel 31");
        assertFalse(lit(27, 0), "world pixel 32");
    }

    @Test
    void aVerticalScrollMovesItToo() {
        scrollTo(0, 3);

        PaletteUse.background(map, ppu, 2);

        assertFalse(lit(0, 12), "world line 15");
        assertTrue(lit(0, 13), "world line 16, where the bottom quadrants start");
    }

    /**
     * A sprite's palette is in its own third byte rather than in any attribute table, so this half
     * of the answer has nothing to do with the scroll.
     */
    @Test
    void aSpriteLightsItsOwnEightByEightBox() {
        writeSprite(0, 40, 100, 2);
        writeSprite(1, 40, 200, 1);

        PaletteUse.sprite(map, ppu, 2);

        // The Y byte is one less than the line the sprite lands on.
        assertTrue(lit(100, 41), "its top left corner");
        assertTrue(lit(107, 48), "its bottom right");
        assertFalse(lit(108, 41), "one past its right edge");
        assertFalse(lit(100, 49), "one past its bottom");
        assertFalse(lit(200, 41), "and the sprite using another palette is not in it");
    }

    @Test
    void aSpriteHangingOffTheRightEdgeIsCutOffRatherThanWrapped() {
        writeSprite(0, 40, PPU.SCREEN_WIDTH - 4, 0);

        PaletteUse.sprite(map, ppu, 0);

        assertTrue(lit(PPU.SCREEN_WIDTH - 1, 41), "the part that is on the screen");
        assertFalse(lit(0, 41), "and nothing at the other side of it");
    }

    private boolean lit(final int x, final int y) {
        return map[y * PPU.SCREEN_WIDTH + x];
    }

    /**
     * Puts the scroll where a game would, through $2005 -- so {@code t} and fine X end up holding
     * what the chip would be holding rather than what a test decided they should.
     */
    private void scrollTo(final int x, final int y) {
        ppu.read(2);
        ppu.write(5, x);
        ppu.write(5, y);
        ppu.write(0, 0);
    }

    private void writeVRAM(final int address, final int value) {
        ppu.read(2);
        ppu.write(6, address >> 8);
        ppu.write(6, address & 0xFF);

        // The second $2006 write takes a couple of dots to reach the address counter, and a $2007
        // write before it lands would go wherever the counter still points.
        run(4);

        ppu.write(7, value);
        run(4);

        assertEquals(value, ppu.peekVRAM(address), "the write did not land");
    }

    /**
     * @param sprite  which of the sixty four.
     * @param y       the Y byte, one less than the line it lands on.
     * @param x       the X byte.
     * @param palette 0 to 3, the low two bits of the attribute byte.
     */
    private void writeSprite(final int sprite, final int y, final int x, final int palette) {
        ppu.write(3, sprite * 4);
        ppu.write(4, y);
        ppu.write(4, 0);
        ppu.write(4, palette);
        ppu.write(4, x);
    }

    private void run(final int cycles) {
        for (var i = 0; i < cycles; i++) {
            nes.tick();
        }
    }

    /**
     * A cartridge that does nothing at all. Everything this tests is written into the machine
     * through the registers a game would use, so the program only has to stay out of the way.
     *
     * <pre>
     * 8000  4C 00 80  JMP $8000
     * </pre>
     */
    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        image[16] = 0x4C;
        image[17] = 0x00;
        image[18] = (byte) 0x80;

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
