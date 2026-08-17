package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * What is on screen, described rather than drawn.
 * <p>
 * This is the picture as it looks to something that cannot see it: a hash to compare one frame with
 * another, and enough of a summary to tell a game from a machine that never started. A screen
 * showing one flat colour is the shape a failure usually takes -- a ROM that would not boot, a
 * mapper that banked in nothing -- and that is a fact about a frame rather than about whoever is
 * asking, which is why it lives here next to the renderer instead of in the caller.
 * <p>
 * Everything here reads the <em>visible</em> picture, the {@link FrameRenderer#VISIBLE_HEIGHT} lines
 * a television would have shown. Deliberately so, and it matters most for the hash: taking it over
 * all 240 lines would make it depend on the overscan the crop exists to hide, and on the pixel or
 * two of the following frame that the emulation loop's three-dots-per-tick granularity lets into
 * scanline 0.
 *
 * @param hash          a hash of the visible picture.
 * @param uniqueColours how many different colour indices are in it.
 * @param blank         whether it is a single flat colour.
 * @param topColours    the commonest colours, most common first.
 */
public record FrameAnalysis(
        long hash,
        int uniqueColours,
        boolean blank,
        List<Colour> topColours) {

    /**
     * A colour index and how much of the picture it covers.
     */
    public record Colour(int entry, long count) {
    }

    /**
     * How many colours {@link #of} keeps. Enough to say what a picture is mostly made of without
     * turning the description into the picture.
     */
    private static final int TOP_COLOURS = 8;

    /**
     * How many colour indices a 2C02 can name, counting the emphasis bits above them.
     */
    private static final int ENTRIES = 512;

    private static final long FNV_OFFSET_BASIS = 0xCBF29CE484222325L;
    private static final long FNV_PRIME = 0x100000001B3L;

    /**
     * Hashes the visible picture, and nothing else.
     * <p>
     * FNV-1a over the colour indices, two bytes an entry. Cheap enough to do on every frame, which
     * is what makes "did anything change?" answerable without keeping the previous frame around.
     *
     * @param frame a frame of colour indices, {@link PPU#getFrameBuffer()}.
     */
    public static long hash(final int[] frame) {
        var hash = FNV_OFFSET_BASIS;

        for (var y = FrameRenderer.OVERSCAN_TOP; y < FrameRenderer.VISIBLE_BOTTOM; y++) {
            for (var x = 0; x < PPU.SCREEN_WIDTH; x++) {
                hash = fold(hash, frame[y * PPU.SCREEN_WIDTH + x]);
            }
        }

        return hash;
    }

    /**
     * Looks at the visible picture properly, hash included.
     * <p>
     * One pass rather than a hash followed by a count, which is the only reason the folding above
     * is a method of its own.
     *
     * @param frame a frame of colour indices, {@link PPU#getFrameBuffer()}.
     */
    public static FrameAnalysis of(final int[] frame) {
        var counts = new long[ENTRIES];
        var hash = FNV_OFFSET_BASIS;

        for (var y = FrameRenderer.OVERSCAN_TOP; y < FrameRenderer.VISIBLE_BOTTOM; y++) {
            for (var x = 0; x < PPU.SCREEN_WIDTH; x++) {
                var entry = frame[y * PPU.SCREEN_WIDTH + x];

                counts[entry]++;
                hash = fold(hash, entry);
            }
        }

        var colours = new ArrayList<Colour>();

        for (var entry = 0; entry < counts.length; entry++) {
            if (counts[entry] > 0) {
                colours.add(new Colour(entry, counts[entry]));
            }
        }

        colours.sort(Comparator.comparingLong(Colour::count).reversed());

        return new FrameAnalysis(
                hash,
                colours.size(),
                colours.size() <= 1,
                List.copyOf(colours.subList(0, Math.min(TOP_COLOURS, colours.size()))));
    }

    /**
     * Folds one colour index into a running hash, low byte first.
     */
    private static long fold(final long hash, final int entry) {
        var folded = (hash ^ (entry & 0xFF)) * FNV_PRIME;

        return (folded ^ ((entry >> 8) & 0xFF)) * FNV_PRIME;
    }

    /**
     * The commonest colour, or 0 for a frame with nothing in it at all.
     */
    public int dominantColour() {
        return topColours.isEmpty() ? 0 : topColours.getFirst().entry();
    }
}
