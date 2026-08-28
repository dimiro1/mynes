package com.github.dimiro1.mynes.video;

import java.util.Arrays;
import java.util.Locale;

/**
 * How a frame of colour indices becomes colours.
 * <p>
 * Two rival answers rather than a setting on one: {@link #NONE} looks each index up in a measured
 * palette, and {@link #NTSC} rebuilds the composite waveform the chip drew and decodes it. The
 * second does not consult a palette at all, which is the one thing worth knowing before switching
 * between them -- see {@link NTSCFilter}.
 * <p>
 * An enum rather than a boolean because both front ends, the config file and the report all have to
 * spell this the same way, and because a second filter would arrive as a third constant rather than
 * as a second boolean.
 */
public enum VideoFilter {
    /**
     * The palette, straight through.
     */
    NONE("none", "None"),

    /**
     * The 2C02's composite signal, decoded. NTSC only.
     */
    NTSC("ntsc", "NTSC");

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
