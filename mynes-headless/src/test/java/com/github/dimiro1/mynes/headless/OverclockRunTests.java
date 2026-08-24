package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * What {@code --hack overclock} does to a game that cannot keep up, from a command line to the RAM
 * it leaves behind.
 * <p>
 * The core's tests pin the timing to the dot; what they cannot say is that a flag typed on a command
 * line turns into a game that stops dropping frames. So this runs {@link OverclockROM} -- a cartridge
 * whose main loop takes 1.43 frames on purpose -- three ways, and reads the counters out of zero
 * page with {@code --dump ram}.
 * <p>
 * Three ways rather than two, because "more is better" is not the claim. The claim is that the
 * frame has to be long enough for the work: at +25% it is not, and the game lags exactly as it did
 * on the hardware.
 */
class OverclockRunTests {
    private static final String ROM = "src/test/resources/overclock/overclock.nes";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Long enough for the difference to be unmistakable and short enough to run a handful of times
     * in a test suite. The cartridge spends its first two frames waiting for the PPU to warm up, so
     * the counts below are of 298 rather than 300.
     */
    private static final String FRAMES = "300";

    /**
     * How many frames the cartridge actually gets through -- {@link #FRAMES} less the two it spends
     * waiting for the PPU's warm-up window to close.
     */
    private static final int PLAYED = 298;

    /**
     * Half an NTSC frame again, which is enough: 44671 cycles against the 42500 a lap takes.
     */
    private static final String ENOUGH = "overclock=131";

    /**
     * A quarter, which is not: 37282 cycles, and the lap still does not fit.
     */
    private static final String NOT_ENOUGH = "overclock=66";

    @TempDir
    private Path out;

    /**
     * The cartridge on disk and the generator beside it cannot drift apart, which is the whole
     * reason it is safe to have both.
     */
    @Test
    void theCheckedInCartridgeIsExactlyWhatTheGeneratorProduces() throws Exception {
        assertArrayEquals(
                OverclockROM.image(),
                Files.readAllBytes(Path.of(ROM)),
                "the cartridge and the code that describes it have come apart. To rewrite it:\n"
                        + "  mvn -q -pl mynes-headless -am test-compile\n"
                        + "  java -cp mynes-headless/target/test-classes "
                        + OverclockROM.class.getName() + " \\\n"
                        + "      mynes-headless/" + ROM + "\n"
                        + "and change overclock.s to match, since nothing checks that one");
    }

    @Test
    void onTheHardwareTheGameLagsEveryOtherFrame() throws Exception {
        var into = run("plain");

        assertEquals(PLAYED, frames(into));
        assertEquals(PLAYED / 2, iterations(into),
                "the lap takes 1.43 frames, so it finishes on every other NMI");
    }

    @Test
    void withTheLinesAddedItKeepsUp() throws Exception {
        var into = run("enough", "--hack", ENOUGH);

        assertEquals(PLAYED, frames(into), "the same number of pictures either way");
        assertEquals(PLAYED, iterations(into), "and one lap of the game in each of them");
    }

    /**
     * The other half of the claim, and the reason there are three runs here. A frame that is longer
     * but still not long enough buys nothing at all -- the lap still misses its NMI and still waits
     * for the one after.
     */
    @Test
    void aQuarterIsNotEnough() throws Exception {
        var into = run("not-enough", "--hack", NOT_ENOUGH);

        assertEquals(PLAYED, frames(into));
        assertEquals(PLAYED / 2, iterations(into), "37282 cycles is still short of the 42500");
    }

    /**
     * The same thing without a debugger. The cartridge leaves the VRAM address on a palette cell
     * chosen by the lap counter, and with rendering off that cell is the whole screen -- so the
     * picture changes once per finished lap and {@code frameChanges} counts them.
     */
    @Test
    void theReportSeesTwiceAsManyFrameChanges() throws Exception {
        var plain = report(run("plain")).at("/video/frameChanges").asInt();
        var overclocked = report(run("enough", "--hack", ENOUGH))
                .at("/video/frameChanges").asInt();

        // Within one either way: the run stops mid-lap, so whether the last change landed inside
        // the frame count depends on where the beam was when it did.
        assertEquals(PLAYED / 2, plain, 1.0, "one change of colour per finished lap");
        assertEquals(PLAYED, overclocked, 1.0, "and twice as many laps finished");
    }

    /**
     * The picture is drawn exactly as the hardware draws it -- the extra lines are lines the beam is
     * already idle on -- so the difference between the two runs is what the game did, not how it was
     * rendered.
     */
    @Test
    void bothRunsDrawTheSameKindOfPicture() throws Exception {
        var plain = report(run("plain")).at("/video/finalFrame/uniqueColours").asInt();
        var overclocked = report(run("enough", "--hack", ENOUGH))
                .at("/video/finalFrame/uniqueColours").asInt();

        assertEquals(1, plain, "the whole screen is the backdrop, and nothing else is drawn");
        assertEquals(1, overclocked, "and the extra lines drew nothing on top of it");
    }

    @Test
    void theReportSaysHowManyLinesWereAdded() throws Exception {
        var plain = report(run("plain")).at("/run/hacks/overclock");

        assertEquals(0, plain.get("beforeNmi").asInt(), "always present, even when it is nothing");
        assertEquals(0, plain.get("afterNmi").asInt());

        var both = report(run("both", "--hack", "overclock=90+40")).at("/run/hacks/overclock");

        assertEquals(90, both.get("beforeNmi").asInt());
        assertEquals(40, both.get("afterNmi").asInt());
    }

    /**
     * Read off the machine rather than off the command line, so a session that changed its mind mid
     * way through is reported as it ended.
     */
    @Test
    void theReportSaysWhatTheMachineHoldsRatherThanWhatWasAskedFor() throws Exception {
        var into = out.resolve("repl");

        assertEquals(Headless.EXIT_OK, Headless.run(new String[]{
                "--rom", ROM,
                "--out", into.toString(),
                "--quiet",
                "--frames", "30",
                "--hack", ENOUGH,
                "--script", script("hack overclock 40 20", "quit").toString()}));

        var overclock = report(into).at("/run/hacks/overclock");

        assertEquals(40, overclock.get("beforeNmi").asInt());
        assertEquals(20, overclock.get("afterNmi").asInt());
    }

    /**
     * The claim the movie chunk exists for. The overclock decides how much of its work the game gets
     * through in a frame, so a replay that took the hardware's timing would be a replay of a
     * different game -- and nothing about the cartridge would say which had happened.
     */
    @Test
    void aReplayPutsTheMoviesOverclockBack() throws Exception {
        var take = out.resolve("take.mnm");
        var recorded = out.resolve("a.mn");
        var replayed = out.resolve("b.mn");

        var first = run("recorded", "--hack", ENOUGH,
                "--record", take.toString(), "--save-state", recorded.toString());

        var into = out.resolve("replayed");

        // Nobody types a hack here, and --play would refuse one if they tried.
        assertEquals(Headless.EXIT_OK, Headless.run(new String[]{
                "--rom", ROM,
                "--out", into.toString(),
                "--quiet",
                "--play", take.toString(),
                "--save-state", replayed.toString(),
                "--dump", "ram"}));

        assertEquals(131, report(into).at("/run/hacks/overclock/beforeNmi").asInt(),
                "the machine was set from the movie");
        assertEquals(iterations(first), iterations(into), "the same game happened");
        assertArrayEquals(
                Files.readAllBytes(recorded),
                Files.readAllBytes(replayed),
                "byte-identical end state, which is the whole claim");
    }

    /**
     * And a movie of an overclocked take really does need it, which is what makes carrying the chunk
     * worth the four bytes rather than merely tidy.
     */
    @Test
    void thatSameTakeWouldHaveBeenADifferentGameWithoutIt() throws Exception {
        var overclocked = run("enough", "--hack", ENOUGH);
        var plain = run("plain");

        assertNotEquals(iterations(plain), iterations(overclocked));
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
        args[7] = "--dump";
        args[8] = "ram";

        System.arraycopy(extra, 0, args, 9, extra.length);

        assertEquals(Headless.EXIT_OK, Headless.run(args));

        return into;
    }

    private Path script(final String... commands) throws IOException {
        var path = out.resolve("session.txt");

        Files.writeString(path, String.join(System.lineSeparator(), commands));

        return path;
    }

    /**
     * The cartridge's frame counter, out of $00-$01.
     */
    private static int frames(final Path into) throws IOException {
        return word(into, OverclockROM.FRAMES_AT);
    }

    /**
     * Its lap counter, out of $02-$03.
     */
    private static int iterations(final Path into) throws IOException {
        return word(into, OverclockROM.ITERATIONS_AT);
    }

    private static int word(final Path into, final int address) throws IOException {
        var ram = Files.readAllBytes(into.resolve("ram.bin"));

        return Byte.toUnsignedInt(ram[address]) | Byte.toUnsignedInt(ram[address + 1]) << 8;
    }

    private static JsonNode report(final Path into) throws IOException {
        return MAPPER.readTree(Files.readString(into.resolve("report.json")));
    }
}
