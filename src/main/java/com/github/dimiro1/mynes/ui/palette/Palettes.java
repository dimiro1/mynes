package com.github.dimiro1.mynes.ui.palette;

import com.github.dimiro1.mynes.Region;

import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;

/**
 * Every palette the emulator can draw with: the built-in NESdev set, then the ten measured ones
 * bundled under {@code /palettes}, alphabetically, and the one for the PAL chip.
 * <p>
 * A bundled palette that will not load is logged and left out of the list rather than being
 * allowed to stop the emulator: no config problem is worth refusing to start over, and the same
 * goes for a missing resource.
 */
public final class Palettes {
    private static final Logger logger = System.getLogger("UI");

    /**
     * The NESdev set, and the one MyNES has always drawn with.
     * <p>
     * Compiled in rather than bundled, so the list is never empty and the default never depends on
     * a resource being present: a build that somehow lost its palette files still runs, and still
     * looks the way it did before there was anything to choose between.
     */
    public static final NESPalette NESDEV = NESPalette.fromARGB("nesdev", "NESdev", new int[]{
            0xFF666666, 0xFF002A88, 0xFF1412A7, 0xFF3B00A4, 0xFF5C007E, 0xFF6E0040, 0xFF6C0600, 0xFF561D00,
            0xFF333500, 0xFF0B4800, 0xFF005200, 0xFF004F08, 0xFF00404D, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFFADADAD, 0xFF155FD9, 0xFF4240FF, 0xFF7527FE, 0xFFA01ACC, 0xFFB71E7B, 0xFFB53120, 0xFF994E00,
            0xFF6B6D00, 0xFF388700, 0xFF0C9300, 0xFF008F32, 0xFF007C8D, 0xFF000000, 0xFF000000, 0xFF000000,
            0xFFFFFEFF, 0xFF64B0FF, 0xFF9290FF, 0xFFC676FF, 0xFFF36AFF, 0xFFFE6ECC, 0xFFFE8170, 0xFFEA9E22,
            0xFFBCBE00, 0xFF88D800, 0xFF5CE430, 0xFF45E082, 0xFF48CDDE, 0xFF4F4F4F, 0xFF000000, 0xFF000000,
            0xFFFFFEFF, 0xFFC0DFFF, 0xFFD3D2FF, 0xFFE8C8FF, 0xFFFBC2FF, 0xFFFEC4EA, 0xFFFECCC5, 0xFFF7D8A5,
            0xFFE4E594, 0xFFCFEF96, 0xFFBDF4AB, 0xFFB3F3CC, 0xFFB5EBF2, 0xFFB8B8B8, 0xFF000000, 0xFF000000,
    });

    /**
     * A palette waiting to be read out of the jar. See {@code scripts/download-palettes.sh} and
     * the PROVENANCE file next to the data for where each one came from.
     */
    private record Bundled(String id, String name, String resource) {
    }

    /**
     * The id of the PAL palette, which is the one thing in {@link #BUNDLED} that is named
     * elsewhere: it is what a PAL machine draws with unless somebody has said otherwise.
     */
    public static final String PAL_ID = "2c07";

    private static final List<Bundled> BUNDLED = List.of(
            new Bundled("composite-direct", "Composite Direct (FBX)", "composite-direct.pal"),
            new Bundled("digital-prime", "Digital Prime (FBX)", "digital-prime.pal"),
            new Bundled("magnum", "Magnum (FBX)", "magnum.pal"),
            new Bundled("nes-classic", "NES Classic (FBX)", "nes-classic.pal"),
            new Bundled(PAL_ID, "PAL (2C07)", "2c07.pal"),
            new Bundled("pc-10", "PC-10", "pc-10.pal"),
            new Bundled("pvm-style-d93", "PVM Style D93 (FBX)", "pvm-style-d93.pal"),
            new Bundled("smooth", "Smooth (FBX)", "smooth.pal"),
            new Bundled("smooth-v2", "Smooth V2 (FBX)", "smooth-v2.pal"),
            new Bundled("sony-cxa", "Sony CXA", "sony-cxa.pal"),
            new Bundled("wavebeam", "Wavebeam", "wavebeam.pal")
    );

    private static final List<NESPalette> ALL = loadAll();

    private Palettes() {
    }

    private static List<NESPalette> loadAll() {
        var palettes = new ArrayList<NESPalette>();
        palettes.add(NESDEV);

        for (var bundled : BUNDLED) {
            var resource = "/palettes/" + bundled.resource();

            try (var in = Palettes.class.getResourceAsStream(resource)) {
                if (in == null) {
                    logger.log(Level.WARNING, resource
                            + " is not on the classpath, leaving " + bundled.id() + " out");
                    continue;
                }

                palettes.add(NESPalette.fromRGB(bundled.id(), bundled.name(), in.readAllBytes()));
            } catch (IOException | IllegalArgumentException e) {
                logger.log(Level.WARNING,
                        "could not read " + resource + ", leaving " + bundled.id() + " out", e);
            }
        }

        return List.copyOf(palettes);
    }

    /**
     * Everything on offer, in the order the dialog lists it: the default first, then the rest by
     * name.
     */
    public static List<NESPalette> all() {
        return ALL;
    }

    /**
     * What the emulator draws with when nothing has said otherwise.
     */
    @SuppressWarnings("SameReturnValue")
    public static NESPalette defaultPalette() {
        return NESDEV;
    }

    /**
     * The same, for a machine of a particular kind.
     * <p>
     * The 2C07 is not a 2C02 with a different clock: its colourburst reference sits fifteen degrees
     * away, so every hue a PAL game asks for comes out somewhere else. A PAL cartridge drawn with
     * an NTSC table is the wrong picture, not a differently measured one, which is why this is a
     * default rather than something to leave to the palette dialog.
     * <p>
     * Falls back to NESdev if the PAL file did not load, since the alternative is a front end with
     * nothing to draw with.
     */
    public static NESPalette defaultPalette(final Region region) {
        if (region != Region.PAL) {
            return NESDEV;
        }

        for (var palette : ALL) {
            if (palette.id().equals(PAL_ID)) {
                return palette;
            }
        }

        return NESDEV;
    }

    /**
     * The palette {@code id} names, or the default if nothing does.
     * <p>
     * An id that no longer names anything -- a hand-edited config file, or a palette dropped in a
     * later version -- costs the choice rather than the startup.
     */
    public static NESPalette byId(final String id) {
        for (var palette : ALL) {
            if (palette.id().equals(id)) {
                return palette;
            }
        }

        logger.log(Level.WARNING, id + " is not a palette, falling back to " + NESDEV.id());

        return NESDEV;
    }
}
