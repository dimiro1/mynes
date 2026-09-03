package com.github.dimiro1.mynes;

import java.util.Arrays;
import java.util.Locale;

/**
 * One of the five voices the APU mixes, as something outside the chip can name it.
 * <p>
 * The chip itself has no use for this: each channel is a field, and {@code mix()} adds the five
 * fields up. What needs a name for one is everything on the outside -- a menu item, a
 * {@code --mute} on a command line, an entry in a report -- and all three have to spell it the same
 * way, which is the same argument {@link com.github.dimiro1.mynes.video.VideoFilter} is an enum
 * for.
 * <p>
 * <strong>Silencing one is not something the console can do.</strong> A game writes $4015 to
 * disable a channel and the chip stops its length counter; this stops the voice reaching the mixer
 * and touches nothing else, so the length counters still count, $4015 still answers the way it
 * would have, and the DMC still steals its cycles from the CPU. It is the sound equivalent of
 * {@code PPU.setBackgroundLayerVisible} -- a way of finding out which voice a noise is coming from,
 * and not a thing the hardware has.
 *
 * @see APU#setChannelMuted(APUChannel, boolean)
 */
public enum APUChannel {
    PULSE_1("pulse1", "Pulse 1"),
    PULSE_2("pulse2", "Pulse 2"),
    TRIANGLE("triangle", "Triangle"),
    NOISE("noise", "Noise"),

    /**
     * The delta modulation channel, which is the one that is nearly always the drums.
     */
    DMC("dmc", "DMC");

    private final String id;
    private final String label;

    APUChannel(final String id, final String label) {
        this.id = id;
        this.label = label;
    }

    /**
     * How this is spelled on a command line and in a report.
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
     * @return the channel with this id, or null if nothing has it. Null rather than a default, for
     *         the reason {@link com.github.dimiro1.mynes.video.VideoFilter#byId} gives: a
     *         misspelling that quietly silenced some other voice would be a run nobody asked for.
     */
    public static APUChannel byId(final String id) {
        var wanted = id.trim().toLowerCase(Locale.ROOT);

        for (var channel : values()) {
            if (channel.id.equals(wanted)) {
                return channel;
            }
        }

        return null;
    }

    /**
     * The ids, comma separated, for an error message that offers them.
     */
    public static String ids() {
        return String.join(", ", Arrays.stream(values()).map(APUChannel::id).toList());
    }
}
