package com.github.dimiro1.mynes.headless;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the command line.
 * <p>
 * Most of what is here is about what happens when it is wrong, because that is the half a person
 * meets: an option nobody typed correctly has to say so and name what would have worked, rather
 * than run something almost right and let the pictures be the first clue.
 */
class OptionsTests {
    private static Options parse(final String... args) {
        return Options.parse(args);
    }

    private static UsageException refused(final String... args) {
        return assertThrows(UsageException.class, () -> Options.parse(args));
    }

    @Test
    void aRomIsRequired() {
        assertTrue(refused("--frames", "10").getMessage().contains("--rom"));
    }

    @Test
    void anUnknownFlagIsRejectedByName() {
        assertTrue(refused("--rom", "x.nes", "--frobnicate").getMessage().contains("--frobnicate"));
    }

    @Test
    void aFlagThatWantsAValueSaysSoWhenItIsLast() {
        assertTrue(refused("--rom", "x.nes", "--frames").getMessage().contains("--frames"));
    }

    @Test
    void theDefaultsAreTenSecondsIntoTargetHeadless() {
        var options = parse("--rom", "x.nes");

        assertEquals(600, options.frames());
        assertEquals(Path.of("target", "headless"), options.outDir());
        assertEquals(Path.of("target", "headless", "report.json"), options.reportPath());
        assertEquals(1, options.scale());
        assertEquals(2, options.pressFrames());
        assertEquals("nesdev", options.palette().id());
        assertFalse(options.fullFrame());
        assertFalse(options.audio());
    }

    @Test
    void aReportOfMinusIsPrintedRatherThanFiled() {
        assertEquals(null, parse("--rom", "x.nes", "--report", "-").reportPath());
    }

    @Test
    void screenshotFramesAccumulateAcrossRepeatedFlags() {
        var options = parse(
                "--rom", "x.nes", "--screenshot", "60,120", "--screenshot", "300");

        assertEquals(3, options.screenshotFrames().size());
        assertTrue(options.wantsScreenshotAt(60));
        assertTrue(options.wantsScreenshotAt(120));
        assertTrue(options.wantsScreenshotAt(300));
        assertFalse(options.wantsScreenshotAt(61));
    }

    @Test
    void lastIsAScreenshotFrameOfItsOwn() {
        var options = parse("--rom", "x.nes", "--screenshot", "60,last");

        assertTrue(options.screenshotLast());
        assertEquals(1, options.screenshotFrames().size());
    }

    @Test
    void screenshotEveryPicksOutTheMultiples() {
        var options = parse("--rom", "x.nes", "--screenshot-every", "10");

        assertTrue(options.wantsScreenshotAt(10));
        assertTrue(options.wantsScreenshotAt(200));
        assertFalse(options.wantsScreenshotAt(15));
    }

    @Test
    void aScreenshotFrameThatIsNotANumberIsRejected() {
        assertTrue(refused("--rom", "x.nes", "--screenshot", "soon")
                .getMessage().contains("soon"));
    }

    /**
     * The one place this deliberately parts company with {@code Palettes.byId}, which logs and
     * falls back to the default. That is right for a settings file somebody typed by hand and wrong
     * here, where the run would otherwise draw its pictures in a palette nobody asked for and look
     * like it had worked.
     */
    @Test
    void anUnknownPaletteIsRefusedRatherThanFallenBackFrom() {
        var message = refused("--rom", "x.nes", "--palette", "wavebem").getMessage();

        assertTrue(message.contains("wavebem"));
        assertTrue(message.contains("wavebeam"), "the message should offer the real ids");
    }

    @Test
    void aKnownPaletteIsTakenAsAsked() {
        assertEquals("wavebeam", parse("--rom", "x.nes", "--palette", "wavebeam").palette().id());
    }

    @Test
    void aScaleOutsideOneToEightIsRejected() {
        assertTrue(refused("--rom", "x.nes", "--scale", "9").getMessage().contains("8"));
        assertTrue(refused("--rom", "x.nes", "--scale", "0").getMessage().contains("8"));
    }

    @Test
    void aDumpNameThatIsNotOnTheListIsRejected() {
        assertTrue(refused("--rom", "x.nes", "--dump", "everything")
                .getMessage().contains("everything"));
    }

    @Test
    void allIsEveryDump() {
        assertEquals(Session.DUMPS, parse("--rom", "x.nes", "--dump", "all").dumps());
    }

    @Test
    void helpDoesNotNeedARom() {
        assertTrue(parse("--help").help());
    }

    @Test
    void listingThePalettesDoesNotNeedARomEither() {
        assertTrue(parse("--list-palettes").listPalettes());
    }

    @Test
    void aScriptImpliesTheInteractiveMode() {
        var options = parse("--rom", "x.nes", "--script", "session.txt");

        assertTrue(options.interactive());
        assertEquals(Path.of("session.txt"), options.scriptPath());
    }

    @Test
    void theUsageMentionsBothWaysIn() {
        assertTrue(Options.usage().contains("exec:exec@headless"));
        assertTrue(Options.usage().contains("jar-with-dependencies"));
    }

    @Test
    void theStateAndBatteryFilesAreWhereTheyWereNamed() {
        var options = parse(
                "--rom", "x.nes",
                "--load-state", "in.mn", "--save-state", "out.mn",
                "--sram-in", "in.sav", "--sram-out", "out.sav");

        assertEquals(Path.of("in.mn"), options.loadState());
        assertEquals(Path.of("out.mn"), options.saveState());
        assertEquals(Path.of("in.sav"), options.sramIn());
        assertEquals(Path.of("out.sav"), options.sramOut());
    }

    @Test
    void aRunStartsAtPowerOnUnlessToldOtherwise() {
        var options = parse("--rom", "x.nes");

        assertNull(options.loadState());
        assertNull(options.saveState());
        assertNull(options.sramIn());
        assertNull(options.sramOut());
    }

    @Test
    void aStateFlagWithNothingAfterItIsRefused() {
        assertThrows(UsageException.class, () -> parse("--rom", "x.nes", "--load-state"));
        assertThrows(UsageException.class, () -> parse("--rom", "x.nes", "--sram-out"));
    }

    @Test
    void theUsageExplainsThatABatteryFileIsTheInteroperableOne() {
        assertTrue(Options.usage().contains("every other emulator"),
                "somebody reading --help should learn that a .sav can come from anywhere");
    }
}
