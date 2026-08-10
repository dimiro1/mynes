package com.github.dimiro1.mynes.ui.palette;

/**
 * One measurement of the colours a 2C02 produces, in a shape the front end can draw with.
 * <p>
 * A real PPU has no palette table at all: it generates an NTSC signal straight from the six bit
 * colour index, so every RGB table in every emulator is somebody's reading of that signal off a
 * particular display. They disagree visibly, and which one looks right is a matter of taste and of
 * what television the person grew up with -- which is why the emulator carries more than one.
 * <p>
 * The table is 512 packed ARGB entries indexed {@code emphasis << 6 | entry}, which is exactly
 * what the PPU writes into its framebuffer, so colouring a pixel stays a single array read.
 */
public final class NESPalette {
    /**
     * The colours the chip can name, before $2001 has its say.
     */
    private static final int BASE_COLOURS = 64;

    /**
     * One for each combination of the three emphasis bits, including none of them.
     */
    private static final int EMPHASIS_VARIANTS = 8;

    private static final int ENTRIES = BASE_COLOURS * EMPHASIS_VARIANTS;

    /**
     * A plain palette file: 64 RGB triplets and nothing else. Everything bundled here is one of
     * these.
     */
    private static final int RGB_BYTES = BASE_COLOURS * 3;

    /**
     * A file that spells the emphasis variants out rather than leaving them to be synthesised.
     * Nothing bundled here is one, but generated palettes in the wild are.
     */
    private static final int FULL_BYTES = ENTRIES * 3;

    /**
     * How much a colour channel is dimmed when one of the other two is being emphasised. Measured
     * on real hardware at roughly three quarters.
     */
    private static final double EMPHASIS_ATTENUATION = 0.746;

    private final String id;
    private final String name;

    /**
     * Takes ownership of {@code colours}; every caller builds a fresh array and keeps no
     * reference.
     */
    private final int[] colours;

    private NESPalette(final String id, final String name, final int[] colours) {
        this.id = id;
        this.name = name;
        this.colours = colours;
    }

    /**
     * Reads a palette file.
     * <p>
     * 192 bytes is 64 RGB triplets, and the eight emphasis variants are synthesised from them.
     * 1536 bytes is all 512 entries written out, which is taken at its word: a file that went to
     * the trouble of measuring emphasis knows better than the approximation below.
     *
     * @param id    the name the config file remembers the palette by.
     * @param name  how the palette is spelled in the dialog.
     * @param bytes the contents of the file.
     * @throws IllegalArgumentException if the file is not one of the two lengths.
     */
    public static NESPalette fromRGB(final String id, final String name, final byte[] bytes) {
        return switch (bytes.length) {
            case RGB_BYTES -> new NESPalette(id, name, withEmphasis(readColours(bytes, BASE_COLOURS)));
            case FULL_BYTES -> new NESPalette(id, name, readColours(bytes, ENTRIES));
            default -> throw new IllegalArgumentException(
                    id + " is " + bytes.length + " bytes, expected "
                            + RGB_BYTES + " or " + FULL_BYTES);
        };
    }

    /**
     * The same thing from 64 colours already packed as ARGB, for a palette that is compiled in
     * rather than read from a file.
     */
    static NESPalette fromARGB(final String id, final String name, final int[] base) {
        if (base.length != BASE_COLOURS) {
            throw new IllegalArgumentException(
                    id + " has " + base.length + " colours, expected " + BASE_COLOURS);
        }

        return new NESPalette(id, name, withEmphasis(base));
    }

    private static int[] readColours(final byte[] bytes, final int count) {
        var colours = new int[count];

        for (var i = 0; i < count; i++) {
            colours[i] = 0xFF000000
                    | ((bytes[i * 3] & 0xFF) << 16)
                    | ((bytes[i * 3 + 1] & 0xFF) << 8)
                    | (bytes[i * 3 + 2] & 0xFF);
        }

        return colours;
    }

    /**
     * Repeats {@code base} once for each of the eight combinations of the three emphasis bits.
     * <p>
     * The emphasis bits do not brighten the channel they name; they dim the other two, by pushing
     * the signal outside the range the television expects. So setting all three at once makes the
     * whole picture darker rather than leaving it alone.
     */
    private static int[] withEmphasis(final int[] base) {
        var table = new int[ENTRIES];

        for (var emphasis = 0; emphasis < EMPHASIS_VARIANTS; emphasis++) {
            // Bit 0 emphasises red, bit 1 green, bit 2 blue -- and each channel is dimmed when
            // either of the other two is emphasised.
            var dimRed = (emphasis & 0b110) != 0;
            var dimGreen = (emphasis & 0b101) != 0;
            var dimBlue = (emphasis & 0b011) != 0;

            for (var entry = 0; entry < BASE_COLOURS; entry++) {
                var colour = base[entry];

                table[(emphasis << 6) | entry] = 0xFF000000
                        | (attenuate((colour >> 16) & 0xFF, dimRed) << 16)
                        | (attenuate((colour >> 8) & 0xFF, dimGreen) << 8)
                        | attenuate(colour & 0xFF, dimBlue);
            }
        }

        return table;
    }

    private static int attenuate(final int channel, final boolean dim) {
        return dim ? (int) (channel * EMPHASIS_ATTENUATION) : channel;
    }

    /**
     * The name the config file remembers this palette by.
     */
    public String id() {
        return id;
    }

    /**
     * How the palette is spelled in the dialog.
     */
    public String name() {
        return name;
    }

    /**
     * One of the 64 colours the chip can name, with no emphasis applied.
     *
     * @param entry a palette entry; only the low six bits matter.
     */
    public int colour(final int entry) {
        return colours[entry & 0x3F];
    }

    /**
     * The whole table, indexed {@code emphasis << 6 | entry}, for a caller drawing pixels.
     * <p>
     * A copy, so nothing can edit the palette out from under everyone else holding it. It is meant
     * to be taken once and kept, not called per pixel.
     */
    public int[] colours() {
        return colours.clone();
    }

    /**
     * The shown name, so a {@link javax.swing.JList} of these needs no cell renderer.
     */
    @Override
    public String toString() {
        return name;
    }
}
