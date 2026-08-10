package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.ui.input.KeyBindings.Button;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the config file behind Settings &gt; Controller...
 * <p>
 * The file is meant to be edited by hand, which means the interesting cases are all the ways a
 * hand-edited file can be wrong. None of them may stop the emulator from starting.
 */
class KeyBindingsTests {
    @TempDir
    private Path directory;

    private Path config() {
        return directory.resolve("config.properties");
    }

    private Path write(final String contents) throws IOException {
        return Files.writeString(config(), contents);
    }

    @Nested
    class Loading {
        @Test
        void aMissingFileGivesTheDefaults() {
            var bindings = KeyBindings.load(directory.resolve("not-there.properties"));

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
            var bindings = KeyBindings.load(write("controller1.a=VK_K\n"));

            assertEquals(KeyEvent.VK_K, bindings.keyFor(Button.A), "the entry that is there");
            assertEquals(KeyEvent.VK_Z, bindings.keyFor(Button.B), "and the seven that are not");
        }

        @Test
        void anUnknownKeyNameFallsBackToItsDefault() throws IOException {
            var bindings = KeyBindings.load(write("""
                    controller1.a=VK_NOT_A_KEY
                    controller1.b=VK_Q
                    """));

            assertEquals(KeyEvent.VK_X, bindings.keyFor(Button.A), "the bad entry alone");
            assertEquals(KeyEvent.VK_Q, bindings.keyFor(Button.B), "the good one still counts");
        }

        @Test
        void anEmptyValueLeavesTheButtonUnbound() throws IOException {
            var bindings = KeyBindings.load(write("controller1.select=\n"));

            assertEquals(KeyBindings.UNBOUND, bindings.keyFor(Button.SELECT));
            assertNull(bindings.buttonFor(KeyEvent.VK_SHIFT), "the default key is free now");
        }
    }

    @Nested
    class Saving {
        @Test
        void survivesTheRoundTrip() throws IOException {
            var saved = KeyBindings.defaults()
                    .with(Button.A, KeyEvent.VK_L)
                    .with(Button.SELECT, KeyBindings.UNBOUND);
            saved.save(config());

            var loaded = KeyBindings.load(config());

            for (var button : Button.values()) {
                assertEquals(saved.keyFor(button), loaded.keyFor(button), button.label());
            }
        }

        @Test
        void createsTheDirectory() throws IOException {
            var path = directory.resolve("nested").resolve("config.properties");

            KeyBindings.defaults().save(path);

            assertTrue(Files.isRegularFile(path));
        }

        @Test
        void writesKeyNamesRatherThanNumbers() throws IOException {
            KeyBindings.defaults().save(config());

            var text = Files.readString(config());

            // The whole point of the format: the file can be edited without looking up that the
            // left arrow is 37.
            assertTrue(text.contains("controller1.a=VK_X"), text);
            assertTrue(text.contains("controller1.left=VK_LEFT"), text);
        }
    }

    @Nested
    class Remapping {
        @Test
        void bindingAKeyTakesItOffTheButtonThatHadIt() {
            var bindings = KeyBindings.defaults().with(Button.B, KeyEvent.VK_X);

            assertEquals(KeyEvent.VK_X, bindings.keyFor(Button.B));
            assertEquals(KeyBindings.UNBOUND, bindings.keyFor(Button.A), "A gave up X");
            assertEquals(Button.B, bindings.buttonFor(KeyEvent.VK_X));
        }

        @Test
        void theOriginalIsUntouched() {
            var defaults = KeyBindings.defaults();

            defaults.with(Button.A, KeyEvent.VK_L);

            assertEquals(KeyEvent.VK_X, defaults.keyFor(Button.A));
        }

        @Test
        void anUnboundKeyPressesNothing() {
            assertNull(KeyBindings.defaults().buttonFor(KeyEvent.VK_F1));
            assertNull(KeyBindings.defaults().buttonFor(KeyBindings.UNBOUND),
                    "and neither does the code for no key at all");
        }
    }
}
