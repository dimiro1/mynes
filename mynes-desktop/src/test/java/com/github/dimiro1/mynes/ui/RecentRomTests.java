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
        assertEquals("smb.nes", new RecentRom(directory.resolve("smb.nes"), null, null).label());
    }

    /**
     * The shape the title bar already uses for a patched game, since the two are naming the same
     * thing in the same window.
     */
    @Test
    void aPatchedGameIsLabelledWithBoth() {
        var game = new RecentRom(directory.resolve("smb.nes"), null, directory.resolve("hack.ips"));

        assertEquals("smb.nes + hack.ips", game.label());
    }

    @Test
    void theTooltipIsTheWholeOfBothPaths() {
        var game = new RecentRom(directory.resolve("smb.nes"), null, directory.resolve("hack.ips"));

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
                new RecentRom(directory.resolve("smb.nes"), null, null),
                new RecentRom(directory.resolve("roms/../smb.nes"), null, null));
    }

    /**
     * A hack is a different game from the cartridge it was cut against -- the same thing the save
     * states say when they are named after the patch rather than the ROM.
     */
    @Test
    void aPatchedGameIsNotTheGameUnderneathIt() {
        assertNotEquals(
                new RecentRom(directory.resolve("smb.nes"), null, null),
                new RecentRom(directory.resolve("smb.nes"), null, directory.resolve("hack.ips")));
    }

    @Test
    void aCartridgeThatIsThereIsThere() throws IOException {
        Files.createFile(directory.resolve("smb.nes"));

        assertTrue(new RecentRom(directory.resolve("smb.nes"), null, null).isThere());
    }

    @Test
    void aCartridgeThatHasBeenMovedIsNot() {
        assertFalse(new RecentRom(directory.resolve("smb.nes"), null, null).isThere());
    }

    /**
     * The cartridge rather than the archive, because that is the game. A collection whose zips are
     * each named after the game inside reads the same either way, and one whose zips are not is what
     * this is for.
     */
    @Test
    void aCartridgeInsideAZipIsLabelledWithTheNameInside() {
        var game = new RecentRom(directory.resolve("collection.zip"), "roms/smb.nes", null);

        assertEquals("smb.nes", game.label());
        assertEquals(directory.resolve("collection.zip") + " > roms/smb.nes", game.describe());
    }

    /**
     * A zip holding two games is two entries on the menu, which is the whole reason the name inside
     * is remembered at all.
     */
    @Test
    void twoCartridgesInOneZipAreTwoGames() {
        assertNotEquals(
                new RecentRom(directory.resolve("many.zip"), "a.nes", null),
                new RecentRom(directory.resolve("many.zip"), "b.nes", null));
    }

    /**
     * Opening it is the only way to find out whether the archive still holds the entry, and reading
     * every zip on the list to build a menu would cost a player with ten of them a disk full of work
     * every time they pulled File down.
     */
    @Test
    void aZipIsOnlyCheckedForBeingThere() throws IOException {
        Files.createFile(directory.resolve("collection.zip"));

        assertTrue(new RecentRom(
                directory.resolve("collection.zip"), "not-in-there.nes", null).isThere());
    }

    /**
     * Both files, because the patch is what makes it that game: applying nothing to the cartridge
     * would open the original under the name of the hack.
     */
    @Test
    void aPatchedGameNeedsItsPatchAsWell() throws IOException {
        Files.createFile(directory.resolve("smb.nes"));

        var game = new RecentRom(directory.resolve("smb.nes"), null, directory.resolve("hack.ips"));

        assertFalse(game.isThere());

        Files.createFile(directory.resolve("hack.ips"));

        assertTrue(game.isThere());
    }
}
