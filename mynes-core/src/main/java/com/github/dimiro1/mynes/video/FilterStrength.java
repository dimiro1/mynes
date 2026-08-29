package com.github.dimiro1.mynes.video;

import java.util.Arrays;
import java.util.Locale;

/**
 * How hard a filter is applied, for the two filters that can be applied harder.
 * <p>
 * One setting for both, because it is one question -- how much of the television to have -- and
 * because a second enum with the same three ids in it would be three chances for a command line, a
 * config file and a report to disagree about which of them a word meant. What each filter reads out
 * of it is its own business, and neither reading is the other's:
 * <ul>
 *   <li>{@link NTSCFilter} reads {@link #peaking}. Every receiver had to keep the subcarrier out of
 *       luma, and the bluntest way to do it is to average a whole colour cycle -- which throws away
 *       every luma detail finer than that cycle along with the chroma, and is the whole of why a
 *       decoded picture is softer than a palette's. A set with a proper trap notched 3.58MHz and
 *       left the rest of the band alone. This is how much of it to give back.</li>
 *   <li>{@link CRTScreen} reads {@link #depth}: how dark the unlit half of the raster goes. At two
 *       rows to a line, which is the commonest magnification there is, a white picture comes out
 *       with its lit rows at 95% of their light and its dark ones at 75% for {@link #LOW}, 90% and
 *       55% for {@link #MEDIUM}, and 85% and 30% for {@link #STRONG}. Both numbers move with the
 *       magnification, because a row of the picture is a slice of the raster and a wider slice
 *       averages more of what is either side of it.</li>
 * </ul>
 * <p>
 * <strong>{@link #STRONG} is the softest decoded picture</strong>, which reads backwards until you
 * remember what is being named: the strength of the filtering, not of the detail. It is the
 * <em>most</em> visible mask, which reads forwards, and the two are the same rule -- more of what
 * the filter does either way.
 * <p>
 * An enum for the reason {@link VideoFilter} is one: both front ends, the config file and the
 * report all have to spell it the same way.
 */
public enum FilterStrength {
    /**
     * A good set's trap: nearly all of the band back, and a picture about as sharp as the palette's
     * while still bleeding its colours. A mask you can see without being able to point at.
     */
    LOW("low", "Low", 1f, 0.30f),

    /**
     * Half of it, which is where this starts.
     */
    MEDIUM("medium", "Medium", 0.5f, 0.55f),

    /**
     * None of it -- the bare cycle-wide average, and the softest of the three. The mask at its
     * deepest, which is a small monitor's worth rather than a broadcast set's.
     */
    STRONG("strong", "Strong", 0f, 0.85f);

    private final String id;
    private final String label;
    private final float peaking;
    private final float depth;

    FilterStrength(final String id, final String label, final float peaking, final float depth) {
        this.id = id;
        this.label = label;
        this.peaking = peaking;
        this.depth = depth;
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
     * How much of the recovered band {@link NTSCFilter} adds back, as a multiple of it. Package
     * private because it is a number about a filter rather than a fact about the setting, and
     * nothing outside this package has any use for it.
     */
    float peaking() {
        return peaking;
    }

    /**
     * How much of the light {@link CRTScreen} takes out of the raster where the beam never went,
     * as a fraction of it. Package private for the reason {@link #peaking} is.
     */
    float depth() {
        return depth;
    }

    /**
     * What a front end that has not been told otherwise uses.
     * <p>
     * The middle one rather than {@link #STRONG}, even though {@link #STRONG} is the plainer piece
     * of signal processing: the point of switching the decoder on is to see a television's colours,
     * and paying for them with half the luma bandwidth is a worse trade than most people would
     * make. Whoever wants the crude trap can say so. It suits the mask for a duller reason -- a
     * default that is the most anything can do is a default somebody has to turn down.
     */
    public static FilterStrength defaultStrength() {
        return MEDIUM;
    }

    /**
     * @return the strength with this id, or null if nothing has it. Null rather than a default, for
     *         the reason {@link VideoFilter#byId} hands back null.
     */
    public static FilterStrength byId(final String id) {
        var wanted = id.trim().toLowerCase(Locale.ROOT);

        for (var strength : values()) {
            if (strength.id.equals(wanted)) {
                return strength;
            }
        }

        return null;
    }

    /**
     * The ids, comma separated, for an error message that offers them.
     */
    public static String ids() {
        return String.join(", ", Arrays.stream(values()).map(FilterStrength::id).toList());
    }
}
