package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;

import java.util.Arrays;

/**
 * Which pixels of the screen a palette is drawing, worked out from the machine rather than from the
 * picture.
 * <p>
 * The picture cannot be asked. A frame holds colour indices, so two palettes that happen to name the
 * same colour are the same pixel there -- and "which of my eight palettes coloured that" is exactly
 * the question a wrong colour raises. So the answer is rebuilt the way the chip built it: from the
 * attribute bytes for a background palette, and from the third byte of each sprite for a sprite one.
 * <p>
 * <b>The background answer is as good as the scroll it is asked at.</b> It is taken from {@code t},
 * which is what the game last wrote, so for a game that splits the screen mid-frame it describes
 * whichever half of the split has been written by the time the timer looks -- the same caveat the
 * nametable viewer's scroll rectangle carries, and for the same reason.
 * <p>
 * Free of Swing so it can be tested where there is no display, like {@link PaletteCells}, and free
 * of any window's geometry: the maps are in screen pixels, and whoever asked can magnify them.
 */
final class PaletteUse {
    /**
     * The four nametables laid out two by two, which is the space a scroll moves through.
     */
    private static final int WORLD_WIDTH = PPU.SCREEN_WIDTH * 2;
    private static final int WORLD_HEIGHT = PPU.SCREEN_HEIGHT * 2;

    /**
     * How wide a share of one attribute byte is. A byte covers four tiles by four and holds four
     * palette numbers, one for each two-by-two quadrant of it -- so the palette can only change
     * every sixteen pixels, which is what lets a row be filled in spans rather than pixel by pixel.
     */
    private static final int QUADRANT = 16;

    private static final int TILE = 8;
    private static final int ATTRIBUTES = 0x3C0;

    static final int PIXELS = PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT;

    static final int SPRITES = 64;

    /**
     * Where one of the four background palettes is drawing.
     *
     * @param into    {@link #PIXELS} flags, overwritten in full.
     * @param palette 0 to 3.
     */
    static void background(final boolean[] into, final PPU ppu, final int palette) {
        var scrollX = scrollX(ppu);
        var scrollY = scrollY(ppu);

        for (var y = 0; y < PPU.SCREEN_HEIGHT; y++) {
            var worldY = (scrollY + y) % WORLD_HEIGHT;
            var row = y * PPU.SCREEN_WIDTH;
            var x = 0;

            while (x < PPU.SCREEN_WIDTH) {
                var worldX = (scrollX + x) % WORLD_WIDTH;
                var used = paletteAt(ppu, worldX, worldY) == palette;

                // As far as the next quadrant boundary in the nametables, which is where the
                // answer can next change. A fine scroll puts that boundary somewhere other than a
                // multiple of sixteen on the screen, which is the whole reason it is worked out
                // from the world position rather than from x.
                var end = Math.min(PPU.SCREEN_WIDTH, x + QUADRANT - worldX % QUADRANT);

                Arrays.fill(into, row + x, row + end, used);

                x = end;
            }
        }
    }

    /**
     * Where the sprites using one of the four sprite palettes are.
     * <p>
     * Every sprite, whatever the hardware would have done with it: one dropped for being the ninth
     * on its line is still using the palette, and a run of sprites that vanish is a question this
     * window gets asked. What is not included is the shape of the sprite -- the flags cover the
     * whole eight by eight or eight by sixteen box, transparent pixels included, because a box is
     * what a person is looking for on a screen and the tile decoding would only make it harder to
     * see.
     *
     * @param into    {@link #PIXELS} flags, overwritten in full.
     * @param palette 0 to 3.
     */
    static void sprite(final boolean[] into, final PPU ppu, final int palette) {
        Arrays.fill(into, false);

        var height = ppu.getSpriteHeight();

        for (var sprite = 0; sprite < SPRITES; sprite++) {
            if ((ppu.peekOAM(sprite * 4 + 2) & 3) != palette) {
                continue;
            }

            // The Y byte is one less than the line the sprite lands on: the hardware compares it
            // against the line it is evaluating and draws on the next one.
            var top = ppu.peekOAM(sprite * 4) + 1;
            var left = ppu.peekOAM(sprite * 4 + 3);

            for (var y = top; y < top + height && y < PPU.SCREEN_HEIGHT; y++) {
                if (y < 0) {
                    continue;
                }

                for (var x = left; x < left + TILE && x < PPU.SCREEN_WIDTH; x++) {
                    into[y * PPU.SCREEN_WIDTH + x] = true;
                }
            }
        }
    }

    /**
     * Which of the four background palettes a point in the four nametables is drawn with. The same
     * arithmetic {@code NametablePanel} does per tile, asked per point instead.
     */
    private static int paletteAt(final PPU ppu, final int worldX, final int worldY) {
        var base = 0x2000
                + ((worldY / PPU.SCREEN_HEIGHT) * 2 + worldX / PPU.SCREEN_WIDTH) * 0x400;
        var column = worldX % PPU.SCREEN_WIDTH / TILE;
        var row = worldY % PPU.SCREEN_HEIGHT / TILE;
        var attribute = ppu.peekVRAM(base + ATTRIBUTES + (row / 4) * 8 + column / 4);

        return (attribute >> (((row & 2) << 1) | (column & 2))) & 3;
    }

    /**
     * Where the top left corner of the screen is in the four nametables. {@code t} is laid out
     * {@code yyy NN YYYYY XXXXX}, and fine X is the one part of the scroll that never goes near it.
     */
    private static int scrollX(final PPU ppu) {
        var t = ppu.getT();

        return ((t >> 10) & 1) * PPU.SCREEN_WIDTH + (t & 0x1F) * TILE + ppu.getFineX();
    }

    private static int scrollY(final PPU ppu) {
        var t = ppu.getT();

        // Coarse Y counts to 31 while a nametable is only 30 tiles tall, and a game that leaves it
        // there is reading attribute bytes as tiles. The wrap keeps that inside the world rather
        // than off the end of it; the picture it describes is nonsense either way.
        return (((t >> 11) & 1) * PPU.SCREEN_HEIGHT
                + ((t >> 5) & 0x1F) * TILE
                + ((t >> 12) & 7)) % WORLD_HEIGHT;
    }

    private PaletteUse() {
    }
}
