package com.github.dimiro1.mynes.video;

import java.util.Arrays;
import java.util.Locale;

/**
 * How much of the picture's fine detail the composite decoder gives back.
 * <p>
 * Every receiver had to keep the subcarrier out of luma, and the bluntest way to do it is to
 * average a whole colour cycle -- which is what {@link NTSCFilter} does, and which throws away
 * every luma detail finer than that cycle along with the chroma. That is the whole of why a
 * decoded picture is softer than a palette's. A television with a proper trap notched 3.58MHz and
 * left the rest of the band alone, and the ones with a sharpness control on the front let somebody
 * decide how much of it to keep. This is that control.
 * <p>
 * <strong>{@link #STRONG} is the crude trap and the softest picture</strong>, which reads backwards
 * until you remember what is being named: the strength of the filtering, not of the detail. Turn it
 * down to see more.
 * <p>
 * An enum for the reason {@link VideoFilter} is one: both front ends, the config file and the
 * report all have to spell it the same way.
 */
public enum FilterStrength {
    /**
     * A good set's trap: nearly all of the band back, and a picture about as sharp as the palette's
     * while still bleeding its colours.
     */
    LOW("low", "Low", 1f),

    /**
     * Half of it, which is where this starts.
     */
    MEDIUM("medium", "Medium", 0.5f),

    /**
     * None of it -- the bare cycle-wide average, and the softest of the three.
     */
    STRONG("strong", "Strong", 0f);

    private final String id;
    private final String label;
    private final float peaking;

    FilterStrength(final String id, final String label, final float peaking) {
        this.id = id;
        this.label = label;
        this.peaking = peaking;
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
     * What a front end that has not been told otherwise uses.
     * <p>
     * The middle one rather than {@link #STRONG}, even though {@link #STRONG} is the plainer piece
     * of signal processing: the point of switching the decoder on is to see a television's colours,
     * and paying for them with half the luma bandwidth is a worse trade than most people would
     * make. Whoever wants the crude trap can say so.
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
