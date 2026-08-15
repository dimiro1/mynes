package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Region;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * Which machine to build for a cartridge: whichever one it asks for, or one insisted on.
 * <p>
 * Separate from {@link Region} because {@link #AUTOMATIC} is not a machine. A console is NTSC or it
 * is PAL; this is a preference about how to decide, and it belongs to whoever is sitting in front of
 * the emulator rather than to the console.
 * <p>
 * There has to be a way to insist, because most cartridges do not say. The header field for it was
 * an afterthought in iNES and is left at zero in nearly every dump, so a European game is usually
 * indistinguishable from an American one -- and {@link #AUTOMATIC} will call it NTSC and run it 17%
 * fast. Somebody who knows what they have needs to be able to say so.
 */
public enum RegionSetting {

    /**
     * Whatever the cartridge's header asks for, and NTSC when it says nothing.
     */
    AUTOMATIC("auto", "Automatic", null),

    NTSC("ntsc", "NTSC", Region.NTSC),
    PAL("pal", "PAL", Region.PAL);

    private static final Logger logger = System.getLogger("UI");

    private final String id;
    private final String label;

    /**
     * The machine this setting names, or null for {@link #AUTOMATIC}, which names none until there
     * is a cartridge to ask.
     */
    private final Region region;

    RegionSetting(final String id, final String label, final Region region) {
        this.id = id;
        this.label = label;
        this.region = region;
    }

    /**
     * How this setting is spelled in the config file and on the headless command line.
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
     * The machine to build for this cartridge.
     */
    public Region resolve(final Cart cart) {
        return region == null ? cart.region() : region;
    }

    /**
     * Whether this setting names a machine on its own, without being shown a cartridge. What the
     * menu uses to decide whether the region it is displaying was chosen or inferred.
     */
    @SuppressWarnings("unused")
    public boolean isAutomatic() {
        return region == null;
    }

    /**
     * What the emulator does when nothing has said otherwise: believe the cartridge.
     */
    @SuppressWarnings("SameReturnValue")
    public static RegionSetting defaultSetting() {
        return AUTOMATIC;
    }

    /**
     * The setting {@code id} names, or the default if nothing does.
     * <p>
     * A misspelling costs the setting rather than the startup, like every other entry in the file --
     * and falling back to {@link #AUTOMATIC} is the mildest way to be wrong, since it is the answer
     * somebody who had not thought about it would have got anyway.
     */
    public static RegionSetting byId(final String id) {
        for (var setting : values()) {
            if (setting.id().equals(id)) {
                return setting;
            }
        }

        logger.log(Level.WARNING, id + " is not a region, falling back to " + defaultSetting().id());

        return defaultSetting();
    }
}
