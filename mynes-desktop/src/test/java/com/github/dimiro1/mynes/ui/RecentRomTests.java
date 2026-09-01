package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for an entry in File &gt; Open Recent.
 * <p>
 * Two questions, and the second is the one with teeth. What the menu writes on an item is the
 * easy half; whether two entries are the same game decides whether the list holds each of them
 * once, and whether a hack and the cartridge it was cut against are one row or two.
 */
class RecentRomTests {
    @TempDir
    private Path directory;

    @Test
    void aCartridgeIsLabelledWithItsFileName() {
        assertEquals("smb.nes", new RecentRom(directory.resolve("smb.nes"), null).label());
    }

    /**
     * The shape the title bar already uses for a patched game, since the two are naming the same
     * thing in the same window.
     */
    @Test
    void aPatchedGameIsLabelledWithBoth() {
        var game = new RecentRom(directory.resolve("smb.nes"), directory.resolve("hack.ips"));

        assertEquals("smb.nes + hack.ips", game.label());
    }

    @Test
    void theTooltipIsTheWholeOfBothPaths() {
        var game = new RecentRom(directory.resolve("smb.nes"), directory.resolve("hack.ips"));

        assertEquals(directory.resolve("smb.nes") + " + " + directory.resolve("hack.ips"),
                game.describe());
    }

    /**
     * Which is what keeps a list built on "each game once" from listing the same cartridge twice
     * because it was reached from two different working directories.
     */
    @Test
    void theSameCartridgeReachedTwoWaysIsOneGame() {
        assertEquals(
                new RecentRom(directory.resolve("smb.nes"), null),
                new RecentRom(directory.resolve("roms/../smb.nes"), null));
    }

    /**
     * A hack is a different game from the cartridge it was cut against -- the same thing the save
     * states say when they are named after the patch rather than the ROM.
     */
    @Test
    void aPatchedGameIsNotTheGameUnderneathIt() {
        assertNotEquals(
                new RecentRom(directory.resolve("smb.nes"), null),
                new RecentRom(directory.resolve("smb.nes"), directory.resolve("hack.ips")));
    }

    @Test
    void aCartridgeThatIsThereIsThere() throws IOException {
        Files.createFile(directory.resolve("smb.nes"));

        assertTrue(new RecentRom(directory.resolve("smb.nes"), null).isThere());
    }

    @Test
    void aCartridgeThatHasBeenMovedIsNot() {
        assertFalse(new RecentRom(directory.resolve("smb.nes"), null).isThere());
    }

    /**
     * Both files, because the patch is what makes it that game: applying nothing to the cartridge
     * would open the original under the name of the hack.
     */
    @Test
    void aPatchedGameNeedsItsPatchAsWell() throws IOException {
        Files.createFile(directory.resolve("smb.nes"));

        var game = new RecentRom(directory.resolve("smb.nes"), directory.resolve("hack.ips"));

        assertFalse(game.isThere());

        Files.createFile(directory.resolve("hack.ips"));

        assertTrue(game.isThere());
    }
}
