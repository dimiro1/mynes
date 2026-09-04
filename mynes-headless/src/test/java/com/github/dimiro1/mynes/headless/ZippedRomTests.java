package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A cartridge inside a zip, which is how nearly every collection ships one.
 * <p>
 * The claim being tested is that it makes no difference: the same cartridge run from a {@code .nes}
 * and from a zip holding that {@code .nes} is the same digest, the same picture and the same report
 * but for one field. Everything downstream depends on that -- a patch is applied to what came out of
 * the archive, a movie recorded from one plays back from the other, and a save state carries a
 * digest that has to match either way.
 * <p>
 * The zips are built here rather than checked in, unlike the {@code .ips} beside them. A patch is a
 * file format a romhack arrives in and so has to be tested as a file somebody else wrote; a zip
 * written by {@link ZipOutputStream} and read by {@code ZipInputStream} is the JDK talking to itself,
 * and a checked-in one would only be testing that nobody had swapped the fixture.
 */
class ZippedRomTests {
    private static final String ROM = "src/test/resources/hello-world/hello-world.nes";
    private static final String PATCH = "src/test/resources/hello-world/mynes.ips";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FRAMES = "60";

    @TempDir
    private Path out;

    @Test
    void aZipHoldingOneCartridgeRunsIt() throws Exception {
        var zipped = report(run("zipped", zip("game.zip", Map.of("hello-world.nes", rom()))));
        var plain = report(run("plain", ROM));

        assertEquals(
                plain.at("/cart/sha256").asText(), zipped.at("/cart/sha256").asText(),
                "the digest is of what came out of the zip, which is the cartridge");
        assertEquals(
                plain.at("/video/finalFrame/hash").asText(),
                zipped.at("/video/finalFrame/hash").asText());
    }

    /**
     * The one field that differs, and the only thing in the whole document that says a zip was
     * involved: the file above it is the archive, since that is what somebody named and what the
     * saves are filed under.
     */
    @Test
    void theReportSaysWhichFileInsideItRan() throws Exception {
        var archive = zip("game.zip", Map.of("hello-world.nes", rom()));
        var zipped = report(run("zipped", archive));

        assertEquals(archive, zipped.at("/cart/file").asText());
        assertEquals("hello-world.nes", zipped.at("/cart/entry").asText());
        assertTrue(report(run("plain", ROM)).at("/cart/entry").isNull(),
                "and it is explicitly null for a cartridge that was not in one");
    }

    /**
     * Named by what is in it rather than by what it is called, so a zip that arrived with the wrong
     * extension still opens.
     */
    @Test
    void aZipIsRecognisedWhateverItIsCalled() throws Exception {
        var archive = zip("collection.nes", Map.of("hello-world.nes", rom()));

        assertEquals("hello-world.nes", report(run("misnamed", archive)).at("/cart/entry").asText());
    }

    @Test
    void aCartridgeInAFolderInsideTheZipStillRuns() throws Exception {
        var archive = zip("game.zip", Map.of("roms/hello-world.nes", rom()));

        assertEquals("roms/hello-world.nes", report(run("nested", archive)).at("/cart/entry").asText());
    }

    /**
     * Refused rather than guessed at: the first entry in a zip is whichever the packer happened to
     * write first, and a run nobody can identify afterwards is worse than a run that did not start.
     */
    @Test
    void aZipHoldingTwoCartridgesIsRefusedUntilOneIsNamed() throws Exception {
        var archive = zip("many.zip", ordered("a.nes", rom(), "b.nes", rom()));

        assertEquals(Headless.EXIT_ROM, headless(archive, out.resolve("refused")));
        assertEquals("b.nes", report(run("named", archive, "--entry", "b.nes")).at("/cart/entry").asText());
    }

    /**
     * Either the whole name the zip stores or just the end of it, since a person reading a message
     * that lists {@code roms/a.nes} may reasonably type either.
     */
    @Test
    void theEntryMayBeNamedInFullOrByItsFileName() throws Exception {
        var archive = zip("many.zip", ordered("roms/a.nes", rom(), "roms/b.nes", rom()));

        assertEquals(
                "roms/b.nes",
                report(run("full", archive, "--entry", "roms/b.nes")).at("/cart/entry").asText());
        assertEquals(
                "roms/b.nes",
                report(run("short", archive, "--entry", "b.nes")).at("/cart/entry").asText());
    }

    @Test
    void aZipHoldingNoCartridgeIsRefused() throws Exception {
        var archive = zip("none.zip", Map.of("readme.txt", "nothing here".getBytes()));

        assertEquals(Headless.EXIT_ROM, headless(archive, out.resolve("empty")));
    }

    @Test
    void anEntryTheZipDoesNotHoldIsRefused() throws Exception {
        var archive = zip("game.zip", Map.of("hello-world.nes", rom()));

        assertEquals(
                Headless.EXIT_ROM,
                headless(archive, out.resolve("missing"), "--entry", "somethingelse.nes"));
    }

    /**
     * Refused rather than ignored, for the reason a misspelled {@code --palette} is: a flag that
     * quietly did nothing would let a script think it had picked a cartridge it had not.
     */
    @Test
    void namingAnEntryOfSomethingThatIsNotAZipIsRefused() {
        assertEquals(
                Headless.EXIT_ROM,
                headless(ROM, out.resolve("notazip"), "--entry", "hello-world.nes"));
    }

    /**
     * The patch path needs to know nothing about any of this: an offset in an IPS file is counted
     * from the front of the cartridge, and the cartridge is what comes out of the archive.
     */
    @Test
    void aPatchIsAppliedToWhatCameOutOfTheZip() throws Exception {
        var archive = zip("game.zip", Map.of("hello-world.nes", rom()));

        var zipped = report(run("zipped", archive, "--patch", PATCH));
        var plain = report(run("plain", ROM, "--patch", PATCH));

        assertEquals(plain.at("/cart/sha256").asText(), zipped.at("/cart/sha256").asText());
        assertEquals(
                plain.at("/video/finalFrame/hash").asText(),
                zipped.at("/video/finalFrame/hash").asText());
    }

    /**
     * A movie carries the cartridge's digest and nothing about where the file was, so the two are
     * interchangeable at either end of a recording. Worth holding on to: it is what lets a movie
     * recorded by somebody with a folder of loose ROMs replay for somebody with a folder of zips.
     */
    @Test
    void aMovieRecordedFromTheLooseRomReplaysFromTheZip() throws Exception {
        var archive = zip("game.zip", Map.of("hello-world.nes", rom()));
        var recorded = out.resolve("take.mnm");
        var fromRom = out.resolve("a.mn");
        var fromZip = out.resolve("b.mn");

        assertEquals(Headless.EXIT_OK, headless(ROM, out.resolve("record"),
                "--input", "30:start", "--record", recorded.toString(),
                "--save-state", fromRom.toString()));
        assertEquals(Headless.EXIT_OK, headless(archive, out.resolve("replay"),
                "--play", recorded.toString(), "--save-state", fromZip.toString()));

        assertArrayEquals(Files.readAllBytes(fromRom), Files.readAllBytes(fromZip));
    }

    /**
     * Nothing is unpacked to disk, so opening a zip leaves nothing behind to tidy up -- the same
     * promise applying a patch already makes about the ROM it does not write to.
     */
    @Test
    void theZipIsNeverUnpackedAndNeverWrittenTo() throws Exception {
        var archive = Path.of(zip("game.zip", Map.of("hello-world.nes", rom())));
        var before = Files.readAllBytes(archive);
        var beside = out.resolve("beside");

        Files.createDirectories(beside);
        Files.copy(archive, beside.resolve("game.zip"));
        run("clean", beside.resolve("game.zip").toString());

        assertArrayEquals(before, Files.readAllBytes(archive));
        assertEquals(
                1, Files.list(beside).count(),
                "the folder the archive was in holds nothing it did not hold before");
    }

    // ================================================================================== internals

    private static byte[] rom() throws IOException {
        return Files.readAllBytes(Path.of(ROM));
    }

    /**
     * {@link Map#of} is deliberately unordered, and two of these tests are about which entry comes
     * first.
     */
    private static Map<String, byte[]> ordered(
            final String first, final byte[] a, final String second, final byte[] b) {
        var entries = new LinkedHashMap<String, byte[]>();

        entries.put(first, a);
        entries.put(second, b);

        return entries;
    }

    private String zip(final String name, final Map<String, byte[]> entries) throws IOException {
        var path = out.resolve(name);

        try (OutputStream file = Files.newOutputStream(path);
             var archive = new ZipOutputStream(file)) {
            for (var entry : entries.entrySet()) {
                archive.putNextEntry(new ZipEntry(entry.getKey()));
                archive.write(entry.getValue());
                archive.closeEntry();
            }
        }

        return path.toString();
    }

    /**
     * Runs a cartridge into a directory of its own, so that two runs can be compared file by file.
     *
     * @return where the artifacts went.
     */
    private Path run(final String name, final String rom, final String... extra) {
        var into = out.resolve(name);

        assertEquals(Headless.EXIT_OK, headless(rom, into, extra));

        return into;
    }

    private static int headless(final String rom, final Path into, final String... extra) {
        var args = new String[extra.length + 7];

        args[0] = "--rom";
        args[1] = rom;
        args[2] = "--out";
        args[3] = into.toString();
        args[4] = "--quiet";
        args[5] = "--frames";
        args[6] = FRAMES;

        System.arraycopy(extra, 0, args, 7, extra.length);

        return Headless.run(args);
    }

    private static JsonNode report(final Path into) throws IOException {
        return MAPPER.readTree(Files.readString(into.resolve("report.json")));
    }
}
