package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;

/**
 * One row of eight pixels at a time, out of the pattern tables and into somebody's raster.
 * <p>
 * Shared by the two windows in this package because they decode the same bytes for different
 * reasons: a nametable draws a background tile with no flipping and an opaque backdrop, a sprite
 * draws with either flip and index 0 left showing whatever was behind it. Everything that differs
 * between those two is an argument here rather than a second copy of the loop.
 * <p>
 * Read through {@link PPU#peekVRAM}, never through the bus. Walking the pattern tables through a
 * real read would tell the mapper what address the PPU is looking at, and MMC3 counts those to
 * decide when to raise its scanline interrupt -- so a debug window would be driving the game's
 * interrupts. It also means the tiles follow whatever bank the game has switched in, which is the
 * only answer that matches the picture.
 */
final class Tiles {
    /**
     * The eight rows of one tile, decoded into a block of a raster.
     *
     * @param pixels  where to draw, as packed ARGB.
     * @param stride  how wide that raster is.
     * @param left    where the tile's left edge goes.
     * @param top     where its top edge goes.
     * @param ppu     the machine, read without side effects.
     * @param address the tile's low bit plane; the high plane is eight bytes further on.
     * @param colours four packed ARGB colours. <b>A zero in slot 0 means transparent</b> -- nothing
     *                is written for those pixels, which is how a sprite lets the background through.
     *                A real colour can never be zero here, since every entry a palette hands out is
     *                fully opaque.
     * @param flipX   whether to draw it back to front.
     * @param flipY   whether to draw it upside down.
     */
    static void draw(
            final int[] pixels,
            final int stride,
            final int left,
            final int top,
            final PPU ppu,
            final int address,
            final int[] colours,
            final boolean flipX,
            final boolean flipY) {

        for (var row = 0; row < 8; row++) {
            var line = flipY ? 7 - row : row;

            // The first plane holds the low bit of each pixel and the one eight bytes later the
            // high bit, which is the order the PPU fetches them in.
            var low = ppu.peekVRAM(address + line);
            var high = ppu.peekVRAM(address + line + 8);
            var into = (top + row) * stride + left;

            for (var column = 0; column < 8; column++) {
                var bit = flipX ? column : 7 - column;
                var entry = (((high >> bit) & 1) << 1) | ((low >> bit) & 1);
                var colour = colours[entry];

                if (colour == 0) {
                    continue;
                }

                pixels[into + column] = colour;
            }
        }
    }

    /**
     * The four colours of one of the eight palettes, as they stand right now.
     * <p>
     * Entry 0 of every palette falls through to the backdrop at $3F00, which is what the PPU itself
     * draws there -- so a background tile's transparent pixels come out the colour the game chose
     * for them rather than black.
     *
     * @param base        $00, $04 ... $1C: where the palette starts in palette RAM.
     * @param transparent whether index 0 should be left showing what is behind it rather than drawn
     *                    as the backdrop, which is the difference between a sprite and a background
     *                    tile.
     */
    static int[] coloursOf(
            final PPU ppu,
            final NESPalette palette,
            final int base,
            final boolean transparent) {

        var colours = new int[4];

        colours[0] = transparent ? 0 : palette.colour(ppu.peekPalette(0));

        for (var i = 1; i < 4; i++) {
            colours[i] = palette.colour(ppu.peekPalette(base + i));
        }

        return colours;
    }

    private Tiles() {
    }
}
