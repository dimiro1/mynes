package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.patch.IPSPatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The same edit to the same cartridge, made twice: once as a romhack and once as a Game Genie.
 * <p>
 * {@link RomHackTests} runs the {@code .ips} beside this cartridge and checks that {@code Hello
 * World!} becomes {@code MyNES Patch!} on the screen. This runs the identical change through the
 * other route -- ten codes typed into a device in the cartridge slot, nothing patched at all -- and
 * asserts the two draw the same picture down to the byte. That is a stronger claim than "the codes
 * did something", because the answer key is a file format with a separate implementation behind it.
 * <p>
 * The cartridge is what makes it possible. It is a 32KB PRG NROM, so nothing is mirrored and file
 * offset {@code n} is CPU address {@code $8000 + (n - 16)} with no arithmetic left over: the patch
 * writes twelve bytes at file offset $398, which is CPU $8388, and ten of the twelve differ.
 * <p>
 * <strong>And the two runs are not the same cartridge in the report, which is the point of the
 * feature.</strong> A patched run has its own {@code cart.sha256}, because a patch really does make a
 * different image. A Game Genie run has the original's, because the cartridge is untouched -- so
 * {@code run.genie} is the only thing in the whole document that tells it apart from a plain run.
 */
class GameGenieRunTests {
    private static final String ROM = "src/test/resources/hello-world/hello-world.nes";
    private static final String PATCH = "src/test/resources/hello-world/mynes.ips";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String FRAMES = "60";

    /**
     * Where the patch writes, counted from the front of the file, header and all.
     */
    private static final int PATCH_AT = 0x398;

    /**
     * The same place on the CPU bus. Sixteen for the iNES header this cartridge has and the patch's
     * offsets are counted through.
     */
    private static final int CPU_AT = 0x8000 + PATCH_AT - 16;

    /**
     * {@code Hello World!} to {@code MyNES Patch!}, one eight letter code per byte that differs --
     * the space at index 5 and the {@code !} at index 11 are the same in both, so there are ten
     * rather than twelve.
     * <p>
     * Eight letters rather than six on purpose: the compare byte is checked against what the
     * cartridge really answers with, so this exercises the path a published code for a banked game
     * would take. They are written down rather than worked out from the {@code .ips} at run time,
     * which would make this a test of an encoder instead of a test of the decoder and the bus.
     */
    private static final List<String> CODES = List.of(
            "IKAEAUAK",   // $8388  H -> M
            "PNAEPLIV",   // $8389  e -> y
            "TKAEZUGV",   // $838A  l -> N
            "IKAELUGT",   // $838B  l -> E
            "LSAEGUYT",   // $838C  o -> S
            "ASAETLYI",   // $838E  W -> P
            "PVAEYUYT",   // $838F  o -> a
            "GNPAALZY",   // $8390  r -> t
            "LVPAPUGT",   // $8391  l -> c
            "AVPAZLGV");  // $8392  d -> h

    @TempDir
    private Path out;

    /**
     * What the ten codes claim, checked against the patch and the cartridge they were cut from.
     * <p>
     * The alternative is ten puzzling failures further down when somebody replaces a fixture. This
     * says which byte moved and where, in a message anybody can read.
     */
    @Test
    void theCodesAreTheSameEditThePatchIs() throws Exception {
        var image = Files.readAllBytes(Path.of(ROM));
        var patched = IPSPatch.read(Files.readAllBytes(Path.of(PATCH)), PATCH).applyTo(image);

        var expected = 0;

        for (var i = 0; i < 12; i++) {
            var before = Byte.toUnsignedInt(image[PATCH_AT + i]);
            var after = Byte.toUnsignedInt(patched[PATCH_AT + i]);

            if (before == after) {
                continue;
            }

            var code = GameGenieCode.decode(CODES.get(expected));

            assertEquals(CPU_AT + i, code.address(), code.text() + " fires in the wrong place");
            assertEquals(after, code.value(), code.text() + " writes the wrong byte");
            assertEquals(before, code.compare(), code.text() + " expects the wrong byte");

            expected++;
        }

        assertEquals(CODES.size(), expected, "there is one code for every byte the patch changes");
    }

    /**
     * The claim this file exists to make.
     */
    @Test
    void theCodesDrawWhatTheRomhackDraws() throws Exception {
        var plain = run("plain");
        var hacked = run("hacked", "--patch", PATCH);
        var cheated = run("cheated", "--genie", String.join(",", CODES));

        // Both differ from the untouched cartridge first. Two runs that each quietly did nothing
        // would agree with each other too, and that would be a passing test proving nothing.
        assertNotEquals(hashIn(plain), hashIn(hacked), "the patch changed the picture");
        assertNotEquals(hashIn(plain), hashIn(cheated), "and so did the codes");

        assertEquals(hashIn(hacked), hashIn(cheated), "and they changed it into the same picture");
        assertArrayEquals(shotIn(hacked), shotIn(cheated), "the same PNG, byte for byte");
    }

    /**
     * A compare code is pinned to the byte it was written for, so codes cut against the hacked
     * cartridge do nothing at all to the hacked cartridge -- every one of them finds {@code MyNES
     * Patch!} already there and the comparison fails.
     */
    @Test
    void aCompareCodeDoesNothingToACartridgeThatAlreadySaysWhatItWants() throws Exception {
        var hacked = run("hacked", "--patch", PATCH);
        var both = run("both", "--patch", PATCH, "--genie", String.join(",", CODES));

        assertEquals(hashIn(hacked), hashIn(both));
    }

    /**
     * The cartridge is not modified, so the digest is the plain one -- unlike a patch, which really
     * does produce a different image and says so. Which means a save state taken with codes in will
     * load into a machine with them out, and {@code run.genie} is the only warning there is.
     */
    @Test
    void aCheatedRunIsStillTheSameCartridge() throws Exception {
        var before = Files.readAllBytes(Path.of(ROM));

        var plain = report(run("plain"));
        var cheated = report(run("cheated", "--genie", String.join(",", CODES)));
        var hacked = report(run("hacked", "--patch", PATCH));

        assertEquals(
                plain.at("/cart/sha256").asText(),
                cheated.at("/cart/sha256").asText(),
                "a code changes nothing about the cartridge, so the digest cannot change");
        assertNotEquals(
                plain.at("/cart/sha256").asText(),
                hacked.at("/cart/sha256").asText(),
                "where a patch does, and does say so");

        assertEquals(0, cheated.at("/cart/patches").size(), "nothing was patched");
        assertArrayEquals(
                before, Files.readAllBytes(Path.of(ROM)),
                "and the file on disk was never opened for writing");
    }

    /**
     * Always there, empty when there are no codes, one entry per code when there are -- so two
     * reports line up key for key however either run was made.
     */
    @Test
    void theReportSaysWhichCodesWereIn() throws Exception {
        assertEquals(0, report(run("plain")).at("/run/genie").size());

        var codes = report(run("one", "--genie", "SXIOPO")).at("/run/genie");

        assertEquals(1, codes.size());
        assertEquals("SXIOPO", codes.get(0).get("code").asText());
        assertEquals(0x91D9, codes.get(0).get("address").asInt());
        assertEquals(0xAD, codes.get(0).get("value").asInt());
        assertTrue(codes.get(0).get("compare").isNull(), "six letters name no bank");
    }

    /**
     * Read off the machine rather than off the command line, so a session that changed its mind is
     * reported as it ended.
     */
    @Test
    void theReportSaysWhatTheMachineHoldsRatherThanWhatWasAskedFor() throws Exception {
        var into = out.resolve("repl");

        assertEquals(Headless.EXIT_OK, Headless.run(new String[]{
                "--rom", ROM,
                "--out", into.toString(),
                "--quiet",
                "--genie", "SXIOPO",
                "--script", script("genie ZEXPYGLA", "ungenie SXIOPO", "quit").toString()}));

        var codes = report(into).at("/run/genie");

        assertEquals(1, codes.size());
        assertEquals("ZEXPYGLA", codes.get(0).get("code").asText(), "the one still in at the end");
        assertEquals(0x03, codes.get(0).get("compare").asInt());
    }

    /**
     * The codes travel inside a movie, which is the only place they can travel: the cartridge a code
     * was played against is byte for byte the cartridge it was not, so a replay that took its codes
     * from the command line would be a replay of a different run -- and one that took none would
     * quietly play the honest game and look like it had worked.
     */
    @Test
    void aMovieCarriesTheCodesItWasRecordedWith() throws Exception {
        var take = out.resolve("cheated.mnm");
        var cheated = run("cheated", "--genie", String.join(",", CODES),
                "--record", take.toString());

        var replayed = out.resolve("replayed");

        // Nobody types a code here, and --play would refuse one if they tried.
        assertEquals(Headless.EXIT_OK, Headless.run(new String[]{
                "--rom", ROM,
                "--out", replayed.toString(),
                "--quiet",
                "--screenshot", "last",
                "--play", take.toString()}));

        assertEquals(CODES.size(), report(replayed).at("/run/genie").size(),
                "the device was filled from the movie");
        assertEquals(hashIn(cheated), hashIn(replayed));
        assertArrayEquals(shotIn(cheated), shotIn(replayed), "the same PNG, byte for byte");
    }

    @Test
    void aCodeThatIsNotOneStopsTheRunBeforeItStarts() {
        assertEquals(Headless.EXIT_USAGE, Headless.run(new String[]{
                "--rom", ROM, "--out", out.toString(), "--quiet", "--genie", "GOSSIB"}));
    }

    // ================================================================================== internals

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

    private Path script(final String... commands) throws IOException {
        var path = out.resolve("session.txt");

        Files.writeString(path, String.join(System.lineSeparator(), commands));

        return path;
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
