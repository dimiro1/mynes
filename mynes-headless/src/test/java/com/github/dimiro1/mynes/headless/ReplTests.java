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
        return magnified(1, commands);
    }

    /**
     * The same, for a session started at a magnification -- which the tube filter has an opinion
     * about, since there is nowhere to put a scanline at 1x.
     */
    private List<JsonNode> magnified(final int scale, final String... commands)
            throws IOException {
        var cart = Cart.load(Files.readAllBytes(Path.of(ROM)), ROM);
        var session = new Session(
                new NES(cart),
                Palettes.defaultPalette().colours(),
                VideoFilter.NONE,
                FilterStrength.defaultStrength(),
                false,
                null);
        var options = Options.parse(new String[]{
                "--rom", ROM, "--interactive", "--scale", Integer.toString(scale)});
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
    void aHackCanBeSwitchedOnAndOffMidSession() throws Exception {
        var replies = session(
                "run 5",
                "hack unlimited-sprites on",
                "hack unlimited-sprites off",
                "quit");

        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean(), reply.toString()));

        assertEquals("unlimited-sprites", replies.get(1).get("hack").asText());
        assertTrue(replies.get(1).get("on").asBoolean());
        assertFalse(replies.get(2).get("on").asBoolean());
    }

    @Test
    void aVoiceCanBeSwitchedOutAndBackMidSession() throws Exception {
        var replies = session(
                "run 5",
                "mute",
                "mute triangle on",
                "mute dmc on",
                "mute triangle off",
                "quit");

        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean(), reply.toString()));

        assertTrue(replies.get(1).get("muted").isEmpty(), "nothing is muted to begin with");

        assertEquals("triangle", replies.get(2).get("channel").asText());
        assertTrue(replies.get(2).get("on").asBoolean(), "so jq .on works here as it does for hack");

        assertEquals(
                List.of("triangle", "dmc"),
                names(replies.get(3).get("muted")),
                "the whole list rides along, in the order the chip mixes them");

        assertEquals(List.of("dmc"), names(replies.get(4).get("muted")));
    }

    @Test
    void aVoiceThatIsMisspeltOrHalfTypedIsAnError() throws Exception {
        var replies = session(
                "mute pulse3 on",
                "mute noise",
                "mute noise maybe",
                "quit");

        for (var i = 0; i < 3; i++) {
            assertFalse(replies.get(i).get("ok").asBoolean(), replies.get(i).toString());
        }

        assertTrue(replies.getFirst().get("error").asText().contains("pulse3"));
        assertTrue(replies.get(2).get("error").asText().contains("maybe"));
    }

    private static List<String> names(final JsonNode array) {
        var names = new ArrayList<String>();

        array.forEach(node -> names.add(node.asText()));

        return names;
    }

    /**
     * Taking the same frame twice and diffing the two pictures is the whole use for this command,
     * so what it has to support is switching without the machine moving underneath it.
     */
    @Test
    void theFilterCanBeSwitchedMidSessionAndReportsItself() throws Exception {
        var replies = session(
                "run 5",
                "filter",
                "filter ntsc",
                "filter none",
                "quit");

        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean(), reply.toString()));

        assertEquals("none", replies.get(1).get("filter").asText());
        assertEquals("ntsc", replies.get(2).get("filter").asText());
        assertEquals("none", replies.get(3).get("filter").asText());
    }

    /**
     * And that the strength survives being switched away from and back, since diffing two pictures
     * of one frame is done by going through the palette in between.
     */
    @Test
    void theStrengthIsTheThirdWordAndOutlastsTheFilterItWasSaidAbout() throws Exception {
        var replies = session(
                "filter",
                "filter ntsc low",
                "filter none",
                "filter ntsc",
                "quit");

        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean(), reply.toString()));

        assertEquals("medium", replies.getFirst().get("strength").asText());
        assertEquals("low", replies.get(1).get("strength").asText());
        assertEquals("low", replies.get(2).get("strength").asText());
        assertEquals("low", replies.get(3).get("strength").asText());
    }

    /**
     * The tube needs somewhere to put a scanline, and a session's magnification was fixed at
     * {@code --scale} before it started -- so this is the one place the answer can be given, and
     * giving it is better than a filter switched on and invisible.
     */
    @Test
    void theTubeIsRefusedInASessionWithNoRoomForAScanline() throws Exception {
        var refused = magnified(1, "filter crt", "filter", "quit");

        assertFalse(refused.getFirst().get("ok").asBoolean());
        assertTrue(refused.getFirst().get("error").asText().contains("--scale"),
                refused.getFirst().toString());
        assertEquals("none", refused.get(1).get("filter").asText(), "and nothing changed");

        var allowed = magnified(2, "filter crt", "quit");

        assertEquals("crt", allowed.getFirst().get("filter").asText());
    }

    /**
     * The glass is switched rather than chosen between, so it is a command of its own -- and it is
     * refused while something other than the tube is drawing, since there is no glass in front of a
     * lookup table.
     */
    @Test
    void theGlassIsBentByItsOwnCommandAndOnlyOverATube() throws Exception {
        var replies = magnified(2,
                "warp",
                "warp on",
                "filter crt",
                "warp on",
                "warp",
                "warp off",
                "quit");

        assertFalse(replies.getFirst().get("warp").asBoolean(), "off unless asked for");

        assertFalse(replies.get(1).get("ok").asBoolean(), "no tube, no glass");
        assertTrue(replies.get(1).get("error").asText().contains("crt"));

        assertTrue(replies.get(3).get("ok").asBoolean());
        assertTrue(replies.get(4).get("warp").asBoolean());
        assertFalse(replies.get(5).get("warp").asBoolean());

        // And the filter command reports it too, since that is the one line that says how the
        // picture is being drawn.
        assertFalse(magnified(2, "filter", "quit").getFirst().get("warp").asBoolean());
    }

    @Test
    void warpTakesOnOrOffAndNothingElse() throws Exception {
        var replies = magnified(2, "filter crt", "warp sideways", "quit");

        assertFalse(replies.get(1).get("ok").asBoolean());
        assertTrue(replies.get(1).get("error").asText().contains("sideways"));
    }

    /**
     * And a strength nobody can spell leaves the filter alone rather than half applying the line.
     */
    @Test
    void aStrengthThatIsMisspeltOrSaidOfThePaletteIsAnError() throws Exception {
        var replies = session(
                "filter ntsc",
                "filter none high",
                "filter ntsc high",
                "filter",
                "quit");

        assertTrue(replies.getFirst().get("ok").asBoolean());
        assertFalse(replies.get(1).get("ok").asBoolean());
        assertFalse(replies.get(2).get("ok").asBoolean());
        assertTrue(replies.get(2).get("error").asText().contains("high"));

        assertEquals("ntsc", replies.get(3).get("filter").asText(), "the refused line changed"
                + " nothing");
    }

    @Test
    void aFilterThatIsMisspeltIsAnError() throws Exception {
        var replies = session("filter composite", "quit");

        assertFalse(replies.getFirst().get("ok").asBoolean());
        assertTrue(replies.getFirst().get("error").asText().contains("composite"));
    }

    @Test
    void aHackThatIsMisspeltOrHalfTypedIsAnError() throws Exception {
        var replies = session(
                "hack",
                "hack unlimited-sprites",
                "hack infinite-lives on",
                "hack unlimited-sprites maybe",
                "quit");

        for (var i = 0; i < 4; i++) {
            assertFalse(replies.get(i).get("ok").asBoolean(), replies.get(i).toString());
        }

        assertTrue(replies.get(2).get("error").asText().contains("infinite-lives"));
        assertTrue(replies.get(3).get("error").asText().contains("maybe"));
    }

    /**
     * The other hack, which takes a number rather than a switch -- so the arity of the command
     * depends on which one was named, and "hack overclock on" is not a thing anybody can mean.
     */
    @Test
    void theOverclockCanBeSetAndClearedMidSession() throws Exception {
        var replies = session(
                "run 5",
                "hack overclock 131",
                "hack overclock 40 20",
                "hack overclock off",
                "quit");

        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean(), reply.toString()));

        assertEquals("overclock", replies.get(1).get("hack").asText());
        assertTrue(replies.get(1).get("on").asBoolean(), "so jq .on works for both hacks");
        assertEquals(131, replies.get(1).get("beforeNmi").asInt());
        assertEquals(0, replies.get(1).get("afterNmi").asInt());

        assertEquals(40, replies.get(2).get("beforeNmi").asInt());
        assertEquals(20, replies.get(2).get("afterNmi").asInt());

        assertFalse(replies.get(3).get("on").asBoolean());
        assertEquals(0, replies.get(3).get("beforeNmi").asInt());
    }

    @Test
    void anOverclockThatIsNotANumberIsAnError() throws Exception {
        var replies = session(
                "hack overclock",
                "hack overclock on",
                "hack overclock lots",
                "hack overclock 2000",
                "quit");

        for (var i = 0; i < 4; i++) {
            assertFalse(replies.get(i).get("ok").asBoolean(), replies.get(i).toString());
        }

        assertTrue(replies.get(1).get("error").asText().contains("scanlines"),
                "\"on\" is somebody who has not been asked how many yet");
        assertTrue(replies.get(2).get("error").asText().contains("lots"));
        assertTrue(replies.get(3).get("error").asText().contains("0 to 1000"));
    }

    /**
     * A movie pins the overclock at the moment it starts, for the reason it pins the codes and a
     * sharper one: this is the one hack a replay's frames actually depend on.
     */
    @Test
    void anOverclockCannotChangeWhileAMovieIsRecording() throws Exception {
        var replies = session(
                "hack overclock 40",
                "record start",
                "hack unlimited-sprites on",
                "hack overclock 90",
                "hack overclock off",
                "quit");

        assertTrue(replies.get(2).get("ok").asBoolean(),
                "the sprite limit changes only pixels, so a movie does not care");

        for (var refused : List.of(replies.get(3), replies.get(4))) {
            assertFalse(refused.get("ok").asBoolean(), refused.toString());
            assertTrue(refused.get("error").asText().contains("pinned"));
        }
    }

    /**
     * Which is what the command is for: run to the frame that matters, put the code in, look, take it
     * out, look again. The cartridge underneath never changed, so the two are of the same moment.
     */
    @Test
    void aCodeCanBePutInAndTakenOutMidSession() throws Exception {
        var replies = session(
                "genie SXIOPO",
                "genie",
                "ungenie SXIOPO",
                "genie",
                "quit");

        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean(), reply.toString()));

        assertEquals(0x91D9, replies.getFirst().get("address").asInt());
        assertEquals(0xAD, replies.getFirst().get("value").asInt());
        assertTrue(replies.getFirst().get("compare").isNull(), "six letters name no bank");

        assertEquals(List.of("SXIOPO"), codes(replies.get(1)));
        assertEquals(List.of(), codes(replies.get(3)));
    }

    @Test
    void clearingTakesTheLotOut() throws Exception {
        var replies = session("genie SXIOPO", "genie ZEXPYGLA", "genie clear", "quit");

        assertEquals(List.of("SXIOPO", "ZEXPYGLA"), codes(replies.get(1)));
        assertEquals(List.of(), codes(replies.get(2)));
    }

    /**
     * One address holds one code, the way the cartridge port does -- and the reply says which one was
     * pushed out, since silently holding two would be the confusing answer.
     */
    @Test
    void aSecondCodeForOneAddressReplacesTheFirst() throws Exception {
        var replies = session("genie SXIOPO", "genie SXIOPO", "quit");

        assertTrue(replies.getFirst().get("replaced").isNull());
        assertEquals("SXIOPO", replies.get(1).get("replaced").asText());
        assertEquals(List.of("SXIOPO"), codes(replies.get(1)));
    }

    @Test
    void aCodeThatIsMisspeltOrHalfTypedIsAnError() throws Exception {
        var replies = session("genie GOSSIB", "genie SXIOP", "ungenie", "ungenie GOSSIP", "quit");

        for (var i = 0; i < 4; i++) {
            assertFalse(replies.get(i).get("ok").asBoolean(), replies.get(i).toString());
        }

        assertTrue(replies.get(3).get("error").asText().contains("GOSSIP"), "and it says which");
    }

    private static List<String> codes(final JsonNode reply) {
        var out = new ArrayList<String>();

        reply.get("codes").forEach(code -> out.add(code.asText()));

        return out;
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
        assertTrue(help.contains("rewind on"));
        assertTrue(help.contains("rewind off"));
        assertTrue(help.contains("record start"));
        assertTrue(help.contains("record stop"));
    }

    // ==================================================================================== movies

    @Test
    void recordReportsItsStatus() throws Exception {
        var replies = session(
                "record", "record start", "run 20", "record", "record stop " + movie(), "quit");

        assertFalse(replies.getFirst().get("on").asBoolean());
        assertFalse(replies.getFirst().has("frames"),
                "nothing to say about a recording that is not happening");

        assertTrue(replies.get(1).get("on").asBoolean());
        assertEquals(0, replies.get(1).get("frames").asLong());
        assertFalse(replies.get(1).get("anchored").asBoolean(),
                "started on frame 0 with no battery filled, so there is nothing to carry");

        assertEquals(20, replies.get(3).get("frames").asLong());
        assertFalse(replies.get(4).get("on").asBoolean(), "and it stopped");
    }

    /**
     * A recording that started after the machine had run has nowhere to begin from but a state, so
     * it takes one -- which is what makes {@code record start} usable at any moment rather than only
     * before the first frame.
     */
    @Test
    void recordingFromPartWayThroughCarriesAState() throws Exception {
        var replies = session("run 40", "record start", "run 10", "record", "quit");

        assertTrue(replies.get(1).get("anchored").asBoolean());
        assertEquals(40, replies.get(1).get("anchorFrame").asLong());
        assertEquals(10, replies.get(3).get("frames").asLong(),
                "ten frames of movie, from frame forty of the machine");
    }

    @Test
    void recordStartTwiceIsAnError() throws Exception {
        var replies = session("record start", "run 10", "record start", "run 10", "quit");

        assertFalse(replies.get(2).get("ok").asBoolean());
        assertTrue(replies.get(2).get("error").asText().contains("already being recorded"),
                "and says why, since starting again would throw the take away");
        assertEquals(20, replies.get(3).get("frame").asLong(), "the session carried on");
    }

    @Test
    void recordStopWithoutARecordingIsAnError() throws Exception {
        var replies = session("record stop " + movie(), "run 10", "quit");

        assertFalse(replies.getFirst().get("ok").asBoolean());
        assertTrue(replies.getFirst().get("error").asText().contains("record start"),
                "and says what to do");
        assertEquals(10, replies.get(1).get("frame").asLong());
    }

    /**
     * Nowhere named on the command line and nowhere named here, which has to be answered rather than
     * guessed at -- and answered without losing the take.
     */
    @Test
    void recordStopWithNowhereToWriteKeepsTheTake() throws Exception {
        var replies = session("record start", "run 10", "record stop", "record", "quit");

        assertFalse(replies.get(2).get("ok").asBoolean());
        assertTrue(replies.get(3).get("on").asBoolean(), "still recording, still ten frames in");
        assertEquals(10, replies.get(3).get("frames").asLong());
    }

    @Test
    void recordStopWritesAMovieWhereItWasAsked() throws Exception {
        var path = movie();
        var reply = session("record start", "run 25", "record stop " + path, "quit").get(2);

        assertEquals(path.toString(), reply.get("path").asText());
        assertEquals(25, reply.get("frames").asLong());
        assertTrue(reply.get("bytes").asLong() > 0);
        assertTrue(Files.exists(path));
    }

    /**
     * A movie pins the codes at the moment it starts, so changing them half way through would leave
     * a file naming one set that was played against another -- and nothing in it would say so.
     * Listing them is still allowed, since listing changes nothing.
     */
    @Test
    void changingGenieCodesWhileRecordingIsRefused() throws Exception {
        var replies = session(
                "genie SXIOPO",
                "record start",
                "genie",
                "genie ZEXPYGLA",
                "ungenie SXIOPO",
                "genie clear",
                "quit");

        assertTrue(replies.get(2).get("ok").asBoolean(), "listing them is not changing them");
        assertEquals(List.of("SXIOPO"), codes(replies.get(2)));

        for (var refused : List.of(replies.get(3), replies.get(4), replies.get(5))) {
            assertFalse(refused.get("ok").asBoolean(), refused.toString());
            assertTrue(refused.get("error").asText().contains("pinned"));
        }
    }

    /**
     * Where a movie goes in these tests. Never beside a fixture.
     */
    private Path movie() {
        return directory.resolve("take.mnm");
    }

    // ==================================================================================== rewind

    /**
     * The mirror of {@link #aStateGoesBackToWhereItWasTaken}, and the claim is the stronger one: a
     * bookmark goes back to a moment somebody chose in advance, where this goes back to a moment
     * nobody thought about until it had already passed.
     */
    @Test
    void rewindGoesBackToWhereTheMachineWas() throws Exception {
        var replies = session("rewind on", "run 90", "rewind 30", "quit");
        var straight = session("run 60", "quit");

        replies.forEach(reply -> assertTrue(reply.get("ok").asBoolean(), reply.toString()));

        assertEquals(90, replies.get(1).get("frame").asLong());
        assertEquals(30, replies.get(2).get("framesRewound").asInt());
        assertEquals(60, replies.get(2).get("frame").asLong());
        assertEquals(
                straight.getFirst().get("hash").asText(),
                replies.get(2).get("hash").asText(),
                "a rewound machine is the machine that never went forward");
    }

    /**
     * Thirty seconds is what the window keeps, so the default here is thirty seconds too -- and on
     * this machine that is 1803 frames rather than 1800, because a frame is not a sixtieth.
     */
    @Test
    void rewindKeepsThirtySecondsUnlessToldOtherwise() throws Exception {
        var replies = session("rewind on", "run 10", "rewind", "quit");

        assertTrue(replies.getFirst().get("on").asBoolean());
        assertEquals(1803, replies.getFirst().get("capacity").asInt());
        assertEquals(10, replies.get(2).get("rewindable").asInt(),
                "ten frames run and the power-on state under them");
    }

    @Test
    void rewindIsClampedToWhatWasKept() throws Exception {
        var replies = session("rewind on 10", "run 50", "rewind 99", "quit");

        assertEquals(10, replies.getFirst().get("capacity").asInt());
        assertEquals(9, replies.get(2).get("framesRewound").asInt(), "nine kept, not ninety-nine");
        assertEquals(41, replies.get(2).get("frame").asLong());
        assertEquals(0, replies.get(2).get("rewindable").asInt(), "parked on the oldest it kept");
    }

    @Test
    void rewindReportsItsStatus() throws Exception {
        var replies = session("rewind", "rewind on 60", "rewind", "rewind off", "rewind", "quit");

        assertFalse(replies.getFirst().get("on").asBoolean());
        assertFalse(replies.getFirst().has("capacity"), "nothing to say about a ring that is not there");

        assertTrue(replies.get(2).get("on").asBoolean());
        assertEquals(60, replies.get(2).get("capacity").asInt());

        assertFalse(replies.get(4).get("on").asBoolean());
    }

    /**
     * Answered rather than fatal, like every other bad command -- and it has to be told apart from a
     * history that has simply run out, which also moves no frames.
     */
    @Test
    void rewindBeforeOnIsAnError() throws Exception {
        var replies = session("run 10", "rewind 5", "run 10", "quit");

        assertFalse(replies.get(1).get("ok").asBoolean());
        assertTrue(replies.get(1).get("error").asText().contains("rewind on"), "and says what to do");
        assertEquals(20, replies.get(2).get("frame").asLong(), "the session carried on regardless");
    }

    @Test
    void rewindThatIsMisspeltOrTooSmallToWorkIsAnError() throws Exception {
        var replies = session("rewind on 1", "rewind wibble", "rewind on", "rewind on", "quit");

        assertFalse(replies.getFirst().get("ok").asBoolean(), "one state can never rewind");
        assertFalse(replies.get(1).get("ok").asBoolean());
        assertTrue(replies.get(2).get("ok").asBoolean());
        assertFalse(replies.get(3).get("ok").asBoolean(), "arming twice would drop the history");
    }

    /**
     * Turning it off and on again starts the history from here rather than resuming the old one,
     * which is the only honest thing it could do with frames nobody was keeping.
     */
    @Test
    void switchingItOffForgetsTheHistory() throws Exception {
        var replies = session(
                "rewind on", "run 30", "rewind off", "run 30", "rewind on", "rewind 5", "quit");

        assertFalse(replies.get(2).get("on").asBoolean());
        assertEquals(0, replies.get(4).get("rewindable").asInt(), "a fresh ring");
        assertEquals(0, replies.get(5).get("framesRewound").asInt());
        assertEquals(60, replies.get(5).get("frame").asLong(), "and nowhere to go from");
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
        assertEquals("write", reply.get("access").asText());
        assertEquals(0x2000, reply.get("address").asInt());
        assertEquals(0x00, reply.get("value").asInt());
        assertEquals(STA_PPUCTRL, reply.get("writtenBy").asInt(), "the STA, not where it stopped");
        assertNotEquals(STA_PPUCTRL, reply.get("stoppedAt").asInt());
    }

    /**
     * The other direction, and it is named differently on purpose: {@code writtenBy} on a stop where
     * nothing was written would be a lie with a helpful shape.
     */
    @Test
    void aReadWatchpointSaysWhatRead() throws Exception {
        var reply = session("watch $C004 read", "run 5", "quit").get(1);

        assertEquals("watchpoint", reply.get("stopped").asText());
        assertEquals("read", reply.get("access").asText());
        assertEquals(0xC004, reply.get("address").asInt());
        assertEquals(0x78, reply.get("value").asInt(), "the SEI's opcode, fetched off the bus");
        assertEquals(SEI, reply.get("readBy").asInt());
        assertFalse(reply.has("writtenBy"));
    }

    @Test
    void aWatchpointSaysWhichWayItIsFacing() throws Exception {
        var reply = session("watch $2000", "watch $0300 read", "watch $0301 both", "points", "quit")
                .get(3);
        var watchpoints = reply.get("watchpoints");

        // Listed in address order rather than the order they were typed in, which puts the two
        // page-three watches first.
        assertEquals(List.of(0x0300, 0x0301, 0x2000), addresses(reply, "watchpoints"));
        assertEquals("read", watchpoints.get(0).get("on").asText());
        assertEquals("both", watchpoints.get(1).get("on").asText());
        assertEquals("write", watchpoints.get(2).get("on").asText());
    }

    @Test
    void aWatchpointFacingNowhereIsAnsweredRatherThanFatal() throws Exception {
        var replies = session("watch $2000 sideways", "run 5", "quit");

        assertFalse(replies.getFirst().get("ok").asBoolean());
        assertTrue(replies.getFirst().get("error").asText().contains("sideways"));
        assertFalse(replies.get(1).has("stopped"), "and no watchpoint was left behind");
    }

    // ============================================================================== conditions

    @Test
    void aConditionalBreakpointStopsOnlyWhereItHolds() throws Exception {
        var stops = session("break " + TXS + " if x == $FF", "run 5", "quit").get(1);
        var doesNot = session("break " + TXS + " if x == $00", "run 5", "quit").get(1);

        assertEquals(TXS, stops.get("stoppedAt").asInt(), "LDX #$FF ran just before it");
        assertFalse(doesNot.has("stopped"));
    }

    @Test
    void aConditionIsListedTheOneWayRound() throws Exception {
        var reply = session("break $C008 if A==16", "quit").getFirst();

        assertEquals("a == $10", reply.get("condition").asText());
        assertEquals("a == $10", reply.at("/breakpoints/0/condition").asText());
    }

    @Test
    void aBreakpointWithoutAConditionSaysSoExplicitly() throws Exception {
        var reply = session("break $C008", "quit").getFirst();

        assertTrue(reply.get("condition").isNull());
        assertTrue(reply.at("/breakpoints/0/condition").isNull());
    }

    @Test
    void settingTheSameBreakpointAgainReplacesItsCondition() throws Exception {
        var replies = session(
                "break " + TXS + " if x == $00", "break " + TXS, "run 5", "quit");

        assertTrue(replies.get(1).at("/breakpoints/0/condition").isNull());
        assertEquals(TXS, replies.get(2).get("stoppedAt").asInt());
    }

    @Test
    void nonsenseAfterAnAddressIsAnsweredRatherThanFatal() throws Exception {
        var replies = session(
                "break $C008 unless x == 0", "break $C008 if wibble == 0", "break $C008 if",
                "run 5", "quit");

        for (var i = 0; i < 3; i++) {
            assertFalse(replies.get(i).get("ok").asBoolean(), "reply " + i);
        }

        assertTrue(replies.get(3).get("ok").asBoolean(), "the session carries on");
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

    // ================================================================================== tracing

    /**
     * Every session below spends a {@code step 1} before it starts, because the first step is the
     * reset sequence and runs no instruction at all -- so a trace opened before it would come back
     * one line short of what was asked for and look like an off-by-one in the tracer.
     */
    @Test
    void aTraceWritesOneLinePerInstruction() throws Exception {
        var path = directory.resolve("trace.log");
        var replies = session("step 1", "trace " + path, "step 20", "trace off", "quit");

        assertEquals(20, Files.readAllLines(path).size());
        assertTrue(replies.get(1).get("on").asBoolean());
        assertEquals(20, replies.get(3).get("lines").asLong());
        assertFalse(replies.get(3).get("on").asBoolean(), "and stopping means stopped");
    }

    @Test
    void aTraceIsNestestsFormat() throws Exception {
        var path = directory.resolve("trace.log");

        session("step 1", "trace " + path, "step 1", "trace off", "quit");

        var first = Files.readAllLines(path).getFirst();

        assertTrue(
                first.matches("^[0-9A-F]{4} {2}.{8} {2}.{32}"
                        + "A:[0-9A-F]{2} X:[0-9A-F]{2} Y:[0-9A-F]{2} P:[0-9A-F]{2}"
                        + " SP:[0-9A-F]{2} PPU:.{3},.{3} CYC:\\d+$"),
                first);
    }

    @Test
    void aTraceStopsAtTheLineCountItWasGiven() throws Exception {
        var path = directory.resolve("trace.log");
        var replies = session("trace " + path + " 5", "step 200", "trace", "quit");

        assertEquals(5, Files.readAllLines(path).size());
        assertEquals(5, replies.get(2).get("lines").asLong());
        assertTrue(replies.get(2).get("full").asBoolean());
        assertFalse(replies.get(2).get("on").asBoolean(), "picked up as soon as it filled");
    }

    @Test
    void aTraceStopsWhenTheSessionDoes() throws Exception {
        var path = directory.resolve("trace.log");

        // No "trace off": the buffered lines would be lost if the session did not close it.
        session("step 1", "trace " + path, "step 20", "quit");

        assertEquals(20, Files.readAllLines(path).size());
    }

    @Test
    void aBareTraceSaysWhereTheFileWentAfterwards() throws Exception {
        var path = directory.resolve("trace.log");
        var replies = session("step 1", "trace " + path, "step 3", "trace off", "trace", "quit");

        assertEquals(path.toString(), replies.get(4).get("path").asText());
        assertEquals(3, replies.get(4).get("lines").asLong());
        assertFalse(replies.get(4).get("on").asBoolean());
    }

    @Test
    void nothingIsBeingTracedUntilSomethingIs() throws Exception {
        var replies = session("trace", "trace off", "quit");

        assertFalse(replies.getFirst().get("on").asBoolean());
        assertFalse(replies.getFirst().has("path"));
        assertFalse(replies.get(1).get("ok").asBoolean(), "and there is nothing to stop");
    }

    @Test
    void aSecondTraceIsRefusedRatherThanQuietlyReplacingTheFirst() throws Exception {
        var first = directory.resolve("first.log");
        var second = directory.resolve("second.log");
        var replies = session("step 1", "trace " + first, "trace " + second, "step 3", "quit");

        assertFalse(replies.get(2).get("ok").asBoolean());
        assertEquals(3, Files.readAllLines(first).size());
        assertFalse(Files.exists(second));
    }

    // ================================================================================== presses

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
        var session = new Session(
                new NES(cart),
                Palettes.defaultPalette().colours(),
                VideoFilter.NONE,
                FilterStrength.defaultStrength(),
                false,
                null);
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

    /**
     * The addresses out of a list of points, which are objects rather than bare numbers: a point
     * carries a condition or a direction now, and a list of numbers could not say either.
     */
    private static List<Integer> addresses(final JsonNode reply, final String field) {
        var out = new ArrayList<Integer>();
        reply.get(field).forEach(point -> out.add(point.get("address").asInt()));

        return out;
    }

    private static List<String> buttons(final JsonNode reply) {
        var names = new ArrayList<String>();
        reply.get("buttons").forEach(name -> names.add(name.asText()));

        return names;
    }
}
