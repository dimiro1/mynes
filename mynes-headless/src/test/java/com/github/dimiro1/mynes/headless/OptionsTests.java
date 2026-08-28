package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

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
    void patchesAreKeptInTheOrderTheyWereNamed() {
        var options = parse("--rom", "x.nes", "--patch", "first.ips", "--patch", "second.ips");

        assertEquals(
                List.of(Path.of("first.ips"), Path.of("second.ips")),
                options.patches());
    }

    @Test
    void aRunWithNoPatchesHasNone() {
        assertTrue(parse("--rom", "x.nes").patches().isEmpty());
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
        assertEquals("nesdev", options.paletteFor(Region.NTSC).id());
        assertNull(options.palette(), "nobody named one, so the region decides");
        assertNull(options.region(), "and nobody named that either, so the cartridge does");
        assertFalse(options.fullFrame());
        assertFalse(options.audio());
    }

    @Test
    void aReportOfMinusIsPrintedRatherThanFiled() {
        assertNull(parse("--rom", "x.nes", "--report", "-").reportPath());
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
        assertEquals("wavebeam",
                parse("--rom", "x.nes", "--palette", "wavebeam").paletteFor(Region.NTSC).id());
    }

    @Test
    void theFilterIsNoneUnlessOneIsNamed() {
        assertEquals(VideoFilter.NONE, parse("--rom", "x.nes").filter());
        assertEquals(VideoFilter.NTSC, parse("--rom", "x.nes", "--filter", "ntsc").filter());
    }

    /**
     * Refused rather than fallen back from, for the reason an unknown palette is. Whether the
     * machine can actually use it is a different question and cannot be asked here: it depends on
     * the cartridge, so {@code Headless} asks it once one has been read.
     */
    @Test
    void aStrengthRidesBehindTheFilterName() {
        assertEquals(FilterStrength.MEDIUM, parse("--rom", "x.nes", "--filter", "ntsc").strength());
        assertEquals(
                FilterStrength.LOW, parse("--rom", "x.nes", "--filter", "ntsc=low").strength());
        assertEquals(
                FilterStrength.STRONG,
                parse("--rom", "x.nes", "--filter", "ntsc=strong").strength());
    }

    /**
     * The last one wins whole, rather than a strength surviving the filter it was said about.
     */
    @Test
    void aSecondFilterReplacesTheFirstStrengthAndAll() {
        assertEquals(
                FilterStrength.MEDIUM,
                parse("--rom", "x.nes", "--filter", "ntsc=low", "--filter", "ntsc").strength());
    }

    @Test
    void anUnknownStrengthIsRefused() {
        var message = refused("--rom", "x.nes", "--filter", "ntsc=high").getMessage();

        assertTrue(message.contains("high"));
        assertTrue(message.contains("medium"), "the message should offer the real ids");
    }

    /**
     * Rather than ignored. A palette has no chroma trap to lean on, so somebody asking for a softer
     * one has misunderstood something and is better told than humoured.
     */
    @Test
    void aStrengthOnThePaletteIsRefused() {
        assertTrue(refused("--rom", "x.nes", "--filter", "none=low").getMessage().contains("none"));
    }

    @Test
    void anUnknownFilterIsRefused() {
        var message = refused("--rom", "x.nes", "--filter", "composite").getMessage();

        assertTrue(message.contains("composite"));
        assertTrue(message.contains("ntsc"), "the message should offer the real ids");
    }

    @Test
    void aRegionIsTakenAsAskedAndOverridesTheCartridge() {
        assertEquals(Region.PAL, parse("--rom", "x.nes", "--region", "pal").region());
        assertEquals(Region.NTSC, parse("--rom", "x.nes", "--region", "ntsc").region());
    }

    /**
     * Left off, there is nothing to override with, and the cartridge's header answers instead.
     */
    @Test
    void noRegionAtAllLeavesItToTheCartridge() {
        assertNull(parse("--rom", "x.nes").region());
    }

    /**
     * Refused rather than defaulted, for the reason a misspelled palette is: a run that quietly
     * happened on the other machine would look like it had worked, and every number in the report
     * would be about a console nobody asked for.
     */
    @Test
    void anUnknownRegionIsRefusedRatherThanFallenBackFrom() {
        var message = refused("--rom", "x.nes", "--region", "secam").getMessage();

        assertTrue(message.contains("secam"));
        assertTrue(message.contains("pal"), "the message should offer the real ids");
    }

    @Test
    void thePaletteFollowsTheRegionWhenNobodyNamedOne() {
        // A PAL cartridge drawn with an NTSC table is the wrong picture rather than a differently
        // measured one, so the default has to know which machine it is for.
        var options = parse("--rom", "x.nes");

        assertEquals("nesdev", options.paletteFor(Region.NTSC).id());
        assertEquals(Palettes.PAL_ID, options.paletteFor(Region.PAL).id());
    }

    @Test
    void anExplicitPaletteWinsOverTheRegion() {
        var options = parse("--rom", "x.nes", "--palette", "wavebeam");

        assertEquals("wavebeam", options.paletteFor(Region.PAL).id(), "somebody asked for it");
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
    void noHackIsAskedForUnlessOneIsNamed() {
        assertTrue(parse("--rom", "x.nes").hacks().isEmpty(), "the console is the default");
    }

    @Test
    void aHackIsTakenAsAsked() {
        assertEquals(
                Set.of(Options.UNLIMITED_SPRITES),
                parse("--rom", "x.nes", "--hack", "unlimited-sprites").hacks());
    }

    /**
     * Refused rather than ignored, for the reason a misspelled region is: the only place the
     * difference shows is the picture, so a run that quietly did not switch it on would look like
     * it had worked.
     */
    @Test
    void aHackNameThatIsNotOnTheListIsRejected() {
        var message = refused("--rom", "x.nes", "--hack", "infinite-lives").getMessage();

        assertTrue(message.contains("infinite-lives"));
        assertTrue(message.contains("unlimited-sprites"), "the message should offer the real ids");
    }

    @Test
    void anOverclockIsTakenAsLines() {
        var options = parse("--rom", "x.nes", "--hack", "overclock=131");

        assertEquals(new Overclock(131, 0), options.overclock());
        assertEquals(Set.of(Options.OVERCLOCK), options.hacks(),
                "it is named under --hack like every other one, so run.hacks reports it");
    }

    @Test
    void anOverclockTakesLinesAfterTheNmiToo() {
        assertEquals(
                new Overclock(131, 20),
                parse("--rom", "x.nes", "--hack", "overclock=131+20").overclock());
    }

    @Test
    void noOverclockIsAskedForUnlessOneIsNamed() {
        assertEquals(Overclock.NONE, parse("--rom", "x.nes").overclock(),
                "the console's own frame is the default");
    }

    /**
     * The one hack that takes a value, so the one that can be half typed. "overclock" on its own is
     * a wish nobody can act on -- there is no obvious number of lines to pick -- and a run that
     * quietly chose one would look like it had worked.
     */
    @Test
    void anOverclockWithoutALineCountIsRejected() {
        var message = refused("--rom", "x.nes", "--hack", "overclock").getMessage();

        assertTrue(message.contains("scanlines"), message);
        assertTrue(message.contains("overclock=131"), "and the message should show the form");
    }

    @Test
    void anOverclockOutsideTheRangeIsRejected() {
        assertTrue(refused("--rom", "x.nes", "--hack", "overclock=1001")
                .getMessage().contains("0 to " + Overclock.MAX_SCANLINES));

        assertTrue(refused("--rom", "x.nes", "--hack", "overclock=-1")
                .getMessage().contains("0 to " + Overclock.MAX_SCANLINES));

        assertTrue(refused("--rom", "x.nes", "--hack", "overclock=lots")
                .getMessage().contains("lots"));
    }

    @Test
    void zeroLinesIsHowToWriteOffOnACommandLineSomethingElseBuilt() {
        assertEquals(Overclock.NONE, parse("--rom", "x.nes", "--hack", "overclock=0").overclock());
    }

    @Test
    void aHackThatTakesNoValueRefusesOne() {
        var message = refused("--rom", "x.nes", "--hack", "unlimited-sprites=3").getMessage();

        assertTrue(message.contains("unlimited-sprites"), message);
    }

    @Test
    void theHacksShareOneList() {
        var options = parse("--rom", "x.nes", "--hack", "unlimited-sprites,overclock=30");

        assertEquals(Set.of(Options.UNLIMITED_SPRITES, Options.OVERCLOCK), options.hacks());
        assertEquals(new Overclock(30, 0), options.overclock());
    }

    @Test
    void severalHackFlagsAccumulateRatherThanReplacingEachOther() {
        var options = parse(
                "--rom", "x.nes", "--hack", "overclock=30", "--hack", "unlimited-sprites");

        assertEquals(new Overclock(30, 0), options.overclock(),
                "the second flag said nothing about the overclock, so it kept the first's");
    }

    @Test
    void noGameGenieCodeIsInUnlessOneIsGiven() {
        assertTrue(parse("--rom", "x.nes").genie().isEmpty(), "the cartridge slot is the default");
    }

    @Test
    void aGameGenieCodeIsDecodedWhileTheCommandLineIsBeingRead() {
        var codes = parse("--rom", "x.nes", "--genie", "SXIOPO").genie();

        assertEquals(1, codes.size());
        assertEquals(0x91D9, codes.getFirst().address());
        assertEquals(0xAD, codes.getFirst().value());
    }

    @Test
    void codesCanBeCommaSeparatedOrRepeated() {
        var together = parse("--rom", "x.nes", "--genie", "SXIOPO,ZEXPYGLA").genie();
        var apart = parse("--rom", "x.nes", "--genie", "SXIOPO", "--genie", "ZEXPYGLA").genie();

        assertEquals(together, apart);
        assertEquals(2, together.size());
    }

    /**
     * Decoded here rather than when the machine is built, so that a mistyped code is a bad command
     * line rather than a run that quietly cheated at nothing. Eight letters from an alphabet with no
     * B, C, D or R in it is not a remote thing to get wrong.
     */
    @Test
    void aCodeThatIsNotOneIsRejected() {
        assertTrue(refused("--rom", "x.nes", "--genie", "GOSSIB").getMessage().contains("B"));
        assertTrue(refused("--rom", "x.nes", "--genie", "SXIOP").getMessage().contains("six"));
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
        assertTrue(Options.usage().contains("mynes.jar"));
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

    @Test
    void theMovieFlagsAreWhereTheyWereNamed() {
        var options = parse("--rom", "x.nes", "--record", "take.mnm");

        assertEquals(Path.of("take.mnm"), options.record());
        assertNull(options.play());

        assertEquals(Path.of("in.mnm"), parse("--rom", "x.nes", "--play", "in.mnm").play());
    }

    @Test
    void aRunPlaysAndRecordsNothingUnlessToldOtherwise() {
        var options = parse("--rom", "x.nes");

        assertNull(options.record());
        assertNull(options.play());
    }

    /**
     * How long a movie is cannot be known while the command line is being read, so {@code --frames}
     * has to be remembered as given or not given rather than as a number: 600 and "nobody said" are
     * the same value and different answers.
     */
    @Test
    void whetherFramesWasAskedForIsRememberedSeparatelyFromTheNumber() {
        assertFalse(parse("--rom", "x.nes").framesSet());
        assertEquals(600, parse("--rom", "x.nes").frames());

        assertTrue(parse("--rom", "x.nes", "--frames", "600").framesSet(),
                "the default typed out is still somebody having typed it");
        assertTrue(parse("--rom", "x.nes", "--frames", "30").framesSet());
    }

    /**
     * Each of these is a second answer to something the movie has already answered, and a run that
     * quietly took one of them would not be the recorded session at all.
     */
    @Test
    void playRefusesTheFlagsTheMovieReplaces() {
        var replaced = List.of(
                List.of("--record", "out.mnm"),
                List.of("--input", "60:start"),
                List.of("--reset-at", "100"),
                List.of("--genie", "SXIOPO"),
                List.of("--hack", "overclock=131"),
                List.of("--load-state", "in.mn"),
                List.of("--sram-in", "in.sav"),
                List.of("--interactive"));

        for (var flags : replaced) {
            var args = new ArrayList<>(List.of("--rom", "x.nes", "--play", "take.mnm"));
            args.addAll(flags);

            var refused = refused(args.toArray(new String[0]));

            assertTrue(refused.getMessage().contains(flags.getFirst()),
                    "the message has to name the flag that was typed: " + refused.getMessage());
            assertTrue(refused.getMessage().contains("--play"));
        }
    }

    /**
     * The two hacks are not the same kind of thing to a replay, and this is the difference. The
     * sprite limit changes only pixels, so a replay with it on is still the recorded session seen
     * more clearly; the overclock changes how much of its work the game gets through in a frame,
     * which makes it a different session.
     */
    @Test
    void playRefusesAnOverclockButNotTheOtherHack() {
        var refused = refused(
                "--rom", "x.nes", "--play", "take.mnm", "--hack", "overclock=131");

        assertTrue(refused.getMessage().contains("--hack overclock"), refused.getMessage());

        assertEquals(
                Set.of(Options.UNLIMITED_SPRITES),
                parse("--rom", "x.nes", "--play", "take.mnm", "--hack", "unlimited-sprites")
                        .hacks());
    }

    @Test
    void theUsageSaysWhatAnOverclockedRunIsNotComparableWith() {
        assertTrue(Options.usage().contains("--hack overclock"),
                "somebody reading --help should learn that --play refuses it");
        assertTrue(Options.usage().contains("overclock=N[+M]"),
                "and what the flag looks like");
        assertTrue(Options.usage().contains("stands still"),
                "and that the sound is a hardware frame's worth however long the frame is");
    }

    /**
     * Everything else combines. A recorded run is an ordinary run with somebody taking notes.
     */
    @Test
    void recordCombinesWithEverythingElse() {
        var options = parse(
                "--rom", "x.nes",
                "--record", "take.mnm",
                "--input", "60:start",
                "--reset-at", "100",
                "--genie", "SXIOPO",
                "--load-state", "in.mn",
                "--interactive");

        assertEquals(Path.of("take.mnm"), options.record());
        assertTrue(options.interactive());
    }

    @Test
    void theUsageExplainsThatARewindIsNotInTheMovie() {
        assertTrue(Options.usage().contains("never re-enacts the revert"),
                "somebody reading --help should learn what a rewind does to a recording");
    }

    @Test
    void theReplyFormatIsLeftToBeResolvedUnlessNamed() {
        assertEquals(Options.Format.AUTO, parse("--rom", "x.nes").format());
    }

    @Test
    void aNamedReplyFormatIsTakenAsAsked() {
        assertEquals(Options.Format.JSON, parse("--rom", "x.nes", "--format", "json").format());
        assertEquals(Options.Format.TEXT, parse("--rom", "x.nes", "--format", "text").format());
    }

    @Test
    void aReplyFormatThatIsNoneOfTheThreeIsRefused() {
        assertTrue(refused("--rom", "x.nes", "--format", "yaml").getMessage().contains("yaml"));
    }
}
