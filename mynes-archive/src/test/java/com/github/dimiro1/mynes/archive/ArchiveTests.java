package com.github.dimiro1.mynes.archive;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ArchiveTests {
    private static byte[] zipOf(final Map<String, byte[]> entries) throws IOException {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            for (var entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }

        return bytes.toByteArray();
    }

    private static byte[] zipOf(final String name, final byte[] content) throws IOException {
        return zipOf(Map.of(name, content));
    }

    private static byte[] contentOf(final String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void aZipIsRecognisedByItsFirstFourBytes() throws Exception {
        assertTrue(Archive.looksLikeOne(zipOf("game.nes", contentOf("NES"))));
    }

    /**
     * The one a caller reaches for when they have picked a file and do not yet know what it is: an
     * iNES header does not begin PK, so the plain cartridge goes straight on to Cart.load.
     */
    @Test
    void aCartridgeIsNotMistakenForAZip() {
        assertFalse(Archive.looksLikeOne(new byte[]{'N', 'E', 'S', 0x1A, 2, 1}));
        assertFalse(Archive.looksLikeOne(new byte[0]));
        assertFalse(Archive.looksLikeOne(new byte[]{'P', 'K'}));
    }

    /**
     * An empty zip is nothing but its end-of-central-directory record, so it does not start the way
     * every other zip does. Recognised anyway, because "there is nothing in here" is a better thing
     * to be told than "this is not a zip".
     */
    @Test
    void anEmptyZipIsStillAZip() throws Exception {
        var empty = zipOf(Map.of());

        assertTrue(Archive.looksLikeOne(empty));
        assertEquals(0, Archive.open(empty, "empty.zip").files().size());
    }

    @Test
    void aFileComesBackWithTheBytesItWentInWith() throws Exception {
        var content = contentOf("a cartridge, more or less");
        var archive = Archive.open(zipOf("game.nes", content), "game.zip");

        assertEquals(1, archive.files().size());
        assertEquals("game.nes", archive.files().getFirst().name());
        assertArrayEquals(content, archive.files().getFirst().bytes());
    }

    /**
     * Bigger than the buffer the read loop uses, which is where an entry read in one chunk and an
     * entry read in several part company.
     */
    @Test
    void aFileLargerThanOneChunkComesBackWhole() throws Exception {
        var content = new byte[512 * 1024];

        for (var i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }

        var archive = Archive.open(zipOf("game.nes", content), "game.zip");

        assertArrayEquals(content, archive.files().getFirst().bytes());
    }

    @Test
    void theFilesComeBackInTheOrderTheArchiveListsThem() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("b.nes", contentOf("b"));
        entries.put("a.nes", contentOf("a"));
        entries.put("c.nes", contentOf("c"));

        var archive = Archive.open(zipOf(entries), "set.zip");

        assertEquals(
                java.util.List.of("b.nes", "a.nes", "c.nes"),
                archive.files().stream().map(Archive.Entry::name).toList());
    }

    @Test
    void aFolderIsNotOneOfTheFiles() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("roms/", new byte[0]);
        entries.put("roms/game.nes", contentOf("NES"));

        var archive = Archive.open(zipOf(entries), "game.zip");

        assertEquals(1, archive.files().size());
        assertEquals("roms/game.nes", archive.files().getFirst().name());
    }

    /**
     * The name a caller resolves against the folder the archive is in, so it is the last segment and
     * never the folders above it.
     */
    @Test
    void theFileNameIsTheLastSegmentOfTheName() {
        assertEquals("game.nes", new Archive.Entry("roms/game.nes", new byte[0]).fileName());
        assertEquals("game.nes", new Archive.Entry("roms\\game.nes", new byte[0]).fileName());
        assertEquals("game.nes", new Archive.Entry("game.nes", new byte[0]).fileName());
    }

    /**
     * Neither is a name a file can have, and both would escape the folder somebody's saves are meant
     * to land in once resolved against it.
     */
    @Test
    void anEntryNamedAfterAFolderAboveIsNotOneOfTheFiles() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("..", contentOf("no"));
        entries.put("roms/..", contentOf("no"));
        entries.put("game.nes", contentOf("NES"));

        var archive = Archive.open(zipOf(entries), "game.zip");

        assertEquals(1, archive.files().size());
        assertEquals("game.nes", archive.files().getFirst().name());
    }

    @Test
    void onlyTheFilesNamedLikeCartridgesAreWanted() throws Exception {
        var entries = new LinkedHashMap<String, byte[]>();
        entries.put("readme.txt", contentOf("read me"));
        entries.put("game.nes", contentOf("NES"));
        entries.put("hack.ips", contentOf("PATCH"));

        var archive = Archive.open(zipOf(entries), "game.zip");

        assertEquals(3, archive.files().size());
        assertEquals(1, archive.endingIn("nes").size());
        assertEquals("game.nes", archive.endingIn("nes").getFirst().name());
        assertEquals(2, archive.endingIn("nes", "ips").size());
    }

    /**
     * A dump written by a DOS tool is the same cartridge as one written by anything since.
     */
    @Test
    void theExtensionIsMatchedWithoutRegardToCase() throws Exception {
        var archive = Archive.open(zipOf("GAME.NES", contentOf("NES")), "game.zip");

        assertEquals(1, archive.endingIn("nes").size());
    }

    /**
     * A file whose whole name is the extension is not one: {@code .nes} is a hidden file, and a
     * folder full of them is what a Mac leaves behind in an archive.
     */
    @Test
    void aNameThatIsNothingButTheExtensionIsNotACartridge() throws Exception {
        var archive = Archive.open(zipOf("nes", contentOf("NES")), "game.zip");

        assertEquals(0, archive.endingIn("nes").size());
    }

    @Test
    void aFileThatIsNotAZipIsRefusedBySayingSo() {
        var bytes = new byte[]{'N', 'E', 'S', 0x1A, 2, 1, 0, 0};
        var refused = assertThrows(
                InvalidArchiveException.class, () -> Archive.open(bytes, "game.zip"));

        assertTrue(refused.getMessage().startsWith("game.zip could not be unzipped: "));
        assertTrue(refused.getMessage().endsWith("."));
    }

    /**
     * A download that stopped half way is the commonest broken zip there is, and it has to fail here
     * rather than hand back the entries it managed.
     */
    @Test
    void aZipCutShortIsRefused() throws Exception {
        var whole = zipOf("game.nes", new byte[64 * 1024]);
        var half = Arrays.copyOf(whole, whole.length / 2);

        assertThrows(InvalidArchiveException.class, () -> Archive.open(half, "game.zip"));
    }
}
