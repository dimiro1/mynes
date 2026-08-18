package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimiro1.mynes.patch.IPSPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An actual romhack, applied to an actual cartridge, checked at the screen.
 * <p>
 * The cartridge draws "Hello World!" and two lines of Russian, and it is here because it is public
 * domain and because nobody has touched it since 2015: a fixture whose upstream still changes would
 * make this test a report on somebody else's repository. The patch beside it is a real {@code .ips}
 * file rather than one built at run time, for the same reason -- what is being tested is the file
 * format a romhack arrives in, so the test is handed one.
 * <p>
 * The hack is the oldest kind there is, a text edit: the twelve ASCII bytes of {@code Hello World!}
 * in the PRG-ROM become {@code MyNES Patch!}. The two are the same length on purpose, so nothing
 * after them moves and the cartridge is the same size, the same mapper and the same everything else
 * -- which is what makes the changed picture attributable to those twelve bytes. That picture was
 * checked by eye when this was written: the first line reads MyNES Patch!, and the Russian below it
 * is untouched.
 */
class RomHackTests {
    private static final String ROM = "src/test/resources/hello-world/hello-world.nes";
    private static final String PATCH = "src/test/resources/hello-world/mynes.ips";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String ORIGINAL = "Hello World!";
    private static final String HACKED = "MyNES Patch!";

    /**
     * Long enough for the text to be on the screen, which is all this needs of the cartridge.
     */
    private static final String FRAMES = "60";

    @TempDir
    private Path out;

    /**
     * The fixture is a binary, so nothing about it can be read in a diff. This is where it says what
     * it is: one record, twelve bytes, no truncation -- and a patch that arrived corrupted fails
     * here rather than as five puzzling failures below it.
     */
    @Test
    void theFixtureIsThePatchItClaimsToBe() throws Exception {
        var patch = IPSPatch.read(Files.readAllBytes(Path.of(PATCH)), PATCH);

        assertEquals(1, patch.records());
        assertEquals(HACKED.length(), patch.bytes());
        assertEquals(IPSPatch.NO_TRUNCATION, patch.truncateTo());
    }

    @Test
    void theHackReplacesTheStringItNames() throws Exception {
        var image = Files.readAllBytes(Path.of(ROM));
        var patched = IPSPatch.read(Files.readAllBytes(Path.of(PATCH)), PATCH).applyTo(image);

        assertTrue(text(image).contains(ORIGINAL), "the cartridge still says what it used to");
        assertEquals(image.length, patched.length, "a same-length edit does not resize the file");
        assertTrue(text(patched).contains(HACKED));
        assertFalse(text(patched).contains(ORIGINAL));
    }

    /**
     * The twelve bytes reach the television, which is the only claim worth making about a patcher.
     */
    @Test
    void theHackShowsOnTheScreen() throws Exception {
        var before = run("plain");
        var after = run("hacked", "--patch", PATCH);

        assertNotEquals(
                hashIn(before), hashIn(after),
                "the patched cartridge draws a different picture");
        assertFalse(
                Arrays.equals(shotIn(before), shotIn(after)),
                "and the two PNGs are not the same file either");
    }

    /**
     * The cartridge on disk is not the one that ran, and is exactly as it was.
     */
    @Test
    void theRomItselfIsNeverTouched() throws Exception {
        var before = Files.readAllBytes(Path.of(ROM));

        var plain = run("plain");
        var hacked = run("hacked", "--patch", PATCH);

        assertArrayEquals(before, Files.readAllBytes(Path.of(ROM)));
        assertNotEquals(
                report(plain).at("/cart/sha256").asText(),
                report(hacked).at("/cart/sha256").asText(),
                "the digest names the image that ran, so a hack has its own");
    }

    /**
     * A patched run is as deterministic as an unpatched one, which is what lets a hack be regression
     * tested the same way everything else here is.
     */
    @Test
    void twoRunsOfTheSameHackAgree() throws Exception {
        var first = run("first", "--patch", PATCH);
        var second = run("second", "--patch", PATCH);

        assertEquals(hashIn(first), hashIn(second));
        assertArrayEquals(shotIn(first), shotIn(second));
    }

    @Test
    void theReportSaysWhichHackItWas() throws Exception {
        var patches = report(run("hacked", "--patch", PATCH)).at("/cart/patches");

        assertEquals(1, patches.size());
        assertEquals(PATCH, patches.get(0).get("path").asText());
        assertEquals(1, patches.get(0).get("records").asInt());
        assertEquals(HACKED.length(), patches.get(0).get("bytes").asInt());
    }

    // ================================================================================== internals

    /**
     * Runs the cartridge into a directory of its own, so that two runs can be compared file by file
     * rather than one after the other.
     *
     * @return where the artifacts went.
     */
    private Path run(final String name, final String... extra) {
        var into = out.resolve(name);
        var args = new String[extra.length + 9];

        args[0] = "--rom";
        args[1] = ROM;
        args[2] = "--out";
        args[3] = into.toString();
        args[4] = "--quiet";
        args[5] = "--frames";
        args[6] = FRAMES;
        args[7] = "--screenshot";
        args[8] = "last";

        System.arraycopy(extra, 0, args, 9, extra.length);

        assertEquals(Headless.EXIT_OK, Headless.run(args));

        return into;
    }

    /**
     * The whole file as characters, for asking whether a string is in it. ISO 8859-1 because it is
     * the one encoding that maps every byte to exactly one character, so nothing in a ROM full of
     * code and tiles can throw the search off.
     */
    private static String text(final byte[] image) {
        return new String(image, StandardCharsets.ISO_8859_1);
    }

    private static JsonNode report(final Path into) throws IOException {
        return MAPPER.readTree(Files.readString(into.resolve("report.json")));
    }

    private static String hashIn(final Path into) throws IOException {
        return report(into).at("/video/finalFrame/hash").asText();
    }

    private static byte[] shotIn(final Path into) throws IOException {
        return Files.readAllBytes(into.resolve("frame-0000" + FRAMES + ".png"));
    }
}
