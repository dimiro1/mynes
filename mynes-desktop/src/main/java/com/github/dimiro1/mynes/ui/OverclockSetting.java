package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * How much extra time a frame to give the game, as a menu offers it.
 * <p>
 * Separate from {@link Overclock} because the two ends of this are counted in different units. A
 * player thinks in time per frame -- "half as long again to get the work done" -- and the chip
 * thinks in scanlines, and which number of scanlines a percentage comes to depends on whether the
 * frame is 262 lines or 312. So the menu remembers a percentage and the region decides the
 * conversion, which also means the same setting survives a region switch and means the same thing
 * on the other side of it.
 * <p>
 * Every one of these adds its lines <em>before</em> the NMI, which is the half that changes nothing
 * a game can observe except that the frame is longer. Lines after the NMI move the picture relative
 * to it and can break a mid-screen split, so they are not something to hand somebody a menu item
 * for; the command line and the REPL can still ask for them.
 *
 * @see Overclock
 */
public enum OverclockSetting {

    /**
     * The hardware, and what a machine nobody has asked runs at.
     */
    OFF("off", "Off", 0),

    PLUS_25("25", "+25%", 25),
    PLUS_50("50", "+50%", 50),
    PLUS_100("100", "+100%", 100),
    PLUS_200("200", "+200%", 200);

    private static final Logger logger = System.getLogger("UI");

    private final String id;
    private final String label;
    private final int percent;

    OverclockSetting(final String id, final String label, final int percent) {
        this.id = id;
        this.label = label;
        this.percent = percent;
    }

    /**
     * How this setting is spelled in the config file. The bare number, since that is what somebody
     * editing the file by hand would write.
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
     * How much longer a frame this asks for, as a percentage of the region's own.
     */
    public int percent() {
        return percent;
    }

    /**
     * What that comes to in scanlines on this machine: 131 for half an NTSC frame again, 156 for
     * half a PAL one.
     */
    public Overclock resolve(final Region region) {
        return percent == 0 ? Overclock.NONE : Overclock.percentOf(region, percent);
    }

    /**
     * What the emulator does when nothing has said otherwise: the hardware's own timing. This is a
     * hack that changes what the game does, so nobody gets it without asking.
     */
    @SuppressWarnings("SameReturnValue")
    public static OverclockSetting defaultSetting() {
        return OFF;
    }

    /**
     * The setting {@code id} names, or the default if nothing does.
     * <p>
     * A misspelling costs the setting rather than the startup, like every other entry in the file --
     * and falling back to {@link #OFF} is the mildest way to be wrong, since it is the machine the
     * cartridge was written for.
     */
    public static OverclockSetting byId(final String id) {
        for (var setting : values()) {
            if (setting.id().equals(id)) {
                return setting;
            }
        }

        logger.log(Level.WARNING,
                id + " is not an overclock, falling back to " + defaultSetting().id());

        return defaultSetting();
    }
}
