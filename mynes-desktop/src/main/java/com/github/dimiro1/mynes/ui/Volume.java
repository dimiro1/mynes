package com.github.dimiro1.mynes.ui;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * How loud the sound is played, as a menu offers it.
 * <p>
 * Steps rather than a slider, for the reason every other setting in this program is: it has to
 * survive a restart, which means being written into a properties file as a word somebody can read
 * and type. A slider would put a number nobody chose in there, one of a hundred and one, and the
 * five here are the ones anybody actually wants.
 * <p>
 * There is no zero, and that is deliberate: {@code Machine > Mute} already is one, it is a switch
 * rather than a position, and it remembers the volume to come back to. A volume of nought would be
 * a second, worse mute that lost that.
 *
 * <h2>Why the number is squared</h2>
 *
 * The ear hears ratios. A fader that moved the amplitude in equal steps would do nearly all of its
 * audible work in the top tenth of its travel and the bottom half would be a row of indistinguishable
 * quiets -- so the percentage is squared on its way to the amplitude, which is the taper a volume
 * control has had since it was a carbon track. 50% comes out at a quarter of the amplitude, which
 * is where "half as loud" actually is to within a couple of decibels.
 */
public enum Volume {
    FULL(100),
    THREE_QUARTERS(75),
    HALF(50),
    QUARTER(25),
    TENTH(10);

    private static final Logger logger = System.getLogger("UI");

    private final int percent;
    private final String id;
    private final String label;

    Volume(final int percent) {
        this.percent = percent;
        this.id = Integer.toString(percent);
        this.label = percent + "%";
    }

    /**
     * Where this sits on the control, 10 to 100. Not the amplitude -- see {@link #gain()}.
     */
    public int percent() {
        return percent;
    }

    /**
     * What every sample is multiplied by, 0.01 to 1.
     */
    public double gain() {
        var fraction = percent / 100.0;

        return fraction * fraction;
    }

    /**
     * How this is spelled in the config file: the bare number, since {@code audio.volume=50} is
     * what somebody editing the file by hand would write.
     */
    public String id() {
        return id;
    }

    /**
     * How it is spelled in the menu.
     */
    public String label() {
        return label;
    }

    /**
     * The next step up, or this one if there is none. The constants are in order loudest first, so
     * up the control is back down the list.
     */
    public Volume louder() {
        return ordinal() == 0 ? this : values()[ordinal() - 1];
    }

    /**
     * The next step down, or this one if there is none.
     */
    public Volume quieter() {
        return ordinal() == values().length - 1 ? this : values()[ordinal() + 1];
    }

    /**
     * What a machine nobody has asked plays at. All of it: an emulator that opened quiet would be
     * one somebody spent a minute wondering about.
     */
    @SuppressWarnings("SameReturnValue")
    public static Volume defaultVolume() {
        return FULL;
    }

    /**
     * The volume {@code id} names, or the default if nothing does.
     * <p>
     * A misspelling costs the setting rather than the startup, like every other entry in the file.
     */
    public static Volume byId(final String id) {
        for (var volume : values()) {
            if (volume.id().equals(id)) {
                return volume;
            }
        }

        logger.log(Level.WARNING, id + " is not a volume, falling back to " + defaultVolume().id());

        return defaultVolume();
    }
}
