package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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

    @TempDir
    private Path out;

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
    void lastIsWhicheverFrameTheRunEndedOn() throws Exception {
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
}
