package com.github.dimiro1.mynes.ui.palette;

import org.junit.jupiter.api.Test;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for the registry, and for the bundled data behind it.
 * <p>
 * A palette that will not load is skipped rather than thrown, which is the right thing at runtime
 * and useless in a build: this is what turns that silence back into a failure, so a
 * {@code scripts/download-palettes.sh} that ever produces a bad file says so here.
 */
class PalettesTests {
    /**
     * The ten bundled measurements plus the built-in NESdev set.
     */
    private static final int EXPECTED = 11;

    @Test
    void everyPaletteLoads() {
        assertEquals(EXPECTED, Palettes.all().size(), Palettes.all().toString());
    }

    @Test
    void everyPaletteIsAFullTable() {
        for (var palette : Palettes.all()) {
            assertEquals(8 * 64, palette.colours().length, palette.id());
        }
    }

    @Test
    void theDefaultIsNESdevAndComesFirst() {
        assertEquals("nesdev", Palettes.defaultPalette().id());
        assertSame(Palettes.defaultPalette(), Palettes.all().getFirst());
    }

    @Test
    void theRestAreAlphabeticalByShownName() {
        var names = Palettes.all().stream().skip(1).map(NESPalette::name).toList();

        assertEquals(names.stream().sorted().toList(), names);
    }

    @Test
    void idsAreUnique() {
        var ids = Palettes.all().stream().map(NESPalette::id).collect(Collectors.toSet());

        assertEquals(Palettes.all().size(), ids.size(), ids.toString());
    }

    @Test
    void namesAreUnique() {
        var names = Palettes.all().stream().map(NESPalette::name).collect(Collectors.toSet());

        assertEquals(Palettes.all().size(), names.size(), names.toString());
    }

    @Test
    void everyIdRoundTrips() {
        for (var palette : Palettes.all()) {
            assertSame(palette, Palettes.byId(palette.id()), palette.id());
        }
    }

    @Test
    void anUnknownIdFallsBackToTheDefault() {
        // A hand-edited config file, or a palette dropped in a later version.
        assertSame(Palettes.defaultPalette(), Palettes.byId("not-a-palette"));
        assertSame(Palettes.defaultPalette(), Palettes.byId(""));
    }
}
