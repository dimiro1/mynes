package com.github.dimiro1.mynes.video;

import java.util.Arrays;
import java.util.Locale;

/**
 * How a frame of colour indices becomes a picture.
 * <p>
 * Three rival answers rather than settings on one, and each is a whole answer: {@link #NONE} looks
 * each index up in a measured palette, {@link #NTSC} rebuilds the composite waveform the chip drew
 * and decodes it, and {@link #CRT} looks the index up the way {@link #NONE} does and then lays it
 * down the way a tube laid it down. Only the middle one declines to consult a palette, which is
 * the one thing worth knowing before switching between them -- see {@link NTSCFilter}.
 * <p>
 * <strong>One at a time, which is the shape of the question rather than a shortage of room.</strong>
 * {@link #NTSC} and {@link #CRT} model different halves of the same television and would compose
 * perfectly well; what stops them is that a fourth constant is a worse name for "both" than a pair
 * of settings would be, and that the pair is the design this enum exists to refuse. If a decoded
 * picture behind scanlines is ever wanted, it arrives by splitting this in two -- not by growing a
 * {@code NTSC_CRT}.
 * <p>
 * An enum rather than a boolean because both front ends, the config file and the report all have to
 * spell this the same way, and because a second filter arrives as a third constant rather than as a
 * second boolean. It has now done so.
 */
public enum VideoFilter {
    /**
     * The palette, straight through.
     */
    NONE("none", "None"),

    /**
     * The 2C02's composite signal, decoded. NTSC only.
     */
    NTSC("ntsc", "NTSC"),

    /**
     * The palette, put on screen the way a picture tube put it there: 240 lines of beam with the
     * unlit half of the raster between them, and the curve of the glass if it is asked for. Either
     * console -- see {@link CRTScreen}.
     */
    CRT("crt", "CRT");

    private final String id;
    private final String label;

    VideoFilter(final String id, final String label) {
        this.id = id;
        this.label = label;
    }

    /**
     * How this is spelled on a command line, in the config file and in a report.
     */
    public String id() {
        return id;
    }

    /**
     * How it is spelled in a menu.
     */
    public String label() {
        return label;
    }

    /**
     * @return the filter with this id, or null if nothing has it. Null rather than a default,
     *         because a misspelling that quietly drew the picture some other way would be a run
     *         nobody asked for; each caller says what it does about that.
     */
    public static VideoFilter byId(final String id) {
        var wanted = id.trim().toLowerCase(Locale.ROOT);

        for (var filter : values()) {
            if (filter.id.equals(wanted)) {
                return filter;
            }
        }

        return null;
    }

    /**
     * The ids, comma separated, for an error message that offers them.
     */
    public static String ids() {
        return String.join(", ", Arrays.stream(values()).map(VideoFilter::id).toList());
    }
}
