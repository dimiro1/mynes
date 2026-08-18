package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for where File &gt; Screenshot puts a picture.
 * <p>
 * Nobody is asked where it should go, so the name has to be right without a dialog to correct it:
 * beside the ROM, saying which game it is, and never on top of the picture taken a moment ago.
 */
class ScreenshotsTests {
    @TempDir
    private Path directory;

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 8, 15, 14, 22, 33);

    private Path rom(final String name) {
        return directory.resolve(name);
    }

    private String nameOf(final String rom) {
        return Screenshots.pathFor(rom(rom), WHEN).getFileName().toString();
    }

    @Test
    void aPictureIsNamedAfterTheGameAndStampedWithTheTime() {
        assertEquals("Zelda-20260815-142233.png", nameOf("Zelda.nes"));
    }

    @Test
    void itLandsBesideTheROM() {
        // The same place the save slots and the .sav go, so a game's files are in one place.
        assertEquals(directory, Screenshots.pathFor(rom("Zelda.nes"), WHEN).getParent());
    }

    @Test
    void onlyTheLastDotIsAnExtension() {
        assertEquals("Super.Mario.Bros-20260815-142233.png", nameOf("Super.Mario.Bros.nes"));
    }

    @Test
    void aNameWithNoExtensionKeepsAllOfItself() {
        assertEquals("Zelda-20260815-142233.png", nameOf("Zelda"));
    }

    @Test
    void aSecondPictureInTheSameSecondDoesNotLandOnTheFirst() throws IOException {
        // One second is a long press of the key, and the stamp is only good to the second.
        var first = Screenshots.pathFor(rom("Zelda.nes"), WHEN);
        Files.createFile(first);

        var second = Screenshots.pathFor(rom("Zelda.nes"), WHEN);
        Files.createFile(second);

        assertNotEquals(first, second);
        assertEquals("Zelda-20260815-142233-2.png", second.getFileName().toString());
        assertEquals(
                "Zelda-20260815-142233-3.png",
                Screenshots.pathFor(rom("Zelda.nes"), WHEN).getFileName().toString(),
                "and the third");
        assertTrue(Files.exists(first), "the first is still there");
    }

    @Test
    void anotherSecondStartsAgainFromTheBareName() throws IOException {
        Files.createFile(Screenshots.pathFor(rom("Zelda.nes"), WHEN));

        assertEquals(
                "Zelda-20260815-142234.png",
                Screenshots.pathFor(rom("Zelda.nes"), WHEN.plusSeconds(1)).getFileName().toString());
    }
}
