package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the row along the bottom of the window.
 * <p>
 * What it says is a string, so a string is what is checked here rather than a painted picture: the
 * sentence is the whole of the behaviour, and the labels it goes into are calls to {@code setText}.
 * <p>
 * The three parts of the class are the three decisions. What the bar has to say and in what order,
 * which is {@code parts}; how much of that a given width holds and what it says about the rest,
 * which is {@code fitted}; and what the tooltip adds, which is {@code detail}.
 */
class StatusBarTests {

    /**
     * A machine with everything at its default, and one step per thing a test wants to move.
     * Twelve components is far too many to spell out in a test whose point is the one that differs.
     */
    private static Setup setup() {
        return new Setup();
    }

    private static final class Setup {
        private Region region = Region.NTSC;
        private RegionSetting regionSetting = RegionSetting.AUTOMATIC;
        private Overclock overclock = Overclock.NONE;
        private int genieCodes;
        private boolean unlimitedSprites;
        private VideoFilter filter = VideoFilter.NONE;
        private FilterStrength strength = FilterStrength.defaultStrength();
        private boolean warp;
        private boolean overscan;
        private boolean leftEdge = true;
        private boolean tvAspect;
        private String palette = "NESdev";
        private final ScreenScale screenScale = ScreenScale.TWO_TIMES;
        private ScreenScale screenshotScale = ScreenScale.ONE_TIMES;
        private final EmulationSpeed fastForward = EmulationSpeed.FOUR_TIMES;
        private int rewindSeconds = 30;
        private boolean muted;
        private Volume volume = Volume.defaultVolume();
        private int audioLatencyMs = AudioOutput.DEFAULT_LATENCY_MS;

        Setup on(final Region region, final RegionSetting setting) {
            this.region = region;
            this.regionSetting = setting;
            return this;
        }

        Setup overclocked(final Overclock overclock) {
            this.overclock = overclock;
            return this;
        }

        Setup cheating(final int codes) {
            this.genieCodes = codes;
            return this;
        }

        Setup withEverySpriteDrawn() {
            this.unlimitedSprites = true;
            return this;
        }

        Setup through(final VideoFilter filter) {
            this.filter = filter;
            return this;
        }

        Setup at(final FilterStrength strength) {
            this.strength = strength;
            return this;
        }

        Setup behindCurvedGlass() {
            this.warp = true;
            return this;
        }

        Setup showingOverscan() {
            this.overscan = true;
            return this;
        }

        Setup withoutTheLeftEdge() {
            this.leftEdge = false;
            return this;
        }

        Setup withTelevisionsPixels() {
            this.tvAspect = true;
            return this;
        }

        Setup colouredBy(final String palette) {
            this.palette = palette;
            return this;
        }

        Setup shotAt(final ScreenScale scale) {
            this.screenshotScale = scale;
            return this;
        }

        Setup keeping(final int seconds) {
            this.rewindSeconds = seconds;
            return this;
        }

        Setup silent() {
            this.muted = true;
            return this;
        }

        Setup at(final Volume volume) {
            this.volume = volume;
            return this;
        }

        StatusBar.Machine machine() {
            return new StatusBar.Machine(region, regionSetting, overclock, genieCodes,
                    unlimitedSprites, filter, strength, warp, overscan, leftEdge, tvAspect,
                    palette,
                    screenScale,
                    screenshotScale, fastForward, rewindSeconds, muted, volume, audioLatencyMs);
        }
    }

    @Nested
    @DisplayName("what the bar has to say")
    class WhatItSays {
        private static String line(final StatusBar.Machine machine) {
            return String.join(" · ", StatusBar.parts(machine));
        }

        /**
         * Its own part rather than a qualifier on the filter's, because it applies whether or not
         * a filter is named -- so it has to be able to appear on a line with no filter on it.
         */
        @Test
        void theTelevisionsPixelsGetAPartOfTheirOwn() {
            assertEquals(
                    List.of("NTSC", "TV aspect"),
                    StatusBar.parts(setup().withTelevisionsPixels().machine()));

            assertEquals(
                    "NTSC · NTSC filter · TV aspect",
                    line(setup().through(VideoFilter.NTSC).withTelevisionsPixels().machine()));
        }

        @Test
        void aMachineWithNothingDoneToItIsJustTheConsole() {
            assertEquals(List.of("NTSC"), StatusBar.parts(setup().machine()));
        }

        /**
         * The console is the one thing always named, and the one thing never dropped: it is
         * invisible from the picture until something is wrong, and a game running 17% fast is
         * exactly that.
         */
        @Test
        void aPALMachineSaysSo() {
            assertEquals(List.of("PAL"),
                    StatusBar.parts(setup().on(Region.PAL, RegionSetting.PAL).machine()));
        }

        /**
         * The strength qualifies the filter's own part rather than taking one of its own, and only
         * once it is not the one it starts at -- so the ordinary decoded line is unchanged and the
         * window has the same number of parts to fit either way.
         */
        @Test
        void aStrengthThatIsNotTheDefaultQualifiesTheFilterRatherThanJoiningIt() {
            assertEquals(
                    List.of("NTSC", "NTSC filter"),
                    StatusBar.parts(setup().through(VideoFilter.NTSC).machine()));

            assertEquals(
                    List.of("NTSC", "NTSC filter (Low)"),
                    StatusBar.parts(setup()
                            .through(VideoFilter.NTSC)
                            .at(FilterStrength.LOW)
                            .machine()));
        }

        /**
         * The tube has a second qualifier, and the two share the brackets rather than taking one
         * part each -- for the reason the strength does not take one of its own.
         */
        @Test
        void theCurveOfTheGlassQualifiesTheTubeBesideTheStrength() {
            assertEquals(
                    List.of("NTSC", "CRT filter"),
                    StatusBar.parts(setup().through(VideoFilter.CRT).machine()));

            assertEquals(
                    List.of("NTSC", "CRT filter (Curved)"),
                    StatusBar.parts(setup()
                            .through(VideoFilter.CRT)
                            .behindCurvedGlass()
                            .machine()));

            assertEquals(
                    List.of("NTSC", "CRT filter (Strong, Curved)"),
                    StatusBar.parts(setup()
                            .through(VideoFilter.CRT)
                            .at(FilterStrength.STRONG)
                            .behindCurvedGlass()
                            .machine()));
        }

        /**
         * And a glass nothing is drawing on says nothing, the way a strength does not.
         */
        @Test
        void aCurveSaysNothingWhileSomethingElseIsDrawing() {
            assertEquals(
                    List.of("NTSC"),
                    StatusBar.parts(setup().behindCurvedGlass().machine()));

            assertEquals(
                    List.of("NTSC", "NTSC filter"),
                    StatusBar.parts(setup()
                            .through(VideoFilter.NTSC)
                            .behindCurvedGlass()
                            .machine()));
        }

        /**
         * And nothing at all when the palette is drawing, since there is no filter for it to
         * qualify.
         */
        @Test
        void aStrengthSaysNothingWhileThePaletteIsDrawing() {
            assertEquals(
                    List.of("NTSC"),
                    StatusBar.parts(setup().at(FilterStrength.LOW).machine()));
        }

        /**
         * Its own part rather than a qualifier on a filter's, because the sixteen lines are the
         * same sixteen lines whichever of the three is drawing -- so it says so with none of them
         * on, which is where a qualifier would have had nothing to hang from.
         */
        @Test
        void theOverscanTakesAPartOfItsOwnWhateverIsDrawing() {
            assertEquals(
                    List.of("NTSC", "Overscan"),
                    StatusBar.parts(setup().showingOverscan().machine()));

            assertEquals(
                    List.of("NTSC", "CRT filter", "Overscan"),
                    StatusBar.parts(setup()
                            .through(VideoFilter.CRT)
                            .showingOverscan()
                            .machine()));
        }

        /**
         * Named for what has gone rather than for what is there, because that is the way round it
         * was switched: the columns are drawn until somebody finds them in the way.
         */
        @Test
        void aHiddenLeftEdgeSaysWhatIsMissing() {
            assertEquals(
                    List.of("NTSC", "No left edge"),
                    StatusBar.parts(setup().withoutTheLeftEdge().machine()));

            assertEquals(
                    List.of("NTSC", "Overscan", "No left edge"),
                    StatusBar.parts(setup()
                            .showingOverscan()
                            .withoutTheLeftEdge()
                            .machine()));
        }

        /**
         * The order is the whole of the design, because the window decides where to stop reading
         * it: the console, then what makes this not the game as it shipped, then silence, then
         * what changes only what you see.
         */
        @Test
        void everythingAtOnceComesOutInTheOrderItWantsSaying() {
            assertEquals(
                    List.of("NTSC", "Overclock +100%", "2 Genie codes", "Muted",
                            "Unlimited sprites", "NTSC filter", "Overscan", "No left edge",
                            "Screenshot 3x"),
                    StatusBar.parts(setup()
                            .overclocked(Overclock.percentOf(Region.NTSC, 100))
                            .cheating(2)
                            .silent()
                            .withEverySpriteDrawn()
                            .through(VideoFilter.NTSC)
                            .showingOverscan()
                            .withoutTheLeftEdge()
                            .shotAt(ScreenScale.THREE_TIMES)
                            .machine()));
        }

        /**
         * Only what is not the ordinary case. A palette, a screen size and a fast forward speed
         * always have a value, so putting them here would fill the bar with things nobody changed
         * and push out the things somebody did -- they are in the tooltip instead.
         */
        @Test
        void aSettingThatAlwaysHasAValueIsLeftToTheTooltip() {
            assertEquals(List.of("NTSC"), StatusBar.parts(setup()
                    .colouredBy("NES Classic")
                    .keeping(0)
                    .machine()));
        }

        /**
         * The scanlines come back out as the percentage the menu offered, which is the unit
         * somebody chose in.
         */
        @Test
        void anOverclockIsNamedInThePercentageTheMenuOffers() {
            assertEquals("NTSC · Overclock +50%",
                    line(setup().overclocked(Overclock.percentOf(Region.NTSC, 50)).machine()));
        }

        /**
         * And on the other machine, where the same percentage is 156 lines rather than 131.
         */
        @Test
        void thePercentageMeansTheSameOnBothMachines() {
            assertEquals("PAL · Overclock +50%", line(setup()
                    .on(Region.PAL, RegionSetting.PAL)
                    .overclocked(Overclock.percentOf(Region.PAL, 50))
                    .machine()));
        }

        /**
         * Lines after the NMI are as much extra time as lines before it. What differs between the
         * two halves is what the game can observe, which is not what this number is about.
         */
        @Test
        void bothHalvesOfAnOverclockAreCounted() {
            assertEquals("NTSC · Overclock +58%",
                    line(setup().overclocked(new Overclock(131, 20)).machine()));
        }

        /**
         * A machine with extra lines on it must not report that it has none, which is what rounding
         * would do to the handful of lines a command line can ask for.
         */
        @Test
        void anOverclockTooSmallToRoundToAPercentIsStillNamed() {
            assertEquals("NTSC · Overclock +1%",
                    line(setup().overclocked(new Overclock(1, 0)).machine()));
        }

        @Test
        void oneCodeIsNotCodes() {
            assertEquals("NTSC · 1 Genie code", line(setup().cheating(1).machine()));
            assertEquals("NTSC · 3 Genie codes", line(setup().cheating(3).machine()));
        }

        @Test
        void silenceIsWorthSaying() {
            assertEquals("NTSC · Muted", line(setup().silent().machine()));
        }

        @Test
        void aVolumeSomebodyTurnedDownIsNamed() {
            assertEquals("NTSC · Volume 25%", line(setup().at(Volume.QUARTER).machine()));
        }

        @Test
        void fullVolumeIsNotWorthSaying() {
            assertEquals("NTSC", line(setup().at(Volume.FULL).machine()));
        }

        /**
         * A mute is the whole answer, so the bar does not go on to offer a second one.
         */
        @Test
        void aMutedMachineDoesNotAlsoNameItsVolume() {
            assertEquals("NTSC · Muted", line(setup().silent().at(Volume.QUARTER).machine()));
        }

        @Test
        void aScreenshotSizeIsNamedOnlyWhenItIsNotThePictureTheMachineDrew() {
            assertEquals("NTSC · Screenshot 3x",
                    line(setup().shotAt(ScreenScale.THREE_TIMES).machine()));

            assertEquals("NTSC", line(setup().shotAt(ScreenScale.ONE_TIMES).machine()),
                    "and not at 1x");
        }
    }

    @Nested
    @DisplayName("how much of it fits")
    class HowMuchFits {
        /**
         * Every character is ten wide and the mark is one character, which is all the arithmetic
         * these need: what is being checked is which parts are dropped and what is said about them,
         * not what a font does.
         */
        private static final int PER_CHARACTER = 10;

        private static String fitted(final List<String> parts, final int characters) {
            return StatusBar.fitted(parts, "i", characters * PER_CHARACTER,
                    text -> text.length() * PER_CHARACTER);
        }

        private static final List<String> THREE = List.of("NTSC", "Muted", "NTSC filter");

        /**
         * The mark is on the line whether or not anything was dropped, because the tooltip is a
         * longer answer than the line ever is: it names every setting, including the ones sitting
         * at their default.
         */
        @Test
        void aLineThatFitsStillSaysThereIsMore() {
            assertEquals("NTSC · Muted · NTSC filter  i", fitted(THREE, 40));
        }

        /**
         * A bar that quietly showed less than it knew would be worse than one that showed nothing,
         * so what did not fit is counted rather than merely left out.
         */
        @Test
        void whatDoesNotFitIsCounted() {
            assertEquals("NTSC · Muted · +1 i", fitted(THREE, 25));
            assertEquals("NTSC · +2 i", fitted(THREE, 15));
        }

        /**
         * From the end, so a part is only dropped once everything after it has been -- the order in
         * {@code parts} is what decides, and it decides it there rather than here.
         */
        @Test
        void theLastOneGoesFirst() {
            assertFalse(fitted(THREE, 25).contains("NTSC filter"));
            assertTrue(fitted(THREE, 25).contains("Muted"));
        }

        /**
         * A window too narrow even for the console gets it anyway, and the label's own ellipsis
         * deals with what is left.
         */
        @Test
        void theConsoleSurvivesAnyWidthAtAll() {
            assertEquals("NTSC · +2 i", fitted(THREE, 0));
        }

        @Test
        void aMachineWithNothingToSayIsBlankRatherThanAMarkOnItsOwn() {
            assertEquals("", fitted(List.of(), 40));
        }
    }

    @Nested
    @DisplayName("the tooltip")
    class TheTooltip {
        /**
         * An inventory rather than a warning: a row that said nothing when its setting was ordinary
         * would leave somebody wondering whether the setting exists, which is the question a hover
         * is being asked.
         */
        @Test
        void everySettingIsNamedWhetherOrNotItIsOrdinary() {
            var detail = StatusBar.detail(setup().machine());

            for (var row : new String[]{"Console", "Region setting", "Overclock", "Game Genie",
                    "Unlimited sprites", "Video filter", "Filter strength", "Palette",
                    "Curved glass", "Overscan", "Left edge", "Pixel shape", "Screen size",
                    "Screenshot size", "Fast forward speed", "Rewind history", "Sound", "Volume",
                    "Audio latency"}) {
                assertTrue(detail.contains(row), row + " is missing from " + detail);
            }
        }

        @Test
        void whatTheLineLeavesOutIsAllInHere() {
            var detail = StatusBar.detail(setup()
                    .withEverySpriteDrawn()
                    .shotAt(ScreenScale.THREE_TIMES)
                    .machine());

            assertTrue(detail.contains("Unlimited sprites&nbsp;&nbsp;&nbsp;</td><td>On"), detail);
            assertTrue(detail.contains("Screenshot size&nbsp;&nbsp;&nbsp;</td><td>3x"), detail);
            assertTrue(detail.contains("Screen size&nbsp;&nbsp;&nbsp;</td><td>2x"), detail);
            assertTrue(detail.contains("Rewind history&nbsp;&nbsp;&nbsp;</td><td>30 seconds"),
                    detail);
        }

        @Test
        void anOrdinaryMachineSaysOffRatherThanNothing() {
            var detail = StatusBar.detail(setup().machine());

            assertTrue(detail.contains("Overclock&nbsp;&nbsp;&nbsp;</td><td>Off"), detail);
            assertTrue(detail.contains("Game Genie&nbsp;&nbsp;&nbsp;</td><td>None"), detail);
            assertTrue(detail.contains("Unlimited sprites&nbsp;&nbsp;&nbsp;</td><td>Off"), detail);
            assertTrue(detail.contains("Sound&nbsp;&nbsp;&nbsp;</td><td>On"), detail);
        }

        /**
         * The two settings with no menu item, which makes this tooltip the one place in the window
         * either of them can be read at all.
         */
        @Test
        void theSettingsWithNoMenuItemAreReadableHere() {
            var detail = StatusBar.detail(setup().keeping(45).machine());

            assertTrue(detail.contains("Rewind history&nbsp;&nbsp;&nbsp;</td><td>45 seconds"),
                    detail);
            assertTrue(detail.contains("Audio latency&nbsp;&nbsp;&nbsp;</td><td>"
                    + AudioOutput.DEFAULT_LATENCY_MS + " ms"), detail);
        }

        /**
         * Unlike the line on screen, which stops at the mute: this is the inventory, and what the
         * volume is set to is a fact about the emulator whether or not it can be heard.
         */
        @Test
        void aMutedMachineStillSaysWhatItsVolumeIs() {
            var detail = StatusBar.detail(setup().silent().at(Volume.HALF).machine());

            assertTrue(detail.contains("Sound&nbsp;&nbsp;&nbsp;</td><td>Muted"), detail);
            assertTrue(detail.contains("Volume&nbsp;&nbsp;&nbsp;</td><td>50%"), detail);
        }

        /**
         * The palette row the other way round, and the same news the greyed-out Strength submenu
         * carries: a lookup table has no chroma trap for a strength to be the strength of.
         */
        @Test
        void anUnfilteredPictureHasNoStrengthToReport() {
            assertTrue(StatusBar.detail(setup().machine())
                    .contains("Filter strength&nbsp;&nbsp;&nbsp;</td><td>No filter"));

            assertTrue(StatusBar.detail(
                            setup().through(VideoFilter.NTSC).at(FilterStrength.LOW).machine())
                    .contains("Filter strength&nbsp;&nbsp;&nbsp;</td><td>Low"));
        }

        /**
         * The ratio rather than "On", because it is the console's number and nobody knows the PAL
         * one by heart -- and it is said the same way for both rather than as 8:7 for the one that
         * happens to simplify, so that two machines are comparable.
         */
        @Test
        void thePixelShapeIsGivenAsTheRatioTheConsoleDrewIt() {
            assertTrue(StatusBar.detail(setup().machine())
                    .contains("Pixel shape&nbsp;&nbsp;&nbsp;</td><td>Square"));

            assertTrue(StatusBar.detail(setup().withTelevisionsPixels().machine())
                    .contains("Pixel shape&nbsp;&nbsp;&nbsp;</td><td>Television, 1.143:1"));

            assertTrue(StatusBar
                    .detail(setup()
                            .on(Region.PAL, RegionSetting.PAL)
                            .withTelevisionsPixels()
                            .machine())
                    .contains("Pixel shape&nbsp;&nbsp;&nbsp;</td><td>Television, 1.386:1"));
        }

        /**
         * The same news the greyed-out Palette item carries: a decoder works its colours out of the
         * signal and never opens the table at all.
         */
        @Test
        void aTubeIsTheOneFilterWhoseGlassCanBeCurved() {
            assertTrue(StatusBar.detail(setup().machine())
                    .contains("Curved glass&nbsp;&nbsp;&nbsp;</td><td>No tube"));

            assertTrue(StatusBar.detail(setup().through(VideoFilter.NTSC).machine())
                    .contains("Curved glass&nbsp;&nbsp;&nbsp;</td><td>No tube"));

            assertTrue(StatusBar.detail(setup().through(VideoFilter.CRT).machine())
                    .contains("Curved glass&nbsp;&nbsp;&nbsp;</td><td>Off"));

            assertTrue(StatusBar
                    .detail(setup().through(VideoFilter.CRT).behindCurvedGlass().machine())
                    .contains("Curved glass&nbsp;&nbsp;&nbsp;</td><td>On"));
        }

        /**
         * And this row is never qualified the way the three above it are: the eight lines at either
         * end of the frame are the chip's, so no filter and no console makes the question moot.
         */
        @Test
        void theOverscanIsShownOrHiddenWhateverIsDrawing() {
            assertTrue(StatusBar.detail(setup().machine())
                    .contains("Overscan&nbsp;&nbsp;&nbsp;</td><td>Hidden"));

            assertTrue(StatusBar.detail(setup().showingOverscan().machine())
                    .contains("Overscan&nbsp;&nbsp;&nbsp;</td><td>Shown"));

            assertTrue(StatusBar.detail(setup().through(VideoFilter.NTSC).showingOverscan().machine())
                    .contains("Overscan&nbsp;&nbsp;&nbsp;</td><td>Shown"));
        }

        @Test
        void theLeftEdgeIsShownOrHiddenBesideIt() {
            assertTrue(StatusBar.detail(setup().machine())
                    .contains("Left edge&nbsp;&nbsp;&nbsp;</td><td>Shown"));

            assertTrue(StatusBar.detail(setup().withoutTheLeftEdge().machine())
                    .contains("Left edge&nbsp;&nbsp;&nbsp;</td><td>Hidden"));
        }

        /**
         * The tube draws through the table like everything but the decoder, so it is the decoder
         * alone that leaves the palette unread.
         */
        @Test
        void aTubeStillNamesTheTableItIsDrawnThrough() {
            assertTrue(StatusBar
                    .detail(setup().colouredBy("NES Classic").through(VideoFilter.CRT).machine())
                    .contains("NES Classic"));
        }

        @Test
        void aFilteredPictureDoesNotConsultThePalette() {
            assertTrue(StatusBar.detail(setup().colouredBy("NES Classic").machine())
                    .contains("NES Classic"), "with no filter, the table that is drawing");

            var filtered = StatusBar.detail(
                    setup().colouredBy("NES Classic").through(VideoFilter.NTSC).machine());

            assertTrue(filtered.contains("Not consulted"), filtered);
            assertFalse(filtered.contains("NES Classic"), filtered);
        }

        @Test
        void noHistoryIsOffRatherThanZeroSeconds() {
            assertTrue(StatusBar.detail(setup().keeping(0).machine())
                    .contains("Rewind history&nbsp;&nbsp;&nbsp;</td><td>Off"));
        }

        /**
         * A palette is named in a file somebody else wrote, so it is the one value in the table
         * that is not this program's own words.
         */
        @Test
        void aPaletteNamedWithMarkupInItDoesNotBreakTheTable() {
            var detail = StatusBar.detail(setup().colouredBy("Smith & <b>Wesson</b>").machine());

            assertTrue(detail.contains("Smith &amp; &lt;b>Wesson&lt;/b>"), detail);
        }
    }

    /**
     * A rate that has not been measured yet shows as nothing rather than as a zero, which is what a
     * paused machine honestly reads.
     */
    @Test
    void aRateThatIsNotKnownYetIsNotShown() {
        assertEquals("", StatusBar.describeRate(FrameRate.UNKNOWN));
        assertEquals("0 fps", StatusBar.describeRate(0));
        assertEquals("60 fps", StatusBar.describeRate(60));
    }

    /**
     * The window is packed around the picture, and a label's preferred width is the width of its
     * text -- so a bar that asked for its own width would drag the window out wider than the game
     * and letterbox it to make room for a sentence about itself.
     */
    @Test
    void theBarAsksForNoWidthOfItsOwn() {
        var bar = new StatusBar();

        bar.setMachine(setup()
                .overclocked(Overclock.percentOf(Region.NTSC, 200))
                .cheating(8)
                .silent()
                .machine());

        assertEquals(0, bar.getPreferredSize().width);
        assertTrue(bar.getPreferredSize().height > 0, "but it does want a row");
    }
}
