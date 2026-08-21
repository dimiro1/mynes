package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
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
 * A whole headless run, from a command line to the files it leaves behind.
 * <p>
 * Driven through {@link Headless#run} rather than through the classes underneath it, because the
 * things worth checking here are the ones that only exist once everything is joined up: that the
 * report describes the artifacts that are actually on disk, that an expectation reaches the exit
 * code, and that two runs of the same command agree.
 * <p>
 * Everything runs on nestest, which is vendored, small, and draws a menu within a few frames.
 */
class HeadlessRunTests {
    private static final String ROM = "src/test/resources/nestest/nestest.nes";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Where nestest's PRG-ROM and CHR-ROM start in the file: after the sixteen byte header, and
     * after the one 16KB bank of program that follows it. Patch offsets count from the front of the
     * file, header included, which is what these two are here to say out loud.
     */
    private static final int PRG_AT = 16;
    private static final int CHR_AT = PRG_AT + 0x4000;

    @TempDir
    private Path out;

    /**
     * An .ips file, assembled here because a patch for a vendored ROM is not something to vendor.
     * Every number in the format is big endian.
     */
    private final class Patch {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        private Patch() {
            bytes.writeBytes("PATCH".getBytes(StandardCharsets.US_ASCII));
        }

        private Patch record(final int offset, final int... data) {
            offset(offset);
            bytes.write(data.length >> 8);
            bytes.write(data.length);

            for (var value : data) {
                bytes.write(value);
            }

            return this;
        }

        private Patch run(final int offset, final int count, final int value) {
            offset(offset);
            bytes.write(0);
            bytes.write(0);
            bytes.write(count >> 8);
            bytes.write(count);
            bytes.write(value);

            return this;
        }

        private Path write(final String name) throws IOException {
            bytes.writeBytes("EOF".getBytes(StandardCharsets.US_ASCII));

            return Files.write(out.resolve(name), bytes.toByteArray());
        }

        private void offset(final int offset) {
            bytes.write(offset >> 16);
            bytes.write(offset >> 8);
            bytes.write(offset);
        }
    }

    private JsonNode reportAt(final Path path) throws IOException {
        return MAPPER.readTree(Files.readString(path));
    }

    /**
     * Runs, quietly and into the temporary directory, with whatever else is asked for.
     */
    private int run(final String... extra) {
        var args = new String[extra.length + 7];

        args[0] = "--rom";
        args[1] = ROM;
        args[2] = "--out";
        args[3] = out.toString();
        args[4] = "--quiet";
        args[5] = "--frames";
        args[6] = "60";

        System.arraycopy(extra, 0, args, 7, extra.length);

        return Headless.run(args);
    }

    private JsonNode report() throws IOException {
        return reportAt(out.resolve("report.json"));
    }

    @Test
    void aRunProducesTheFramesItWasAskedFor() throws Exception {
        assertEquals(Headless.EXIT_OK, run());

        assertEquals(60, report().at("/run/frames").asLong());
        assertEquals("frames", report().at("/run/stoppedBecause").asText());
        assertTrue(report().at("/run/completed").asBoolean());
    }

    @Test
    void theReportNamesTheCartAndParsesAsJson() throws Exception {
        run();

        var cart = report().get("cart");

        assertEquals(0, cart.get("mapper").asInt());
        assertEquals("nestest.nes", cart.get("name").asText());
        assertEquals(16384, cart.get("prgROMBytes").asInt());
        assertEquals(64, cart.get("sha256").asText().length());
    }

    @Test
    void aScreenshotLandsWhereTheReportSaysItDid() throws Exception {
        run("--screenshot", "60");

        var frames = report().at("/video/screenshots/frames");
        assertEquals(1, frames.size());
        assertEquals(60, frames.get(0).asLong());

        var image = ImageIO.read(out.resolve("frame-000060.png").toFile());
        assertEquals(256, image.getWidth());
        assertEquals(224, image.getHeight());
    }

    @Test
    void fullFrameKeepsTheScanlinesATelevisionHides() throws Exception {
        run("--screenshot", "60", "--full-frame");

        var image = ImageIO.read(out.resolve("frame-000060.png").toFile());

        assertEquals(240, image.getHeight());
        assertEquals("full", report().at("/video/overscan").asText());
    }

    @Test
    void lastIsWhicheverFrameTheRunEndedOn() {
        run("--screenshot", "last");

        assertTrue(Files.exists(out.resolve("frame-000060.png")));
    }

    @Test
    void aScreenThatHasNotTurnedOnYetIsReportedAsBlank() {
        // One frame in, nestest has not drawn anything: the picture is a single colour, which is
        // what a machine that never started looks like and what --expect-not-blank is for.
        assertEquals(Headless.EXIT_EXPECTATION, run("--frames", "1", "--expect-not-blank"));
    }

    @Test
    void aScreenWithAMenuOnItIsNot() throws Exception {
        assertEquals(Headless.EXIT_OK, run("--expect-not-blank"));

        assertFalse(report().at("/video/finalFrame/blank").asBoolean());
        assertTrue(report().at("/video/finalFrame/uniqueColours").asInt() > 1);
    }

    @Test
    void theAudioStatsAreThereWithoutAWavFile() throws Exception {
        run();

        assertTrue(report().at("/audio/samples").asLong() > 0);
        assertTrue(report().at("/audio/wav").isNull());
        assertFalse(Files.exists(out.resolve("audio.wav")));
    }

    @Test
    void aWavFileIsWrittenWhenItIsAskedFor() throws Exception {
        run("--audio");

        assertTrue(Files.exists(out.resolve("audio.wav")));
        assertEquals(out.resolve("audio.wav").toString(), report().at("/audio/wav").asText());
    }

    @Test
    void everyDumpIsTheSizeTheHardwareIs() throws Exception {
        run("--dump", "all");

        var sizes = new java.util.HashMap<String, Integer>();
        report().get("dumps").forEach(dump ->
                sizes.put(dump.get("what").asText(), dump.get("bytes").asInt()));

        assertEquals(2048, sizes.get("ram"));
        assertEquals(256, sizes.get("oam"));
        assertEquals(32, sizes.get("palette"));
        assertEquals(4096, sizes.get("nametables"));
        assertEquals(8192, sizes.get("chr"));

        assertEquals(2048, Files.size(out.resolve("ram.bin")));
    }

    @Test
    void anExpectationThatFailsChangesTheExitCode() throws Exception {
        assertEquals(Headless.EXIT_EXPECTATION, run("--expect-motion", "5000"));

        var expectation = report().get("expectations").get(0);

        assertEquals("motion", expectation.get("name").asText());
        assertFalse(expectation.get("passed").asBoolean());
        assertTrue(expectation.get("detail").asText().contains("5000"));
    }

    @Test
    void anExpectationThatHoldsDoesNot() throws Exception {
        assertEquals(Headless.EXIT_OK, run("--expect-motion", "1"));
        assertTrue(report().get("expectations").get(0).get("passed").asBoolean());
    }

    /**
     * The bytes a patch writes are the bytes the machine runs.
     * <p>
     * The record lands on the front of nestest's CHR-ROM, which the PPU sees at $0000 and
     * {@code --dump chr} hands straight back -- so this follows a patch all the way through the
     * emulator rather than stopping at the report's word for it.
     */
    @Test
    void aPatchReachesTheMachineThatRuns() throws Exception {
        var patch = new Patch().record(CHR_AT, 0xAB, 0xCD).write("one.ips");

        assertEquals(Headless.EXIT_OK, run("--patch", patch.toString(), "--dump", "chr"));

        var chr = Files.readAllBytes(out.resolve("chr.bin"));

        assertEquals((byte) 0xAB, chr[0]);
        assertEquals((byte) 0xCD, chr[1]);
    }

    @Test
    void patchesAreAppliedInTheOrderTheyWereNamed() throws Exception {
        var first = new Patch().record(CHR_AT, 0x11).write("first.ips");
        var second = new Patch().record(CHR_AT, 0x22).write("second.ips");

        run("--patch", first.toString(), "--patch", second.toString(), "--dump", "chr");
        assertEquals((byte) 0x22, Files.readAllBytes(out.resolve("chr.bin"))[0]);

        run("--patch", second.toString(), "--patch", first.toString(), "--dump", "chr");
        assertEquals((byte) 0x11, Files.readAllBytes(out.resolve("chr.bin"))[0]);
    }

    /**
     * The point of patching at load: the ROM somebody owns is still the ROM they own afterwards.
     */
    @Test
    void theRomOnDiskIsLeftAlone() throws Exception {
        var before = Files.readAllBytes(Path.of(ROM));

        run();
        var unpatched = report().at("/cart/sha256").asText();

        run("--patch", new Patch().record(CHR_AT, 0xAB).write("one.ips").toString());

        assertArrayEquals(before, Files.readAllBytes(Path.of(ROM)));
        assertNotEquals(unpatched, report().at("/cart/sha256").asText(),
                "the digest is of the image that ran, which is the patched one");
    }

    @Test
    void theReportNamesEveryPatchAndWhatItHeld() throws Exception {
        var patch = new Patch().record(CHR_AT, 0xAB, 0xCD).run(CHR_AT + 16, 32, 0xFF)
                .write("one.ips");

        run("--patch", patch.toString());

        var patches = report().at("/cart/patches");

        assertEquals(1, patches.size());
        assertEquals(patch.toString(), patches.get(0).get("path").asText());
        assertEquals(2, patches.get(0).get("records").asInt());
        assertEquals(34, patches.get(0).get("bytes").asInt());
    }

    @Test
    void aRunWithoutPatchesSaysSoRatherThanLeavingTheKeyOut() throws Exception {
        run();

        assertTrue(report().at("/cart/patches").isArray());
        assertEquals(0, report().at("/cart/patches").size());
    }

    /**
     * Patching happens before the cartridge is read, which is the whole reason it happens where it
     * does: this one rewrites the iNES header into a 32KB single-bank cartridge with no CHR-ROM at
     * all, and the emulator builds that cartridge rather than nestest.
     */
    @Test
    void aPatchThatRewritesTheHeaderChangesTheCartridge() throws Exception {
        var patch = new Patch()
                .record(4, 0x02)
                .record(5, 0x00)
                .run(PRG_AT + 0x4000, 0x4000, 0xEA)
                .write("bigger.ips");

        assertEquals(Headless.EXIT_OK, run("--patch", patch.toString()));

        assertEquals(32768, report().at("/cart/prgROMBytes").asInt());
        assertEquals(0, report().at("/cart/chrROMBytes").asInt());
    }

    @Test
    void aFileThatIsNotAPatchExitsFive() {
        assertEquals(Headless.EXIT_ROM, run("--patch", "README.md"));
    }

    @Test
    void aPatchThatIsNotThereExitsFiveToo() {
        assertEquals(Headless.EXIT_ROM, run("--patch", out.resolve("nowhere.ips").toString()));
    }

    @Test
    void aFileThatIsNotARomExitsFive() {
        assertEquals(Headless.EXIT_ROM, Headless.run(new String[]{"--rom", "README.md"}));
    }

    @Test
    void aFileThatIsNotThereExitsFiveToo() {
        assertEquals(Headless.EXIT_ROM, Headless.run(new String[]{"--rom", "nowhere.nes"}));
    }

    @Test
    void aCommandLineThatCannotBeReadExitsTwo() {
        assertEquals(Headless.EXIT_USAGE, Headless.run(new String[]{"--frobnicate"}));
    }

    @Test
    void helpExitsZeroWithoutRunningAnything() {
        assertEquals(Headless.EXIT_OK, Headless.run(new String[]{"--help"}));
    }

    @Test
    void aTimeoutStillWritesAReport() throws Exception {
        assertEquals(Headless.EXIT_TIMEOUT, run("--frames", "100000000", "--timeout", "1"));

        assertEquals("timeout", report().at("/run/stoppedBecause").asText());
        assertFalse(report().at("/run/completed").asBoolean());
        assertTrue(report().at("/run/frames").asLong() > 0);
        assertTrue(report().at("/run/frames").asLong() < 100000000);
    }

    /**
     * The test the whole design rests on.
     * <p>
     * Nothing in the machine reads a clock or a random number, so the same command has to produce
     * the same answer -- which is what makes a frame hash worth writing down, one run worth
     * comparing with another, and restarting from power on cheap enough that there is no need for a
     * session to keep alive. Everything that legitimately differs between two runs is under
     * {@code host}, and dropping it is the whole of the comparison.
     */
    @Test
    void theSameRomRunTwiceProducesTheSameReportAndTheSamePicture(@TempDir final Path other)
            throws Exception {
        var first = out.resolve("report.json");
        var second = other.resolve("report.json");

        run("--screenshot", "60", "--input", "20:start", "--audio");
        Headless.run(new String[]{
                "--rom", ROM, "--out", out.toString(), "--quiet", "--frames", "60",
                "--screenshot", "60", "--input", "20:start", "--audio",
                "--report", second.toString()});

        var a = (com.fasterxml.jackson.databind.node.ObjectNode) reportAt(first);
        var b = (com.fasterxml.jackson.databind.node.ObjectNode) reportAt(second);

        a.remove("host");
        b.remove("host");

        assertEquals(a, b);

        // And the picture itself, byte for byte, which is what a golden image would rest on.
        var image = out.resolve("frame-000060.png");
        var bytes = Files.readAllBytes(image);

        Files.delete(image);
        run("--screenshot", "60", "--input", "20:start", "--audio");

        org.junit.jupiter.api.Assertions.assertArrayEquals(bytes, Files.readAllBytes(image));
    }

    /**
     * The counterpart to the test above: pressing something different has to produce something
     * different, or the comparison above would hold for the wrong reason.
     */
    @Test
    void adifferentScheduleProducesADifferentPicture() throws Exception {
        run("--frames", "200", "--input", "20:start");
        var pressed = report().at("/video/finalFrame/hash").asText();

        run("--frames", "200");
        var untouched = report().at("/video/finalFrame/hash").asText();

        assertNotEquals(untouched, pressed);
    }

    /**
     * What a save state is for, from outside the emulator: a run that starts from one draws the
     * picture the run it was taken from was looking at.
     * <p>
     * {@code --frames 0} does no work at all -- the loop runs no iterations, and {@code --screenshot
     * last} still photographs whatever frame the machine is on -- so this compares the two PNGs byte
     * for byte with nothing in between to muddy it.
     */
    @Test
    void aRunThatStartsFromAStateDrawsWhatTheStateWasSavedOn() throws Exception {
        var state = out.resolve("at-60.mn");

        assertEquals(Headless.EXIT_OK, run("--save-state", state.toString(), "--screenshot", "60"));

        var taken = Files.readAllBytes(out.resolve("frame-000060.png"));
        var frameSaved = report().at("/ppu/frame").asLong();

        assertTrue(Files.size(state) > 0, "there is a state file to load");

        assertEquals(Headless.EXIT_OK, run(
                "--frames", "0", "--load-state", state.toString(), "--screenshot", "last"));

        assertEquals(frameSaved, report().at("/ppu/frame").asLong(),
                "the machine came back on the frame the state was taken on");
        assertArrayEquals(
                taken,
                Files.readAllBytes(out.resolve("frame-%06d.png".formatted(frameSaved))),
                "and it came back looking the same");
    }

    /**
     * The region reaches the machine, and the machine really is a different one. nestest is an
     * NTSC cartridge -- like everything vendored here -- so this is the override doing the work.
     */
    @Test
    void aPalRunIsADifferentMachineFromTopToBottom() throws Exception {
        run("--frames", "120");

        var ntscCycles = report().at("/run/cpuCycles").asLong();

        assertEquals("ntsc", report().at("/run/region").asText());
        assertFalse(report().at("/run/regionForced").asBoolean());
        assertEquals("nesdev", report().at("/video/palette").asText());

        run("--frames", "120", "--region", "pal");

        assertEquals("pal", report().at("/run/region").asText());
        assertTrue(report().at("/run/regionForced").asBoolean());
        assertEquals("2c07", report().at("/video/palette").asText(),
                "a PAL machine draws with the PAL table unless somebody says otherwise");

        // 120 frames of 33247.5 cycles rather than of 29780.5: a PAL frame is longer, so the same
        // number of them is more work.
        assertEquals(120 * 33247.5, report().at("/run/cpuCycles").asLong(), 2);
        assertTrue(report().at("/run/cpuCycles").asLong() > ntscCycles);
    }

    /**
     * The flag reaches the PPU, and the report says so either way -- which is the point of it,
     * since a run with the hack on and a run without it are not two measurements of the same
     * machine even when the picture happens to come out the same.
     */
    @Test
    void theReportSaysWhichHacksWereOn() throws Exception {
        run();

        assertFalse(report().at("/run/hacks/unlimitedSprites").asBoolean(),
                "off unless somebody asks");

        run("--hack", "unlimited-sprites");

        assertTrue(report().at("/run/hacks/unlimitedSprites").asBoolean());
    }

    /**
     * nestest never puts nine sprites on a scanline, so switching the hack on has nothing to do --
     * which makes it the right cartridge for showing that the flag on its own changes no pixels.
     */
    @Test
    void aHackWithNothingToDoLeavesThePictureExactlyAsItWas() throws Exception {
        run();

        var withoutIt = report().at("/video/finalFrame/hash").asText();

        run("--hack", "unlimited-sprites");

        assertEquals(withoutIt, report().at("/video/finalFrame/hash").asText());
    }

    @Test
    void aHackNobodyHasWrittenIsACommandLineError() {
        assertEquals(2, run("--hack", "infinite-lives"));
    }

    /**
     * The report reads the hacks back off the machine rather than off the command line, so a
     * session that switched one on half way through is described as it ended.
     */
    @Test
    void aHackSwitchedOnInTheReplIsInTheReport() throws Exception {
        var script = Files.writeString(
                out.resolve("session.txt"), "run 5\nhack unlimited-sprites on\nquit\n");

        run("--script", script.toString());

        assertTrue(report().at("/run/hacks/unlimitedSprites").asBoolean(),
                "nobody put it on the command line, and it is on all the same");
    }

    @Test
    void theReportSaysWhatTheCartridgeAskedFor() throws Exception {
        run();

        // Every ROM vendored here has a header of zeros, which says nothing at all -- which is
        // exactly what nearly every real dump says too.
        assertEquals("unstated", report().at("/cart/timing").asText());
        assertEquals("ntsc", report().at("/cart/region").asText());
    }

    @Test
    void theReportSaysWhereTheRunStartedFrom() throws Exception {
        run();

        assertTrue(report().at("/run/state/startedFromPowerOn").asBoolean());
        assertTrue(report().at("/run/state/loadedFrom").isNull());

        var state = out.resolve("bookmark.mn");
        run("--save-state", state.toString());

        assertEquals(state.toString(), report().at("/run/state/savedTo").asText());

        run("--frames", "1", "--load-state", state.toString());

        assertFalse(report().at("/run/state/startedFromPowerOn").asBoolean(),
                "a run from a state is not comparable with one from power on, and says so");
        assertEquals(state.toString(), report().at("/run/state/loadedFrom").asText());
    }

    /**
     * The fourth thing that decides whether two runs are comparable, after the region, the hacks and
     * the codes. A run that went back and played the same frames again visited them with a machine
     * the frame counter no longer describes, so its sound and its frame changes are not a straight
     * run's -- and nothing else in the document would say so.
     */
    @Test
    void theReportSaysHowMuchOfTheRunWasPlayedTwice() throws Exception {
        run();

        assertEquals(0, report().at("/run/state/framesRewound").asLong(),
                "present and zero on a run nobody rewound, so two reports compare key for key");

        var script = Files.writeString(
                out.resolve("session.txt"), "rewind on\nrun 30\nrewind 10\nquit\n");

        run("--script", script.toString());

        assertEquals(10, report().at("/run/state/framesRewound").asLong());
        assertEquals(20, report().at("/ppu/frame").asLong(), "thirty run, ten given back");
    }

    @Test
    void aStateFromAnotherCartridgeStopsTheRun() {
        var state = out.resolve("nestest.mn");
        run("--save-state", state.toString());

        var args = new String[]{
                "--rom", "src/test/resources/mmc3-test-2/1-clocking.nes",
                "--out", out.toString(), "--quiet", "--frames", "1",
                "--load-state", state.toString()};

        assertEquals(Headless.EXIT_USAGE, Headless.run(args),
                "the wrong cartridge is a mistake on the command line, not a crash");
    }

    /**
     * The battery file is the interoperable one, so what matters is that it is exactly the bytes and
     * nothing else -- no header, no length, no version.
     */
    @Test
    void theBatteryRamMakesTheRoundTripThroughTheCommandLine() throws Exception {
        var sram = out.resolve("nestest.sav");

        run("--sram-out", sram.toString(), "--dump", "prgram");

        var written = Files.readAllBytes(sram);

        assertEquals(0x2000, written.length, "eight kilobytes, and no header on the front");
        assertArrayEquals(
                Files.readAllBytes(out.resolve("prgram.bin")),
                written,
                "the same bytes --dump prgram reports");

        // Hand it back and it has to arrive intact, which is the whole of the interoperability claim.
        Files.write(sram, patterned(written.length));
        run("--frames", "0", "--sram-in", sram.toString(), "--dump", "prgram");

        assertArrayEquals(
                patterned(written.length),
                Files.readAllBytes(out.resolve("prgram.bin")),
                "what went in came back out");
        assertEquals(0x2000, report().at("/cart/sram/bytes").asInt());
    }

    @Test
    void aShorterBatteryFileFillsWhatItCanAndALongerOneIsCut() throws Exception {
        var sram = out.resolve("short.sav");
        Files.write(sram, patterned(64));

        assertEquals(Headless.EXIT_OK, run("--frames", "0", "--sram-in", sram.toString(),
                "--dump", "prgram"), "a file another emulator wrote is worked with, not refused");

        var ram = Files.readAllBytes(out.resolve("prgram.bin"));

        assertEquals(0x2000, ram.length);
        assertArrayEquals(patterned(64), Arrays.copyOf(ram, 64));

        var long_ = out.resolve("long.sav");
        Files.write(long_, patterned(0x8000));

        assertEquals(Headless.EXIT_OK,
                run("--frames", "0", "--sram-in", long_.toString(), "--dump", "prgram"));
        assertArrayEquals(
                patterned(0x2000),
                Files.readAllBytes(out.resolve("prgram.bin")),
                "the first bank of a bigger board's file");
    }

    // ==================================================================================== movies

    /**
     * A run with no frame count of its own, which is what {@code --play} wants: the whole point is
     * that the movie's own length is the answer unless somebody overrules it.
     */
    private int play(final String... extra) {
        var args = new String[extra.length + 5];

        args[0] = "--rom";
        args[1] = ROM;
        args[2] = "--out";
        args[3] = out.toString();
        args[4] = "--quiet";

        System.arraycopy(extra, 0, args, 5, extra.length);

        return Headless.run(args);
    }

    /**
     * The whole claim, through the command line: a session played back arrives at the same machine.
     * <p>
     * Compared as save state bytes rather than as pictures, because a picture stops being evidence
     * as soon as a ROM settles down -- and because two files being byte-equal is an assertion
     * anybody can re-run with {@code cmp}.
     */
    @Test
    void aRecordedSessionReplaysToTheSameMachine() throws Exception {
        var take = out.resolve("take.mnm");
        var recorded = out.resolve("a.mn");
        var replayed = out.resolve("b.mn");

        assertEquals(Headless.EXIT_OK, run(
                "--frames", "200", "--input", "60/40x3:start",
                "--record", take.toString(), "--save-state", recorded.toString()));

        assertTrue(Files.size(take) > 0, "there is a movie to play");
        assertEquals(200, report().at("/run/record/frames").asLong());

        assertEquals(Headless.EXIT_OK, play(
                "--play", take.toString(), "--save-state", replayed.toString()));

        assertEquals(200, report().at("/run/frames").asLong());
        assertArrayEquals(
                Files.readAllBytes(recorded), Files.readAllBytes(replayed),
                "every field of the machine, not only the picture");
    }

    /**
     * The headline: a rewind while recording drops the frames that were taken back, so the movie is
     * the timeline that was finally played and the replay never re-enacts the revert.
     * <p>
     * Ninety frames run, thirty given back, thirty played again with Start held -- so the session
     * ends on frame 90 and the movie holds 90 rather than 120.
     */
    @Test
    void aRecordedSessionWithARewindReplaysStraightThrough() throws Exception {
        var take = out.resolve("rewound.mnm");
        var recorded = out.resolve("a.mn");
        var replayed = out.resolve("b.mn");

        var script = Files.writeString(out.resolve("session.txt"), String.join("\n",
                "record start",
                "rewind on",
                "run 90",
                "rewind 30",
                "hold start",
                "run 30",
                "quit") + "\n");

        assertEquals(Headless.EXIT_OK, run(
                "--script", script.toString(),
                "--record", take.toString(),
                "--save-state", recorded.toString()));

        assertEquals(90, report().at("/run/record/frames").asLong(),
                "ninety, not a hundred and twenty: the thirty that were undone are not in it");
        assertEquals(30, report().at("/run/state/framesRewound").asLong());

        assertEquals(Headless.EXIT_OK, play(
                "--play", take.toString(), "--save-state", replayed.toString()));

        assertEquals(90, report().at("/run/replay/frames").asLong());
        assertEquals(0, report().at("/run/state/framesRewound").asLong(),
                "the replay never goes backwards at all");
        assertArrayEquals(Files.readAllBytes(recorded), Files.readAllBytes(replayed));
    }

    @Test
    void aReplayReproducesAMidRunReset() throws Exception {
        var take = out.resolve("reset.mnm");
        var recorded = out.resolve("a.mn");
        var untouched = out.resolve("c.mn");
        var replayed = out.resolve("b.mn");

        assertEquals(Headless.EXIT_OK, run(
                "--frames", "120", "--reset-at", "60",
                "--record", take.toString(), "--save-state", recorded.toString()));

        // nestest is sitting on a menu either way, so the picture is no evidence at all here and
        // the state bytes are. Without this the test would pass on a replay that ignored resets.
        assertEquals(Headless.EXIT_OK, run("--frames", "120", "--save-state", untouched.toString()));
        assertFalse(
                Arrays.equals(Files.readAllBytes(recorded), Files.readAllBytes(untouched)),
                "the reset has to have changed something, or this proves nothing");

        assertEquals(Headless.EXIT_OK, play(
                "--play", take.toString(), "--save-state", replayed.toString()));

        assertArrayEquals(Files.readAllBytes(recorded), Files.readAllBytes(replayed));
    }

    /**
     * A run that did not start at power on has nowhere for a movie to begin but a state, so the
     * movie carries one -- and the replay of it is honest about not being a power-on run.
     */
    @Test
    void anAnchoredRecordingEmbedsItsStart() throws Exception {
        var bookmark = out.resolve("at-60.mn");
        run("--frames", "60", "--save-state", bookmark.toString());

        var take = out.resolve("anchored.mnm");
        var recorded = out.resolve("a.mn");
        var replayed = out.resolve("b.mn");

        assertEquals(Headless.EXIT_OK, run(
                "--frames", "40",
                "--load-state", bookmark.toString(),
                "--record", take.toString(),
                "--save-state", recorded.toString()));

        assertTrue(report().at("/run/record/anchored").asBoolean());
        assertEquals(60, report().at("/run/record/anchorFrame").asLong());
        assertEquals(40, report().at("/run/record/frames").asLong());

        assertEquals(Headless.EXIT_OK, play(
                "--play", take.toString(), "--save-state", replayed.toString()));

        assertFalse(report().at("/run/state/startedFromPowerOn").asBoolean(),
                "a replay of an anchored take is no more a power-on run than a --load-state is");
        assertEquals(100, report().at("/ppu/frame").asLong(), "sixty anchored plus forty played");
        assertArrayEquals(Files.readAllBytes(recorded), Files.readAllBytes(replayed));
    }

    @Test
    void playDefaultsToTheMovieLength() throws Exception {
        var take = out.resolve("take.mnm");

        run("--frames", "137", "--record", take.toString());

        assertEquals(Headless.EXIT_OK, play("--play", take.toString()));

        assertEquals(137, report().at("/run/frames").asLong(),
                "nobody named a length, so the movie's own is the answer");
    }

    /**
     * Running longer than the movie is a legitimate thing to want -- what does the game do when the
     * player stops playing? -- and the honest answer for a frame nobody recorded is that nobody was
     * touching the pad, which {@code framesWithInput} has to say rather than counting the whole run.
     */
    @Test
    void runningPastTheEndContinuesWithNoInput() throws Exception {
        var take = out.resolve("take.mnm");

        run("--frames", "100", "--input", "0-100:start", "--record", take.toString());

        assertEquals(Headless.EXIT_OK, play("--play", take.toString()));

        var withinTheMovie = report().at("/input/framesWithInput").asLong();

        assertTrue(withinTheMovie > 0, "the recorded session pressed something");

        assertEquals(Headless.EXIT_OK, play("--play", take.toString(), "--frames", "160"));

        assertEquals(160, report().at("/run/frames").asLong());
        assertEquals(withinTheMovie, report().at("/input/framesWithInput").asLong(),
                "the sixty frames past the end had nothing held");
    }

    @Test
    void aMovieFromAnotherCartridgeExitsTwo() throws Exception {
        var take = out.resolve("take.mnm");
        run("--frames", "20", "--record", take.toString());

        assertEquals(Headless.EXIT_USAGE, Headless.run(new String[]{
                "--rom", "src/test/resources/mmc3-test-2/1-clocking.nes",
                "--out", out.toString(), "--quiet",
                "--play", take.toString()}));
    }

    @Test
    void aFileThatIsNotAMovieExitsTwo() throws Exception {
        var nonsense = Files.writeString(out.resolve("nonsense.mnm"), "this is not a movie");

        assertEquals(Headless.EXIT_USAGE, play("--play", nonsense.toString()));
    }

    /**
     * Always present, with explicit nulls, so two reports line up key for key whether either run
     * touched a movie at all.
     */
    @Test
    void theReportSaysWhatWasRecordedAndWhatWasReplayed() throws Exception {
        run();

        assertTrue(report().at("/run/record/savedTo").isNull());
        assertTrue(report().at("/run/record/frames").isNull());
        assertTrue(report().at("/run/replay/playedFrom").isNull());
        assertTrue(report().at("/run/replay/anchorFrame").isNull());

        var take = out.resolve("take.mnm");
        run("--record", take.toString());

        assertEquals(take.toString(), report().at("/run/record/savedTo").asText());
        assertEquals(60, report().at("/run/record/frames").asLong());
        assertFalse(report().at("/run/record/anchored").asBoolean());
        assertTrue(report().at("/run/replay/playedFrom").isNull(), "it played nothing");

        play("--play", take.toString());

        assertEquals(take.toString(), report().at("/run/replay/playedFrom").asText());
        assertEquals(60, report().at("/run/replay/frames").asLong());
        assertTrue(report().at("/run/record/savedTo").isNull(), "and it recorded nothing");
    }

    /**
     * The REPL writes its own file, so the report has to name that one rather than the flag that was
     * never given.
     */
    @Test
    void aMovieStoppedInTheReplIsNamedByTheReport() throws Exception {
        var take = out.resolve("from-the-repl.mnm");
        var script = Files.writeString(out.resolve("session.txt"),
                "record start\nrun 45\nrecord stop " + take + "\nquit\n");

        run("--script", script.toString());

        assertEquals(take.toString(), report().at("/run/record/savedTo").asText());
        assertEquals(45, report().at("/run/record/frames").asLong());
    }

    private static byte[] patterned(final int length) {
        var bytes = new byte[length];

        for (var i = 0; i < length; i++) {
            bytes[i] = (byte) (i * 7 + 1);
        }

        return bytes;
    }
}
