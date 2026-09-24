package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Where in the frame the machine was touched, asked for from a command line and from a session.
 * <p>
 * The core's {@code EventTracerTests} holds the file's shape and the beam position in it; what only
 * exists once everything is joined up is the rest: that a flag opens one and the report names it,
 * that the REPL's three forms start, stop and count the same log, and -- the claim the whole design
 * rests on -- that a run being logged is byte for byte the run that was not. A gauge that changed
 * the timing would be measuring itself.
 */
class EventLogRunTests {
    private static final String ROM = "src/test/resources/nestest/nestest.nes";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path out;

    private int run(final String... extra) {
        var args = new ArrayList<String>(
                List.of("--rom", ROM, "--out", out.toString(), "--quiet", "--frames", "60"));

        args.addAll(List.of(extra));

        return Headless.run(args.toArray(new String[0]));
    }

    private JsonNode report() throws IOException {
        return MAPPER.readTree(Files.readString(out.resolve("report.json")));
    }

    /**
     * The file's records, header dropped, cut into their fields.
     */
    private List<String[]> records(final Path path) throws IOException {
        return Files.readAllLines(path).stream()
                .filter(line -> !line.startsWith("#"))
                .map(line -> line.trim().split("\\s+"))
                .toList();
    }

    /**
     * Runs a REPL session against a fresh machine and hands back one parsed reply per line.
     */
    private List<JsonNode> session(final String... commands) throws IOException {
        var cart = Cart.load(Files.readAllBytes(Path.of(ROM)), ROM);
        var session = new Session(
                new NES(cart),
                Palettes.defaultPalette().colours(),
                VideoFilter.NONE,
                FilterStrength.defaultStrength(),
                false,
                false,
                null);
        var options = Options.parse(new String[]{"--rom", ROM, "--interactive"});
        var captured = new ByteArrayOutputStream();

        try (var stdout = new PrintStream(captured, true, StandardCharsets.UTF_8);
             var in = new BufferedReader(new StringReader(String.join("\n", commands) + "\n"))) {

            new Repl(session, options, in, stdout, false).run();
        }

        var replies = new ArrayList<JsonNode>();

        for (var line : captured.toString(StandardCharsets.UTF_8).lines().toList()) {
            replies.add(MAPPER.readTree(line));
        }

        return replies;
    }

    // ================================================================================ the command line

    @Test
    void theFlagWritesALogAndTheReportNamesIt() throws Exception {
        var path = out.resolve("events.log");

        assertEquals(Headless.EXIT_OK, run("--log-events", path.toString()));

        var records = records(path);

        assertFalse(records.isEmpty(), "nestest writes its registers on every frame");
        assertEquals(records.size(), report().at("/eventLog/records").asLong());
        assertEquals(path.toString(), report().at("/eventLog/path").asText());
        assertFalse(report().at("/eventLog/reads").asBoolean());
        assertFalse(report().at("/eventLog/full").asBoolean());
    }

    /**
     * Always present with explicit nulls, so two reports line up key for key whether either run
     * logged anything.
     */
    @Test
    void theReportSaysSoEvenWhenNothingWasLogged() throws Exception {
        run();

        assertTrue(report().at("/eventLog/path").isNull());
        assertTrue(report().at("/eventLog/reads").isNull());
        assertTrue(report().at("/eventLog/records").isNull());
        assertTrue(report().at("/eventLog/full").isNull());
    }

    /**
     * The reads are the expensive half and the half somebody asks for deliberately, which is why
     * they are a flag of their own and why they are off without it.
     */
    @Test
    void theReadsAreOnlyThereWhenTheyWereAskedFor() throws Exception {
        var quiet = out.resolve("writes.log");
        var loud = out.resolve("both.log");

        run("--log-events", quiet.toString());

        assertTrue(
                records(quiet).stream().noneMatch(fields -> fields[3].endsWith("-read")),
                "reads were not asked for");

        run("--log-events", loud.toString(), "--log-reads");

        assertTrue(
                records(loud).stream().anyMatch(fields -> fields[3].equals("ppu-read")),
                "nestest polls $2002 while it waits for vblank");
        assertTrue(report().at("/eventLog/reads").asBoolean());
    }

    /**
     * A setting on a thing nobody switched on is a thing that quietly does nothing, which is the
     * same refusal {@code --filter none=low} and {@code --warp} without a tube get.
     */
    @Test
    void readsWithNoLogToPutThemInIsRefused() {
        assertEquals(Headless.EXIT_USAGE, run("--log-reads"));
    }

    /**
     * The claim the whole design rests on: the sink does not arm the machine, so the run it watches
     * is the run that would have happened. Checked on the frame hash, which is the finest thing the
     * report holds.
     */
    @Test
    void aLoggedRunIsByteForByteAnUnloggedOne() throws Exception {
        run("--log-events", out.resolve("both.log").toString(), "--log-reads");

        var logged = report().at("/video/finalFrame/hash").asText();
        var loggedCycles = report().at("/run/cpuCycles").asLong();

        run();

        assertEquals(logged, report().at("/video/finalFrame/hash").asText());
        assertEquals(loggedCycles, report().at("/run/cpuCycles").asLong());
    }

    // ====================================================================================== the REPL

    @Test
    void theSessionStartsStopsAndCountsOne() throws Exception {
        var path = out.resolve("repl.log");
        var replies = session(
                "events",
                "events on " + path,
                "run 5",
                "events",
                "events off",
                "events",
                "quit");

        assertFalse(replies.get(0).get("on").asBoolean(), "nothing is being logged yet");

        var running = replies.get(3);

        assertTrue(running.get("on").asBoolean());
        assertEquals(path.toString(), running.get("path").asText());
        assertTrue(running.get("records").asLong() > 0);

        var stopped = replies.get(5);

        assertFalse(stopped.get("on").asBoolean());
        assertEquals(
                running.get("records").asLong(),
                stopped.get("records").asLong(),
                "and it stopped counting when it stopped writing");

        assertEquals(stopped.get("records").asLong(), records(path).size());
    }

    @Test
    void theSessionAsksForTheReadsWithAFormOfItsOwn() throws Exception {
        var path = out.resolve("reads.log");

        session("events reads " + path, "run 5", "events off", "quit");

        assertTrue(
                records(path).stream().anyMatch(fields -> fields[3].equals("ppu-read")),
                "the polls are in it");
    }

    /**
     * A limit that has been reached takes the log off the machine rather than leaving it on doing
     * nothing, which is the same sweep a finished instruction trace gets between commands.
     */
    @Test
    void aSessionLogStopsAtItsLimit() throws Exception {
        var path = out.resolve("short.log");
        var replies = session("events on " + path + " 20", "run 5", "events", "quit");
        var full = replies.get(2);

        assertTrue(full.get("full").asBoolean());
        assertFalse(full.get("on").asBoolean(), "and took itself off");
        assertEquals(20, full.get("records").asLong());
        assertEquals(20, records(path).size());
    }

    @Test
    void aSecondLogIsRefusedRatherThanReplacingTheFirst() throws Exception {
        var replies = session(
                "events on " + out.resolve("first.log"),
                "events on " + out.resolve("second.log"),
                "quit");

        assertTrue(replies.get(0).get("ok").asBoolean());
        assertFalse(replies.get(1).get("ok").asBoolean());
        assertTrue(replies.get(1).get("error").asText().contains("first.log"));
    }

    @Test
    void theFormsThatMakeNoSenseAreRefused() throws Exception {
        var replies = session("events off", "events wibble", "events on", "quit");

        assertFalse(replies.get(0).get("ok").asBoolean(), "nothing is being logged");
        assertFalse(replies.get(1).get("ok").asBoolean(), "\"wibble\" is not one of the forms");
        assertFalse(replies.get(2).get("ok").asBoolean(), "and \"on\" wants somewhere to write it");
    }
}
