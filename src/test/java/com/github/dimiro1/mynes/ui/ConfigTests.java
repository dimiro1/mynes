package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.ui.input.KeyBindings;
import com.github.dimiro1.mynes.ui.input.KeyBindings.Button;
import com.github.dimiro1.mynes.ui.palette.NESPalette;
import com.github.dimiro1.mynes.ui.palette.Palettes;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code ~/.mynes/config.properties} and everything kept in it.
 * <p>
 * The file is meant to be edited by hand, which means the interesting cases are all the ways a
 * hand-edited file can be wrong. None of them may stop the emulator from starting.
 * <p>
 * The other half of the job is that saving is a truncate-and-rewrite: one class owns the file, so
 * that changing one setting cannot drop another. {@link Sections} is the guard on that.
 */
class ConfigTests {
    @TempDir
    private Path directory;

    /**
     * Some palette that is not the default, for telling a choice that was carried apart from
     * everything having quietly fallen back to NESdev.
     */
    private static final NESPalette OTHER = Palettes.all().get(1);

    private Path config() {
        return directory.resolve("config.properties");
    }

    private Path write(final String contents) throws IOException {
        return Files.writeString(config(), contents);
    }

    @Nested
    @DisplayName("loading the bindings")
    class LoadingBindings {
        @Test
        void aMissingFileGivesTheDefaults() {
            var bindings = Config.load(directory.resolve("not-there.properties")).keyBindings();

            assertEquals(KeyEvent.VK_X, bindings.keyFor(Button.A));
            assertEquals(KeyEvent.VK_Z, bindings.keyFor(Button.B));
            assertEquals(KeyEvent.VK_SHIFT, bindings.keyFor(Button.SELECT));
            assertEquals(KeyEvent.VK_ENTER, bindings.keyFor(Button.START));
            assertEquals(KeyEvent.VK_UP, bindings.keyFor(Button.UP));
            assertEquals(KeyEvent.VK_DOWN, bindings.keyFor(Button.DOWN));
            assertEquals(KeyEvent.VK_LEFT, bindings.keyFor(Button.LEFT));
            assertEquals(KeyEvent.VK_RIGHT, bindings.keyFor(Button.RIGHT));
        }

        @Test
        void aMissingEntryFallsBackToItsDefault() throws IOException {
            var bindings = Config.load(write("controller1.a=VK_K\n")).keyBindings();

            assertEquals(KeyEvent.VK_K, bindings.keyFor(Button.A), "the entry that is there");
            assertEquals(KeyEvent.VK_Z, bindings.keyFor(Button.B), "and the seven that are not");
        }

        @Test
        void anUnknownKeyNameFallsBackToItsDefault() throws IOException {
            var bindings = Config.load(write("""
                    controller1.a=VK_NOT_A_KEY
                    controller1.b=VK_Q
                    """)).keyBindings();

            assertEquals(KeyEvent.VK_X, bindings.keyFor(Button.A), "the bad entry alone");
            assertEquals(KeyEvent.VK_Q, bindings.keyFor(Button.B), "the good one still counts");
        }

        @Test
        void anEmptyValueLeavesTheButtonUnbound() throws IOException {
            var bindings = Config.load(write("controller1.select=\n")).keyBindings();

            assertEquals(KeyBindings.UNBOUND, bindings.keyFor(Button.SELECT));
            assertNull(bindings.buttonFor(KeyEvent.VK_SHIFT), "the default key is free now");
        }
    }

    @Nested
    @DisplayName("loading the palette")
    class LoadingPalette {
        @Test
        void aMissingEntryGivesTheDefault() throws IOException {
            assertSame(Palettes.defaultPalette(),
                    Config.load(write("controller1.a=VK_K\n")).palette());
        }

        @Test
        void anIdNamesItsPalette() throws IOException {
            assertSame(OTHER, Config.load(write("video.palette=" + OTHER.id() + "\n")).palette());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            // Properties keeps everything after the '=', trailing spaces included.
            assertSame(OTHER, Config.load(write("video.palette= " + OTHER.id() + "  \n")).palette());
        }

        @Test
        void anUnknownIdFallsBackToTheDefault() throws IOException {
            assertSame(Palettes.defaultPalette(),
                    Config.load(write("video.palette=not-a-palette\n")).palette());
        }
    }

    @Nested
    @DisplayName("loading the fast forward speed")
    class LoadingFastForward {
        @Test
        void aMissingEntryGivesTheDefault() throws IOException {
            assertSame(EmulationSpeed.defaultFastForward(),
                    Config.load(write("video.palette=nesdev\n")).fastForwardSpeed());
        }

        @Test
        void anIdNamesItsSpeed() throws IOException {
            assertSame(EmulationSpeed.EIGHT_TIMES,
                    Config.load(write("emulation.fast-forward=8x\n")).fastForwardSpeed());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertSame(EmulationSpeed.UNLIMITED,
                    Config.load(write("emulation.fast-forward= unlimited \n")).fastForwardSpeed());
        }

        @Test
        void anUnknownIdFallsBackToTheDefault() throws IOException {
            assertSame(EmulationSpeed.defaultFastForward(),
                    Config.load(write("emulation.fast-forward=ludicrous\n")).fastForwardSpeed());
        }
    }

    @Nested
    @DisplayName("saving")
    class Saving {
        @Test
        void theBindingsSurviveTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setKeyBindings(KeyBindings.defaults()
                    .with(Button.A, KeyEvent.VK_L)
                    .with(Button.SELECT, KeyBindings.UNBOUND));
            config.save(config());

            var loaded = Config.load(config()).keyBindings();

            for (var button : Button.values()) {
                assertEquals(config.keyBindings().keyFor(button), loaded.keyFor(button),
                        button.label());
            }
        }

        @Test
        void thePaletteSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setPalette(OTHER);
            config.save(config());

            assertSame(OTHER, Config.load(config()).palette());
        }

        @Test
        void theFastForwardSpeedSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setFastForwardSpeed(EmulationSpeed.UNLIMITED);
            config.save(config());

            assertSame(EmulationSpeed.UNLIMITED, Config.load(config()).fastForwardSpeed());
        }

        @Test
        void createsTheDirectory() throws IOException {
            var path = directory.resolve("nested").resolve("config.properties");

            Config.load(path).save(path);

            assertTrue(Files.isRegularFile(path));
        }

        @Test
        void writesKeyNamesRatherThanNumbers() throws IOException {
            Config.load(config()).save(config());

            var text = Files.readString(config());

            // The whole point of the format: the file can be edited without looking up that the
            // left arrow is 37.
            assertTrue(text.contains("controller1.a=VK_X"), text);
            assertTrue(text.contains("controller1.left=VK_LEFT"), text);
        }
    }

    @Nested
    @DisplayName("one file, one owner")
    class Sections {
        @Test
        void aSaveWritesEverySection() throws IOException {
            var config = Config.load(config());
            config.setKeyBindings(KeyBindings.defaults().with(Button.A, KeyEvent.VK_L));
            config.setPalette(OTHER);
            config.save(config());

            var text = Files.readString(config());

            assertTrue(text.contains("video.palette=" + OTHER.id()), text);
            assertTrue(text.contains("controller1.a=VK_L"), text);
        }

        @Test
        void changingOneSettingKeepsTheOther() throws IOException {
            // Rebind a key, the way Settings > Controller... would.
            var first = Config.load(config());
            first.setKeyBindings(first.keyBindings().with(Button.A, KeyEvent.VK_L));
            first.save(config());

            // Then pick a palette in a later run, the way Settings > Palette... would. Saving
            // truncates the file, so a dialog that wrote only its own half would drop the binding
            // above -- which is the bug this class exists to keep from coming back.
            var second = Config.load(config());
            second.setPalette(OTHER);
            second.save(config());

            var reloaded = Config.load(config());

            assertSame(OTHER, reloaded.palette(), "the palette just picked");
            assertEquals(KeyEvent.VK_L, reloaded.keyBindings().keyFor(Button.A),
                    "and the binding from the run before");
        }
    }
}
