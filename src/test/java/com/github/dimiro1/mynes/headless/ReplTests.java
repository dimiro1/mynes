package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.ui.palette.Palettes;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine driven a command at a time.
 * <p>
 * A whole session goes in as a string and comes back as one JSON document per line, which is also
 * exactly how it is used: a caller pipes in what it wants done and reads the answers back in order.
 */
class ReplTests {
    private static final String ROM = "src/test/resources/nestest/nestest.nes";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    private Path directory;

    /**
     * Runs a session and hands back one parsed document per reply.
     */
    private List<JsonNode> session(final String... commands) throws IOException {
        var cart = Cart.load(Files.readAllBytes(Path.of(ROM)), ROM);
        var session = new Session(new NES(cart), Palettes.defaultPalette().colours(), null);
        var options = Options.parse(new String[]{"--rom", ROM, "--interactive"});
        var captured = new ByteArrayOutputStream();

        try (var out = new PrintStream(captured, true, StandardCharsets.UTF_8);
             var in = new BufferedReader(new StringReader(String.join("\n", commands) + "\n"))) {

            // JSON, so the one-document-per-line assertions below hold whatever a real terminal
            // would have resolved --format to.
            new Repl(session, options, in, out, false).run();
        }

        var replies = new ArrayList<JsonNode>();

        for (var line : captured.toString(StandardCharsets.UTF_8).lines().toList()) {
            replies.add(MAPPER.readTree(line));
        }

        return replies;
    }

    @Test
    void everyCommandGetsOneLineOfJsonBack() throws Exception {
        var replies = session("run 10", "run 10", "quit");

        assertEquals(3, replies.size());
        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean()));
    }

    @Test
    void everyReplyCarriesTheFrameAndTheHash() throws Exception {
        var replies = session("run 10", "quit");

        for (var reply : replies) {
            assertEquals(10, reply.get("frame").asLong());
            assertEquals(16, reply.get("hash").asText().length());
        }
    }

    @Test
    void runAdvancesTheFramesItIsGiven() throws Exception {
        var replies = session("run", "run 30", "quit");

        assertEquals(1, replies.get(0).get("frame").asLong());
        assertEquals(31, replies.get(1).get("frame").asLong());
    }

    /**
     * The command that answers "which frame does the title screen land on?", which used to be a
     * throwaway program of its own.
     */
    @Test
    void runUntilChangeStopsOnTheFrameThatDiffers() throws Exception {
        var reply = session("run-until-change 300", "quit").getFirst();

        assertTrue(reply.get("changed").asBoolean());
        assertTrue(reply.get("framesRun").asLong() > 0);
        assertEquals(reply.get("framesRun").asLong(), reply.get("frame").asLong());
    }

    @Test
    void runUntilChangeGivesUpAfterTheFramesItWasAllowed() throws Exception {
        // Nothing has been drawn yet at frame 0 and nothing will be within two frames, so this
        // must come back having run its budget and saying it found nothing.
        var reply = session("run-until-change 2", "quit").getFirst();

        assertFalse(reply.get("changed").asBoolean());
        assertEquals(2, reply.get("framesRun").asLong());
    }

    @Test
    void aPressChangesWhatTheGameSees() throws Exception {
        var replies = session("run 60", "press start", "run 30", "quit");
        var untouched = session("run 60", "run 30", "quit");

        assertEquals(List.of("start"), buttons(replies.get(1)));
        assertNotEquals(
                untouched.get(1).get("hash").asText(),
                replies.get(2).get("hash").asText());
    }

    @Test
    void aHoldLastsUntilItIsReleased() throws Exception {
        var replies = session("hold right", "run 5", "release", "quit");

        assertEquals(List.of("right"), buttons(replies.getFirst()));
        assertEquals(List.of(), buttons(replies.get(2)));
    }

    @Test
    void readComesBackAsHex() throws Exception {
        var reply = session("run 30", "read 0x0000 4", "quit").get(1);

        assertEquals(0, reply.get("address").asInt());
        assertEquals(4, reply.get("count").asInt());
        assertEquals(8, reply.get("bytes").asText().length());
    }

    @Test
    void anAddressCanBeWrittenThreeWays() throws Exception {
        var replies = session("read 0x10", "read $10", "read 16", "quit");

        assertEquals(16, replies.get(0).get("address").asInt());
        assertEquals(16, replies.get(1).get("address").asInt());
        assertEquals(16, replies.get(2).get("address").asInt());
    }

    @Test
    void stateSaysWhereEverythingIs() throws Exception {
        var reply = session("run 60", "state", "quit").get(1);

        assertTrue(reply.at("/cpu/pc").isNumber());
        assertTrue(reply.at("/ppu/renderingEnabled").isBoolean());
        assertTrue(reply.at("/video/uniqueColours").asInt() > 0);
        assertTrue(reply.at("/audio/samples").asLong() > 0);
    }

    @Test
    void aScreenshotIsWrittenWhereItWasAskedFor() throws Exception {
        var path = directory.resolve("shot.png");
        var reply = session("run 60", "screenshot " + path, "quit").get(1);

        assertEquals(path.toString(), reply.get("path").asText());
        assertTrue(Files.exists(path));
    }

    @Test
    void aDumpIsWrittenWhereItWasAskedFor() throws Exception {
        var path = directory.resolve("ram.bin");
        var reply = session("dump ram " + path, "quit").getFirst();

        assertEquals(2048, reply.get("bytes").asInt());
        assertEquals(2048, Files.size(path));
    }

    /**
     * The reading resets each time, because the question is almost never "does this cartridge make
     * any sound" but "did <em>that</em> make a sound".
     */
    @Test
    void audioReportsWhatHappenedSinceItWasLastAsked() throws Exception {
        var replies = session("run 30", "audio", "run 30", "audio", "quit");

        var first = replies.get(1);
        var second = replies.get(3);

        assertTrue(first.get("samples").asLong() > 0);
        assertTrue(second.get("samples").asLong() > 0);
        assertTrue(
                second.get("totalSamples").asLong() > first.get("totalSamples").asLong(),
                "the running total keeps going even though the reading starts again");
    }

    @Test
    void aBadCommandIsAnsweredAndTheSessionCarriesOn() throws Exception {
        var replies = session("run 10", "frobnicate", "run 10", "quit");

        assertEquals(4, replies.size());
        assertFalse(replies.get(1).get("ok").asBoolean());
        assertTrue(replies.get(1).get("error").asText().contains("frobnicate"));
        assertTrue(replies.get(2).get("ok").asBoolean());
        assertEquals(20, replies.get(2).get("frame").asLong());
    }

    @Test
    void aCommandThatIsMissingItsArgumentIsAnsweredTheSameWay() throws Exception {
        var replies = session("press", "screenshot", "read", "quit");

        for (var i = 0; i < 3; i++) {
            assertFalse(replies.get(i).get("ok").asBoolean());
        }
    }

    @Test
    void blankLinesAndCommentsAreIgnored() throws Exception {
        var replies = session("", "# a note", "run 5", "quit");

        assertEquals(2, replies.size());
    }

    @Test
    void theEndOfTheInputEndsTheSessionJustAsQuitDoes() throws Exception {
        var replies = session("run 5");

        assertEquals(1, replies.size());
        assertEquals(5, replies.getFirst().get("frame").asLong());
    }

    /**
     * The shape a session actually uses this in: bookmark, try one thing, come back, try another.
     * The reply to a load carries the frame it landed on, which is the confirmation worth having.
     */
    @Test
    void aStateGoesBackToWhereItWasTaken() throws Exception {
        var path = directory.resolve("bookmark.mn").toString();

        var replies = session(
                "run 30",
                "save-state " + path,
                "run 90",
                "load-state " + path,
                "quit");

        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean(), reply.toString()));

        assertEquals(30, replies.get(1).get("frame").asLong());
        assertTrue(replies.get(1).get("bytes").asLong() > 0, "the file has something in it");
        assertEquals(120, replies.get(2).get("frame").asLong());
        assertEquals(30, replies.get(3).get("frame").asLong(), "back where it started");
        assertEquals(
                replies.get(1).get("hash").asText(),
                replies.get(3).get("hash").asText(),
                "and looking the way it did");
    }

    @Test
    void aStateThatWillNotLoadIsAnErrorRatherThanTheEndOfTheSession() throws Exception {
        var missing = directory.resolve("nothing-here.mn").toString();

        var replies = session("run 5", "load-state " + missing, "run 5", "quit");

        assertEquals(4, replies.size(), "the session carried on");
        assertFalse(replies.get(1).get("ok").asBoolean());
        assertEquals(10, replies.get(2).get("frame").asLong(),
                "and the failed load cost it nothing: ten frames run, ten frames on");
    }

    @Test
    void loadStateWantsAFile() throws Exception {
        var replies = session("load-state", "quit");

        assertFalse(replies.getFirst().get("ok").asBoolean());
        assertTrue(replies.getFirst().get("error").asText().contains("load-state"));
    }

    @Test
    void theHelpListsTheStateCommands() throws Exception {
        var help = session("help", "quit").getFirst().get("commands").asText();

        assertTrue(help.contains("save-state"));
        assertTrue(help.contains("load-state"));
    }

    @Test
    void theHelpListsTheDebuggingCommands() throws Exception {
        var help = session("help", "quit").getFirst().get("commands").asText();

        for (var command : List.of("step", "disasm", "break", "unbreak", "watch", "points")) {
            assertTrue(help.contains(command), command + " should be listed");
        }
    }

    // ================================================================================= debugging

    /**
     * nestest's reset vector lands on $C004 and the first four instructions are known, which makes
     * them the one place a test can name an address and be sure the machine goes there.
     */
    private static final int SEI = 0xC004;
    private static final int LDX_FF = 0xC006;
    private static final int TXS = 0xC008;

    /**
     * $C015 is {@code STA $2000}, the first write nestest makes to anything.
     */
    private static final int STA_PPUCTRL = 0xC015;

    /**
     * The first step is the reset sequence, which is not an instruction and leaves the CPU standing
     * on the first one rather than past it. Worth pinning: a debugger whose first step silently ran
     * something would be lying about where the machine had got to.
     */
    @Test
    void theFirstStepIsTheResetAndRunsNoInstruction() throws Exception {
        var reply = session("step", "quit").getFirst();

        assertEquals(SEI, reply.at("/pc").asInt(), "the reset vector");
        assertEquals("SEI", reply.at("/next/text").asText(), "which has not run yet");
    }

    @Test
    void stepAdvancesOneInstructionAndSaysWhatIsNext() throws Exception {
        var replies = session("step", "step", "quit");

        assertEquals(1, replies.get(1).at("/instructions").asInt());
        assertEquals(0xC005, replies.get(1).at("/pc").asInt(), "SEI ran");
        assertEquals("CLD", replies.get(1).at("/next/text").asText());
    }

    @Test
    void stepTakesACount() throws Exception {
        var reply = session("step 3", "quit").getFirst();

        assertEquals(3, reply.at("/instructions").asInt());
        assertEquals(LDX_FF, reply.at("/pc").asInt(), "the reset, then SEI and CLD");
        assertEquals("LDX #$FF", reply.at("/next/text").asText());
    }

    @Test
    void disasmStartsAtTheProgramCounterWhenItIsNotToldWhere() throws Exception {
        var reply = session("step", "disasm", "quit").get(1);

        assertEquals(SEI, reply.at("/address").asInt());
        assertEquals("SEI", reply.at("/lines/0/text").asText());
        assertEquals("CLD", reply.at("/lines/1/text").asText());
        assertEquals("LDX #$FF", reply.at("/lines/2/text").asText());
        assertEquals(LDX_FF, reply.at("/lines/2/address").asInt(),
                "each one starts where the last ended");
    }

    @Test
    void disasmTakesAnAddressInAnyOfTheThreeSpellings() throws Exception {
        var replies = session("disasm $C004 1", "disasm 0xC004 1", "disasm 49156 1", "quit");

        for (var i = 0; i < 3; i++) {
            assertEquals("SEI", replies.get(i).at("/lines/0/text").asText());
            assertEquals("78", replies.get(i).at("/lines/0/bytes").asText());
        }
    }

    @Test
    void aBreakpointStopsTheRunAndSaysWhere() throws Exception {
        var reply = session("break $C008", "run 5", "quit").get(1);

        assertEquals("breakpoint", reply.get("stopped").asText());
        assertEquals(TXS, reply.get("stoppedAt").asInt());
        assertEquals(0, reply.get("frames").asLong(), "it never got to the end of the first frame");
    }

    @Test
    void aWatchpointSaysWhatWroteWhatAndWhere() throws Exception {
        var reply = session("watch $2000", "run 5", "quit").get(1);

        assertEquals("watchpoint", reply.get("stopped").asText());
        assertEquals(0x2000, reply.get("address").asInt());
        assertEquals(0x00, reply.get("value").asInt());
        assertEquals(STA_PPUCTRL, reply.get("writtenBy").asInt(), "the STA, not where it stopped");
        assertNotEquals(STA_PPUCTRL, reply.get("stoppedAt").asInt());
    }

    @Test
    void aRunNothingStopsSaysNothingAboutStopping() throws Exception {
        var reply = session("run 5", "quit").getFirst();

        assertFalse(reply.has("stopped"), "absent, so that jq can select on it");
        assertEquals(5, reply.get("frames").asLong());
    }

    @Test
    void aPointCanBePickedUpAgain() throws Exception {
        var replies = session("break $C008", "unbreak $C008", "run 5", "quit");

        assertEquals(List.of(), addresses(replies.get(1), "breakpoints"));
        assertFalse(replies.get(2).has("stopped"));
    }

    @Test
    void thePointsAreListedInAddressOrder() throws Exception {
        var reply = session("break $C010", "break $C008", "watch $2000", "points", "quit").get(3);

        assertEquals(List.of(0xC008, 0xC010), addresses(reply, "breakpoints"));
        assertEquals(List.of(0x2000), addresses(reply, "watchpoints"));
    }

    @Test
    void clearingDropsEveryPoint() throws Exception {
        var replies = session("break $C008", "watch $2000", "points clear", "run 5", "quit");

        assertEquals(List.of(), addresses(replies.get(2), "breakpoints"));
        assertEquals(List.of(), addresses(replies.get(2), "watchpoints"));
        assertFalse(replies.get(3).has("stopped"), "and the machine runs freely again");
    }

    @Test
    void aPointWithoutAnAddressIsAnsweredRatherThanFatal() throws Exception {
        var replies = session("break", "watch", "points wibble", "run 5", "quit");

        for (var i = 0; i < 3; i++) {
            assertFalse(replies.get(i).get("ok").asBoolean());
        }

        assertTrue(replies.get(3).get("ok").asBoolean(), "the session carries on");
    }

    /**
     * A breakpoint that stops a run part way through a frame must not cost the press a frame of its
     * own, or a button would be let go of before the game had a whole frame to see it held.
     */
    @Test
    void aBreakpointDoesNotEatAPress() throws Exception {
        // Stopped part way through the first frame, so the press has not had its frame yet.
        var stopped = session("press start 1", "break $C008", "run 1", "state", "quit").get(3);

        assertEquals(List.of("start"), buttons(stopped));

        // Left alone the frame finishes, the press is spent on it, and the next one lets go.
        var finished = session("press start 1", "run 1", "run 1", "state", "quit").get(3);

        assertEquals(List.of(), buttons(finished));
    }

    /**
     * In text mode a reply is readable lines for a person, not one JSON document for a machine: no
     * surrounding braces, a bare {@code key: value}, and the frame on a line of its own.
     */
    @Test
    void textModeAnswersInReadableLinesRatherThanJson() throws Exception {
        var cart = Cart.load(Files.readAllBytes(Path.of(ROM)), ROM);
        var session = new Session(new NES(cart), Palettes.defaultPalette().colours(), null);
        var options = Options.parse(new String[]{"--rom", ROM, "--interactive"});
        var captured = new ByteArrayOutputStream();

        try (var out = new PrintStream(captured, true, StandardCharsets.UTF_8);
             var in = new BufferedReader(new StringReader("run 10\nquit\n"))) {

            new Repl(session, options, in, out, true).run();
        }

        var text = captured.toString(StandardCharsets.UTF_8);

        assertTrue(text.lines().anyMatch(line -> line.matches("frame\\s*: 10")),
                "the frame is a readable line of its own");
        assertTrue(text.contains("\n"), "spread over lines, not squeezed onto one");
        assertFalse(text.contains("{\"ok\""), "and not a compact JSON object");
        assertFalse(text.contains("\\n"), "nor an escaped newline");
    }

    private static List<Integer> addresses(final JsonNode reply, final String field) {
        var out = new ArrayList<Integer>();
        reply.get(field).forEach(address -> out.add(address.asInt()));

        return out;
    }

    private static List<String> buttons(final JsonNode reply) {
        var names = new ArrayList<String>();
        reply.get("buttons").forEach(name -> names.add(name.asText()));

        return names;
    }
}
