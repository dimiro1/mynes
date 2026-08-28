package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.Palettes;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoding the composite signal the chip draws, which is the other answer to the question the
 * palette answers.
 * <p>
 * The interesting assertions here are the ones a palette could not make: that a picture with no
 * colour in it comes out coloured, and that the same picture comes out differently on three
 * successive frames. Everything a palette <em>can</em> do is checked too, against the palette, since
 * agreeing with it on a flat field is what says the signal and the decoder are both right.
 */
class NTSCFilterTests {
    private static final int WIDTH = PPU.SCREEN_WIDTH;
    private static final int HEIGHT = PPU.SCREEN_HEIGHT;

    /**
     * Somewhere in the middle of the picture, well away from either margin.
     */
    private static final int MIDDLE = 120 * WIDTH + 128;

    /**
     * The colours a 2C02 names before the emphasis bits are counted.
     */
    private static final int BASE_COLOURS = 64;

    /**
     * Every entry the chip can name before the emphasis bits are counted.
     */
    private static final int[] ALL_ENTRIES = java.util.stream.IntStream
            .range(0, BASE_COLOURS)
            .toArray();

    /**
     * The entries with no hue in them: column $D, which is the darkest each row goes, the four
     * greys of column $0, and the blacks of columns $E and $F.
     */
    private static final int[] GREYS = {0x00, 0x10, 0x20, 0x30, 0x0D, 0x1D, 0x2D, 0x3D, 0x0F};

    private final NTSCFilter filter = new NTSCFilter();

    /**
     * What the last call to {@link #worstAgainstThePalette} found, for the failure message. A field
     * because a lambda cannot close over a local a loop has been assigning to.
     */
    private String furthest = "";

    private static int[] frameOf(final int entry) {
        var frame = new int[WIDTH * HEIGHT];
        Arrays.fill(frame, entry);

        return frame;
    }

    /**
     * How far apart two packed colours are, as a straight line through RGB. Not a perceptual
     * measure and not meant to be: it is here to say "about the same colour" with a number.
     */
    private static double distance(final int a, final int b) {
        var dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
        var dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
        var db = (a & 0xFF) - (b & 0xFF);

        return Math.sqrt(dr * dr + dg * dg + db * db);
    }

    /**
     * How colourful a packed colour is, as the spread between its channels. Zero is a grey.
     */
    private static int chroma(final int colour) {
        var r = (colour >> 16) & 0xFF;
        var g = (colour >> 8) & 0xFF;
        var b = colour & 0xFF;

        return Math.max(r, Math.max(g, b)) - Math.min(r, Math.min(g, b));
    }

    /**
     * How far the furthest of {@code entries} lands from the palette's idea of it, over all three
     * phases -- since a flat field ought to decode the same at every one of them, and a bug that
     * only showed up at one would be exactly the sort worth catching.
     */
    private double worstAgainstThePalette(final int[] entries) {
        var palette = Palettes.defaultPalette().colours();
        var worst = 0.0;

        for (var phase = 0; phase < PPU.COLOUR_PHASES; phase++) {
            for (var entry : entries) {
                var d = distance(filter.colourise(frameOf(entry), phase)[MIDDLE], palette[entry]);

                if (d > worst) {
                    worst = d;
                    furthest = String.format(
                            "entry $%02X at phase %d is %.1f away from the palette",
                            entry, phase, d);
                }
            }
        }

        return worst;
    }

    @Test
    void aFlatFieldComesOutFlat() {
        var pixels = filter.colourise(frameOf(0x21), 0);
        var colour = pixels[MIDDLE];

        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                assertEquals(colour, pixels[y * WIDTH + x], "at " + x + "," + y);
            }
        }
    }

    /**
     * The signal path checked end to end against something that was measured rather than derived.
     * <p>
     * A field of one colour has no neighbour to bleed into it, so what comes out is that colour and
     * nothing else -- which makes this the one case where the decoder and a palette are answering
     * the same question and can be held against each other.
     * <p>
     * Forty out of a possible 441, and the worst of them is a bright green the two disagree about
     * by a few per cent of saturation. A bound rather than an equality because they are two
     * measurements of the same television and were never going to agree exactly: this is here to
     * catch a hue that has come out backwards or a level table read the wrong way round, which are
     * the ways this breaks. {@link #theGreysLandOnTheMeasuredPaletteAlmostExactly} is the sharp
     * half of the same check.
     */
    @Test
    void everyColourLandsNearTheMeasuredPalette() {
        assertTrue(worstAgainstThePalette(ALL_ENTRIES) < 40, () -> furthest);
    }

    /**
     * The greys, which have no hue for the two to disagree about, and so agree to within a couple
     * of units out of 255. That is the assertion that says the voltage table, the black and white
     * points and the box filter's division are all right -- everything but the carrier.
     */
    @Test
    void theGreysLandOnTheMeasuredPaletteAlmostExactly() {
        assertTrue(worstAgainstThePalette(GREYS) < 8, () -> furthest);
    }

    /**
     * The whole reason this exists.
     * <p>
     * Alternating black and white columns hold no colour at all -- both entries are greys -- but a
     * pixel is eight samples of signal where a colour cycle is twelve, so a pattern that repeats
     * every two pixels repeats every sixteen samples and the receiver has no way to tell it from
     * chroma. A television showed colour there and so does this. A palette cannot: it sees one
     * pixel at a time, and every pixel here is grey.
     */
    @Test
    void narrowStripesOfGreyComeOutColoured() {
        var frame = new int[WIDTH * HEIGHT];

        for (var i = 0; i < frame.length; i++) {
            frame[i] = (i % 2 == 0) ? 0x30 : 0x0F;
        }

        var decoded = filter.colourise(frame, 0)[MIDDLE];

        assertTrue(
                chroma(decoded) > 64,
                () -> "expected an artefact colour, got " + Integer.toHexString(decoded));
    }

    /**
     * Dot crawl, which is the same slip seen down the picture rather than across it.
     */
    @Test
    void anEdgeIsColouredDifferentlyOnEveryThirdScanline() {
        var frame = new int[WIDTH * HEIGHT];

        // A vertical edge, which is where the eight samples of one colour meet the eight of
        // another and neither of them fills a cycle.
        for (var y = 0; y < HEIGHT; y++) {
            for (var x = 0; x < WIDTH; x++) {
                frame[y * WIDTH + x] = x < WIDTH / 2 ? 0x30 : 0x0F;
            }
        }

        var pixels = filter.colourise(frame, 0);
        var first = pixels[100 * WIDTH + WIDTH / 2];

        assertNotEquals(first, pixels[101 * WIDTH + WIDTH / 2], "one line down");
        assertNotEquals(first, pixels[102 * WIDTH + WIDTH / 2], "two lines down");
        assertEquals(first, pixels[103 * WIDTH + WIDTH / 2], "and three brings it back");
    }

    /**
     * The same slip again, this time between one frame and the next, which is what makes a still
     * picture shimmer on a real television.
     */
    @Test
    void theSameFrameIsColouredDifferentlyAtEachOfTheThreePhases() {
        var frame = new int[WIDTH * HEIGHT];

        for (var i = 0; i < frame.length; i++) {
            frame[i] = (i % 2 == 0) ? 0x30 : 0x0F;
        }

        var atZero = filter.colourise(frame, 0).clone();
        var atOne = filter.colourise(frame, 1).clone();
        var atTwo = filter.colourise(frame, 2).clone();

        assertNotEquals(atZero[MIDDLE], atOne[MIDDLE]);
        assertNotEquals(atZero[MIDDLE], atTwo[MIDDLE]);
        assertNotEquals(atOne[MIDDLE], atTwo[MIDDLE]);
    }

    /**
     * The margin doing its job. The window of the first and last pixels reaches six samples outside
     * the picture, and filling that with the edge pixel rather than with a repeated <em>sample</em>
     * is the difference between a clean edge and a column of invented colour down the side.
     */
    @Test
    void theEdgesOfAFlatFieldAreNoDifferentFromTheMiddle() {
        for (var phase = 0; phase < PPU.COLOUR_PHASES; phase++) {
            var pixels = filter.colourise(frameOf(0x21), phase);
            var middle = pixels[MIDDLE];

            assertEquals(middle, pixels[120 * WIDTH], "the leftmost pixel, phase " + phase);
            assertEquals(
                    middle, pixels[120 * WIDTH + WIDTH - 1], "the rightmost pixel, phase " + phase);
        }
    }

    /**
     * Emphasis attenuates the signal for six phases out of twelve, so it reaches the decoder as a
     * change to the waveform rather than as a second table to look in.
     */
    @Test
    void theEmphasisBitsDimTheColour() {
        var plain = filter.colourise(frameOf(0x21), 0)[MIDDLE];
        var emphasised = filter.colourise(frameOf((7 << 6) | 0x21), 0)[MIDDLE];

        assertNotEquals(plain, emphasised);
        assertTrue(
                ((emphasised >> 16) & 0xFF) < ((plain >> 16) & 0xFF)
                        && (emphasised & 0xFF) < (plain & 0xFF),
                "all three bits set dims every channel");
    }

    /**
     * Deterministic, like everything else here: the scratch buffers are reused between calls and
     * carrying something over from the last frame would be the way that broke.
     */
    @Test
    void theSameFrameDecodesToTheSameBytesEveryTime() {
        var frame = new int[WIDTH * HEIGHT];

        for (var i = 0; i < frame.length; i++) {
            frame[i] = i % 64;
        }

        var once = filter.colourise(frame, 1).clone();

        filter.colourise(frameOf(0x0F), 2);

        assertArrayEquals(once, filter.colourise(frame, 1));
    }
}
