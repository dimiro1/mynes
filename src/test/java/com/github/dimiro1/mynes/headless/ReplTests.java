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

            new Repl(session, options, in, out).run();
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

    private static List<String> buttons(final JsonNode reply) {
        var names = new ArrayList<String>();
        reply.get("buttons").forEach(name -> names.add(name.asText()));

        return names;
    }
}
