package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.ui.input.KeyBindings;
import com.github.dimiro1.mynes.ui.input.KeyBindings.Button;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@code ~/.mynes/config.properties} and everything kept in it.
 * <p>
 * The file is meant to be edited by hand, which means the interesting cases are all the ways a
 * hand-edited file can be wrong. None of them may stop the emulator from starting.
 * <p>
 * The other half of the job is that saving is a truncate-and-rewrite: one class owns the file, so
 * that changing one setting cannot drop another. {@link Sections} is the guard on that.
 */
class ConfigTests {
    @TempDir
    private Path directory;

    /**
     * Some palette that is not the default, for telling a choice that was carried apart from
     * everything having quietly fallen back to NESdev.
     */
    private static final NESPalette OTHER = Palettes.all().get(1);

    private Path config() {
        return directory.resolve("config.properties");
    }

    private Path write(final String contents) throws IOException {
        return Files.writeString(config(), contents);
    }

    /**
     * A game somewhere under the temporary directory, which is what makes the paths in these tests
     * absolute on whichever machine they run on: {@link RecentRom} makes every path absolute, so a
     * relative one would come back joined to the working directory and compare equal to nothing
     * anybody wrote down.
     */
    private RecentRom game(final String name) {
        return new RecentRom(directory.resolve(name), null);
    }

    @Nested
    @DisplayName("loading the status bar")
    class LoadingStatusBar {
        /**
         * One of the two entries here whose default is yes. Most of this file is something the
         * emulator does when asked, and the bar is something it does until told not to.
         */
        @Test
        void aMissingEntryLeavesTheBarOn() {
            assertTrue(Config.load(directory.resolve("not-there.properties")).statusBar());
        }

        @Test
        void falseTakesItAway() throws IOException {
            assertFalse(Config.load(write("ui.status-bar=false\n")).statusBar());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertTrue(Config.load(write("ui.status-bar=  true  \n")).statusBar());
        }

        /**
         * A value that is not {@code true} means no, the same as everywhere else in the file. It is
         * only the missing entry the two disagree about.
         */
        @Test
        void anythingThatIsNotTrueIsNo() throws IOException {
            assertFalse(Config.load(write("ui.status-bar=maybe\n")).statusBar());
        }
    }

    /**
     * The other entry whose missing value means yes, and it is worth its own case rather than
     * riding on the bar's: the two share one reader, so a fallback wired to the wrong one of them
     * would leave a player's game running while they answered an email.
     */
    @Nested
    @DisplayName("loading the background pause")
    class LoadingPauseInBackground {
        @Test
        void aMissingEntryPausesInTheBackground() {
            assertTrue(Config.load(directory.resolve("not-there.properties")).pauseInBackground());
        }

        @Test
        void falseLeavesTheGameRunning() throws IOException {
            assertFalse(Config.load(write("emulation.pause-in-background=false\n"))
                    .pauseInBackground());
        }

        @Test
        void anythingThatIsNotTrueIsNo() throws IOException {
            assertFalse(Config.load(write("emulation.pause-in-background=maybe\n"))
                    .pauseInBackground());
        }

        /**
         * The bar is the entry this one shares a reader with, so the case worth having is the one
         * where a file answers for one of them and not the other.
         */
        @Test
        void theBarIsADifferentQuestion() throws IOException {
            var config = Config.load(write("emulation.pause-in-background=false\n"));

            assertFalse(config.pauseInBackground());
            assertTrue(config.statusBar());
        }
    }

    @Nested
    @DisplayName("loading the bindings")
    class LoadingBindings {
        @Test
        void aMissingFileGivesTheDefaults() {
            var bindings = Config.load(directory.resolve("not-there.properties")).keyBindings();

            assertEquals(KeyEvent.VK_X, bindings.keyFor(Button.A));
            assertEquals(KeyEvent.VK_Z, bindings.keyFor(Button.B));
            assertEquals(KeyEvent.VK_SHIFT, bindings.keyFor(Button.SELECT));
            assertEquals(KeyEvent.VK_ENTER, bindings.keyFor(Button.START));
            assertEquals(KeyEvent.VK_UP, bindings.keyFor(Button.UP));
            assertEquals(KeyEvent.VK_DOWN, bindings.keyFor(Button.DOWN));
            assertEquals(KeyEvent.VK_LEFT, bindings.keyFor(Button.LEFT));
            assertEquals(KeyEvent.VK_RIGHT, bindings.keyFor(Button.RIGHT));
        }

        @Test
        void aMissingEntryFallsBackToItsDefault() throws IOException {
            var bindings = Config.load(write("controller1.a=VK_K\n")).keyBindings();

            assertEquals(KeyEvent.VK_K, bindings.keyFor(Button.A), "the entry that is there");
            assertEquals(KeyEvent.VK_Z, bindings.keyFor(Button.B), "and the seven that are not");
        }

        @Test
        void anUnknownKeyNameFallsBackToItsDefault() throws IOException {
            var bindings = Config.load(write("""
                    controller1.a=VK_NOT_A_KEY
                    controller1.b=VK_Q
                    """)).keyBindings();

            assertEquals(KeyEvent.VK_X, bindings.keyFor(Button.A), "the bad entry alone");
            assertEquals(KeyEvent.VK_Q, bindings.keyFor(Button.B), "the good one still counts");
        }

        @Test
        void anEmptyValueLeavesTheButtonUnbound() throws IOException {
            var bindings = Config.load(write("controller1.select=\n")).keyBindings();

            assertEquals(KeyBindings.UNBOUND, bindings.keyFor(Button.SELECT));
            assertNull(bindings.buttonFor(KeyEvent.VK_SHIFT), "the default key is free now");
        }
    }

    @Nested
    @DisplayName("loading the palette")
    class LoadingPalette {
        @Test
        void aMissingEntryGivesTheDefault() throws IOException {
            assertSame(Palettes.defaultPalette(),
                    Config.load(write("controller1.a=VK_K\n")).palette(Region.NTSC));
        }

        @Test
        void anIdNamesItsPalette() throws IOException {
            assertSame(OTHER,
                    Config.load(write("video.palette=" + OTHER.id() + "\n")).palette(Region.NTSC));
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            // Properties keeps everything after the '=', trailing spaces included.
            assertSame(OTHER,
                    Config.load(write("video.palette= " + OTHER.id() + "  \n")).palette(Region.NTSC));
        }

        @Test
        void anUnknownIdFallsBackToTheDefault() throws IOException {
            assertSame(Palettes.defaultPalette(),
                    Config.load(write("video.palette=not-a-palette\n")).palette(Region.NTSC));
        }

        @Test
        void aPALMachineHasAPaletteOfItsOwn() throws IOException {
            // Two entries, because the two PPUs do not generate the same colours. A choice made
            // for one machine must not follow the emulator onto the other.
            var config = Config.load(write(
                    "video.palette=" + OTHER.id() + "\nvideo.palette.pal=nesdev\n"));

            assertSame(OTHER, config.palette(Region.NTSC));
            assertSame(Palettes.NESDEV, config.palette(Region.PAL));
        }

        @Test
        void aMissingPALEntryGivesThePALPalette() throws IOException {
            assertSame(Palettes.defaultPalette(Region.PAL),
                    Config.load(write("video.palette=nesdev\n")).palette(Region.PAL));
        }
    }

    @Nested
    @DisplayName("loading the video filter")
    class LoadingVideoFilter {
        @Test
        void aMissingEntryLeavesThePaletteToDoIt() throws IOException {
            assertSame(VideoFilter.NONE,
                    Config.load(write("video.palette=nesdev\n")).videoFilter());
        }

        @Test
        void anIdNamesItsFilter() throws IOException {
            assertSame(VideoFilter.NTSC,
                    Config.load(write("video.filter=ntsc\n")).videoFilter());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertSame(VideoFilter.NTSC,
                    Config.load(write("video.filter= ntsc \n")).videoFilter());
        }

        @Test
        void anUnknownIdFallsBackToThePalette() throws IOException {
            assertSame(VideoFilter.NONE,
                    Config.load(write("video.filter=composite\n")).videoFilter());
        }

        @Test
        void aMissingStrengthIsTheDefaultOne() throws IOException {
            assertSame(FilterStrength.defaultStrength(),
                    Config.load(write("video.filter=ntsc\n")).filterStrength());
        }

        @Test
        void anIdNamesItsStrength() throws IOException {
            assertSame(FilterStrength.LOW,
                    Config.load(write("video.filter.strength=low\n")).filterStrength());
        }

        /**
         * Remembered rather than dropped, the way the filter itself is on a PAL machine: it is a
         * preference about the decoder, and the palette drawing today does not unsay it.
         */
        @Test
        void aStrengthIsKeptEvenWhileThePaletteIsDrawing() throws IOException {
            var config = Config.load(
                    write("video.filter=none\nvideo.filter.strength=strong\n"));

            assertSame(VideoFilter.NONE, config.videoFilter());
            assertSame(FilterStrength.STRONG, config.filterStrength());
        }

        @Test
        void anUnknownStrengthFallsBackToTheDefault() throws IOException {
            assertSame(FilterStrength.defaultStrength(),
                    Config.load(write("video.filter.strength=sharpest\n")).filterStrength());
        }
    }

    @Nested
    @DisplayName("loading the screen size")
    class LoadingScreenScale {
        @Test
        void aMissingEntryGivesTheDefault() throws IOException {
            assertSame(ScreenScale.defaultScale(),
                    Config.load(write("video.palette=nesdev\n")).screenScale());
        }

        @Test
        void anIdNamesItsSize() throws IOException {
            assertSame(ScreenScale.THREE_TIMES, Config.load(write("video.scale=3\n")).screenScale());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertSame(ScreenScale.FOUR_TIMES, Config.load(write("video.scale= 4 \n")).screenScale());
        }

        @Test
        void anUnknownIdFallsBackToTheDefault() throws IOException {
            // A window with no size is not a thing to start up with.
            assertSame(ScreenScale.defaultScale(), Config.load(write("video.scale=12\n")).screenScale());
        }
    }

    @Nested
    @DisplayName("loading the screenshot size")
    class LoadingScreenshotScale {
        @Test
        void aMissingEntryGivesThePictureTheMachineDrew() throws IOException {
            // 1x rather than the window's 2x, and the two settings are read separately: somebody who
            // plays at 4x has said nothing about what a file should be.
            var config = Config.load(write("video.scale=4\n"));

            assertSame(ScreenScale.defaultScreenshotScale(), config.screenshotScale());
            assertSame(ScreenScale.FOUR_TIMES, config.screenScale());
        }

        @Test
        void anIdNamesItsSize() throws IOException {
            assertSame(ScreenScale.THREE_TIMES,
                    Config.load(write("video.screenshot.scale=3\n")).screenshotScale());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertSame(ScreenScale.TWO_TIMES,
                    Config.load(write("video.screenshot.scale= 2 \n")).screenshotScale());
        }

        @Test
        void anUnknownIdFallsBackToTheSameSizeAMissingOneDoes() throws IOException {
            // Not to the window's default, which is what makes this its own fallback rather than
            // byId's: a botched entry and no entry have to mean the same thing.
            assertSame(ScreenScale.defaultScreenshotScale(),
                    Config.load(write("video.screenshot.scale=12\n")).screenshotScale());
        }
    }

    @Nested
    @DisplayName("loading the region")
    class LoadingRegion {
        @Test
        void aMissingEntryGivesTheDefault() throws IOException {
            assertSame(RegionSetting.AUTOMATIC,
                    Config.load(write("video.palette=nesdev\n")).region());
        }

        @Test
        void anIdNamesItsRegion() throws IOException {
            assertSame(RegionSetting.PAL, Config.load(write("emulation.region=pal\n")).region());
            assertSame(RegionSetting.NTSC, Config.load(write("emulation.region=ntsc\n")).region());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertSame(RegionSetting.PAL, Config.load(write("emulation.region= pal \n")).region());
        }

        @Test
        void anUnknownIdFallsBackToTheDefault() throws IOException {
            // Believing the cartridge is the mildest way to be wrong: it is the answer somebody
            // who had never opened this file would have got anyway.
            assertSame(RegionSetting.AUTOMATIC,
                    Config.load(write("emulation.region=secam\n")).region());
        }
    }

    @Nested
    @DisplayName("loading the fast forward speed")
    class LoadingFastForward {
        @Test
        void aMissingEntryGivesTheDefault() throws IOException {
            assertSame(EmulationSpeed.defaultFastForward(),
                    Config.load(write("video.palette=nesdev\n")).fastForwardSpeed());
        }

        @Test
        void anIdNamesItsSpeed() throws IOException {
            assertSame(EmulationSpeed.EIGHT_TIMES,
                    Config.load(write("emulation.fast-forward=8x\n")).fastForwardSpeed());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertSame(EmulationSpeed.UNLIMITED,
                    Config.load(write("emulation.fast-forward= unlimited \n")).fastForwardSpeed());
        }

        @Test
        void anUnknownIdFallsBackToTheDefault() throws IOException {
            assertSame(EmulationSpeed.defaultFastForward(),
                    Config.load(write("emulation.fast-forward=ludicrous\n")).fastForwardSpeed());
        }
    }

    @Nested
    @DisplayName("loading the mute setting")
    class LoadingMute {
        @Test
        void aMissingEntryLeavesTheSoundOn() throws IOException {
            assertFalse(Config.load(write("video.palette=nesdev\n")).muted());
        }

        @Test
        void trueMeansMuted() throws IOException {
            assertTrue(Config.load(write("audio.muted=true\n")).muted());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertTrue(Config.load(write("audio.muted= true \n")).muted());
        }

        @Test
        void anythingElseLeavesTheSoundOn() throws IOException {
            // No warning and no fallback to argue about: somebody who did not write "true" wants
            // to hear the game, which is what a missing entry means too.
            assertFalse(Config.load(write("audio.muted=yes\n")).muted());
        }
    }

    @Nested
    @DisplayName("loading the volume")
    class LoadingVolume {
        @Test
        void aMissingEntryIsFullVolume() throws IOException {
            assertSame(Volume.defaultVolume(),
                    Config.load(write("video.palette=nesdev\n")).volume());
        }

        @Test
        void aStepIsTakenAsWritten() throws IOException {
            assertSame(Volume.QUARTER, Config.load(write("audio.volume=25\n")).volume());
        }

        @Test
        void aStepThatIsNotOneOfTheFiveFallsBackToTheDefault() throws IOException {
            assertSame(Volume.defaultVolume(), Config.load(write("audio.volume=33\n")).volume());
        }
    }

    @Nested
    @DisplayName("loading the audio latency")
    class LoadingLatency {
        @Test
        void aMissingEntryIsTheDefault() throws IOException {
            assertEquals(AudioOutput.DEFAULT_LATENCY_MS,
                    Config.load(write("video.palette=nesdev\n")).audioLatencyMs());
        }

        @Test
        void aNumberIsTakenAsMilliseconds() throws IOException {
            assertEquals(35, Config.load(write("audio.latency-ms=35\n")).audioLatencyMs());
        }

        /**
         * The two ways of being wrong, answered differently: a word says nothing about what was
         * wanted and falls back, and a number out of range is a wish that can be granted
         * approximately.
         */
        @Test
        void somethingThatIsNotANumberFallsBackAndOneOutOfRangeIsClamped() throws IOException {
            assertEquals(AudioOutput.DEFAULT_LATENCY_MS,
                    Config.load(write("audio.latency-ms=low\n")).audioLatencyMs());

            assertEquals(AudioOutput.MAX_LATENCY_MS,
                    Config.load(write("audio.latency-ms=5000\n")).audioLatencyMs());

            assertEquals(AudioOutput.MIN_LATENCY_MS,
                    Config.load(write("audio.latency-ms=0\n")).audioLatencyMs());
        }
    }

    @Nested
    @DisplayName("loading the hacks")
    class LoadingHacks {
        @Test
        void aMissingEntryLeavesTheConsoleAsItWas() throws IOException {
            assertFalse(Config.load(write("video.palette=nesdev\n")).unlimitedSprites());
        }

        @Test
        void trueMeansTheSpriteLimitIsLifted() throws IOException {
            assertTrue(Config.load(write("hacks.unlimited-sprites=true\n")).unlimitedSprites());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertTrue(Config.load(write("hacks.unlimited-sprites= true \n")).unlimitedSprites());
        }

        @Test
        void anythingElseLeavesItOff() throws IOException {
            assertFalse(Config.load(write("hacks.unlimited-sprites=yes\n")).unlimitedSprites());
        }

        @Test
        void aMissingOverclockLeavesTheFrameAsLongAsItWas() throws IOException {
            assertEquals(
                    OverclockSetting.OFF,
                    Config.load(write("video.palette=nesdev\n")).overclock());
        }

        @Test
        void aPercentageNamesItsPreset() throws IOException {
            assertEquals(
                    OverclockSetting.PLUS_50,
                    Config.load(write("hacks.overclock=50\n")).overclock());
            assertEquals(
                    OverclockSetting.PLUS_200,
                    Config.load(write("hacks.overclock= 200 \n")).overclock());
        }

        @Test
        void aPercentageNobodyOffersFallsBackToOff() throws IOException {
            // The mildest way to be wrong: the machine the cartridge was written for, which is what
            // somebody who had not thought about it would have got anyway.
            assertEquals(
                    OverclockSetting.OFF,
                    Config.load(write("hacks.overclock=lots\n")).overclock());
            assertEquals(
                    OverclockSetting.OFF,
                    Config.load(write("hacks.overclock=33\n")).overclock());
        }

        @Test
        void anOverclockSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setOverclock(OverclockSetting.PLUS_100);
            config.save(config());

            assertEquals(OverclockSetting.PLUS_100, Config.load(config()).overclock());
        }
    }

    @Nested
    @DisplayName("loading the rewind settings")
    class LoadingRewind {
        @Test
        void aMissingEntryGivesThirtySecondsOnBackspace() throws IOException {
            var config = Config.load(write("video.palette=nesdev\n"));

            assertEquals(30, config.rewindSeconds());
            assertEquals(KeyEvent.VK_BACK_SPACE, config.rewindKey());
        }

        @Test
        void aNumberNamesItsSeconds() throws IOException {
            assertEquals(90, Config.load(write("rewind.seconds=90\n")).rewindSeconds());
        }

        @Test
        void surroundingSpaceIsIgnored() throws IOException {
            assertEquals(5, Config.load(write("rewind.seconds= 5 \n")).rewindSeconds());
            assertEquals(KeyEvent.VK_HOME, Config.load(write("rewind.key= VK_HOME \n")).rewindKey());
        }

        @Test
        void zeroSwitchesTheWholeThingOff() throws IOException {
            assertEquals(0, Config.load(write("rewind.seconds=0\n")).rewindSeconds());
        }

        /**
         * A wish that can be granted approximately is granted approximately. The ceiling is there
         * because an extra nought is a plausible typo and an hour of save states is not something
         * to find out about by running out of heap.
         */
        @Test
        void anImpossibleNumberOfSecondsIsClamped() throws IOException {
            assertEquals(300, Config.load(write("rewind.seconds=3000\n")).rewindSeconds());
            assertEquals(0, Config.load(write("rewind.seconds=-5\n")).rewindSeconds());
        }

        /**
         * Unlike a number out of range, which says what was wanted. This says nothing, so it lands
         * where every other unreadable entry in the file lands.
         */
        @Test
        void somethingThatIsNotANumberFallsBackToTheDefault() throws IOException {
            assertEquals(30, Config.load(write("rewind.seconds=lots\n")).rewindSeconds());
        }

        @Test
        void anEmptyKeyLeavesRewindWithNoKeyOnIt() throws IOException {
            assertEquals(KeyBindings.UNBOUND, Config.load(write("rewind.key=\n")).rewindKey());
        }

        @Test
        void anUnknownKeyNameFallsBackToBackspace() throws IOException {
            // VK_BACKSPACE without the underscore is the likely typo, and it is not a constant.
            assertEquals(KeyEvent.VK_BACK_SPACE,
                    Config.load(write("rewind.key=VK_BACKSPACE\n")).rewindKey());
        }
    }

    @Nested
    @DisplayName("loading the recent games")
    class LoadingRecent {
        @Test
        void aMissingFileHasOpenedNothing() {
            assertEquals(List.of(),
                    Config.load(directory.resolve("not-there.properties")).recentRoms());
        }

        @Test
        void theyComeBackInTheOrderTheyAreNumbered() throws IOException {
            var loaded = Config.load(write("""
                    recent.1=/roms/a.nes
                    recent.2=/roms/b.nes
                    """)).recentRoms();

            assertEquals(
                    List.of(new RecentRom(Path.of("/roms/a.nes"), null),
                            new RecentRom(Path.of("/roms/b.nes"), null)),
                    loaded);
        }

        /**
         * Deleting the line is how a game is taken off the menu by hand, and renumbering the rest
         * is not something anybody should have to do afterwards.
         */
        @Test
        void aDeletedLineIsSteppedOverRatherThanEndingTheList() throws IOException {
            var loaded = Config.load(write("""
                    recent.1=/roms/a.nes
                    recent.3=/roms/c.nes
                    """)).recentRoms();

            assertEquals(
                    List.of(new RecentRom(Path.of("/roms/a.nes"), null),
                            new RecentRom(Path.of("/roms/c.nes"), null)),
                    loaded);
        }

        @Test
        void anEmptyEntryIsNotAGame() throws IOException {
            assertEquals(List.of(), Config.load(write("recent.1=\n")).recentRoms());
        }

        @Test
        void aGameNamedTwiceIsListedOnce() throws IOException {
            var loaded = Config.load(write("""
                    recent.1=/roms/a.nes
                    recent.2=/roms/a.nes
                    """)).recentRoms();

            assertEquals(List.of(new RecentRom(Path.of("/roms/a.nes"), null)), loaded);
        }

        @Test
        void aPatchIsRememberedWithItsCartridge() throws IOException {
            var loaded = Config.load(write("""
                    recent.1=/roms/a.nes
                    recent.1.patch=/hacks/a.ips
                    """)).recentRoms();

            assertEquals(
                    List.of(new RecentRom(Path.of("/roms/a.nes"), Path.of("/hacks/a.ips"))),
                    loaded);
        }

        /**
         * A patch is something applied to a cartridge, so an entry naming one and no cartridge is
         * not half an entry -- it is nothing at all.
         */
        @Test
        void aPatchWithNoCartridgeIsNoGame() throws IOException {
            assertEquals(List.of(), Config.load(write("recent.1.patch=/hacks/a.ips\n")).recentRoms());
        }

        /**
         * The one entry in the file that can name something the platform will not even accept as a
         * name. It costs its own line, the way every other unreadable entry costs its own setting.
         */
        @Test
        void somethingThatIsNotAPathCostsOnlyItsOwnEntry() throws IOException {
            var loaded = Config.load(write("""
                    recent.1=\\u0000
                    recent.2=/roms/b.nes
                    """)).recentRoms();

            assertEquals(List.of(new RecentRom(Path.of("/roms/b.nes"), null)), loaded);
        }

        @Test
        void tenIsAsFarAsItReads() throws IOException {
            var text = new StringBuilder();

            for (var n = 1; n <= 14; n++) {
                text.append("recent.").append(n).append("=/roms/").append(n).append(".nes\n");
            }

            assertEquals(10, Config.load(write(text.toString())).recentRoms().size());
        }
    }

    @Nested
    @DisplayName("the recent games")
    class Recent {
        @Test
        void theNewestIsFirst() {
            var config = Config.load(config());
            config.addRecentRom(game("a.nes"));
            config.addRecentRom(game("b.nes"));

            assertEquals(List.of(game("b.nes"), game("a.nes")), config.recentRoms());
        }

        /**
         * The whole behaviour of the menu: the same few games are opened over and over, and a list
         * that let each of them in ten times would be a list holding one game.
         */
        @Test
        void openingAGameAgainMovesItUpRatherThanAddingIt() {
            var config = Config.load(config());
            config.addRecentRom(game("a.nes"));
            config.addRecentRom(game("b.nes"));
            config.addRecentRom(game("a.nes"));

            assertEquals(List.of(game("a.nes"), game("b.nes")), config.recentRoms());
        }

        /**
         * Because a hack is a different game from the cartridge it was cut against, which is the
         * same thing the save states say when they are named after the patch.
         */
        @Test
        void aPatchedGameIsNotTheSameGameUnpatched() {
            var patched = new RecentRom(directory.resolve("a.nes"), directory.resolve("a.ips"));

            var config = Config.load(config());
            config.addRecentRom(game("a.nes"));
            config.addRecentRom(patched);

            assertEquals(List.of(patched, game("a.nes")), config.recentRoms());
        }

        @Test
        void theOldestFallsOffTheEndAtTen() {
            var config = Config.load(config());

            for (var n = 1; n <= 14; n++) {
                config.addRecentRom(game(n + ".nes"));
            }

            var recent = config.recentRoms();

            assertEquals(10, recent.size());
            assertEquals(game("14.nes"), recent.getFirst());
            assertEquals(game("5.nes"), recent.getLast());
        }

        @Test
        void clearingLeavesNothing() {
            var config = Config.load(config());
            config.addRecentRom(game("a.nes"));
            config.clearRecentRoms();

            assertEquals(List.of(), config.recentRoms());
        }
    }

    @Nested
    @DisplayName("saving")
    class Saving {
        @Test
        void theBindingsSurviveTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setKeyBindings(KeyBindings.defaults()
                    .with(Button.A, KeyEvent.VK_L)
                    .with(Button.SELECT, KeyBindings.UNBOUND));
            config.save(config());

            var loaded = Config.load(config()).keyBindings();

            for (var button : Button.values()) {
                assertEquals(config.keyBindings().keyFor(button), loaded.keyFor(button),
                        button.label());
            }
        }

        @Test
        void thePaletteSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setPalette(Region.NTSC, OTHER);
            config.save(config());

            assertSame(OTHER, Config.load(config()).palette(Region.NTSC));
        }

        @Test
        void theVideoFilterSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setVideoFilter(VideoFilter.NTSC);
            config.setFilterStrength(FilterStrength.LOW);
            config.save(config());

            var reloaded = Config.load(config());

            assertSame(VideoFilter.NTSC, reloaded.videoFilter());
            assertSame(FilterStrength.LOW, reloaded.filterStrength());
        }

        @Test
        void bothPalettesSurviveTheRoundTrip() throws IOException {
            // Separately, which is the whole point of there being two: choosing a palette for
            // European games must not quietly become the choice for all of them.
            var config = Config.load(config());
            config.setPalette(Region.NTSC, OTHER);
            config.setPalette(Region.PAL, Palettes.NESDEV);
            config.save(config());

            var loaded = Config.load(config());

            assertSame(OTHER, loaded.palette(Region.NTSC));
            assertSame(Palettes.NESDEV, loaded.palette(Region.PAL));
        }

        @Test
        void theScreenSizeSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setScreenScale(ScreenScale.FOUR_TIMES);
            config.save(config());

            assertSame(ScreenScale.FOUR_TIMES, Config.load(config()).screenScale());
        }

        @Test
        void theScreenshotSizeSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setScreenshotScale(ScreenScale.THREE_TIMES);
            config.save(config());

            assertSame(ScreenScale.THREE_TIMES, Config.load(config()).screenshotScale());
        }

        @Test
        void theRegionSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setRegion(RegionSetting.PAL);
            config.save(config());

            assertSame(RegionSetting.PAL, Config.load(config()).region());
        }

        @Test
        void theFastForwardSpeedSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setFastForwardSpeed(EmulationSpeed.UNLIMITED);
            config.save(config());

            assertSame(EmulationSpeed.UNLIMITED, Config.load(config()).fastForwardSpeed());
        }

        @Test
        void theMuteSettingSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setMuted(true);
            config.save(config());

            assertTrue(Config.load(config()).muted());
        }

        @Test
        void theVolumeSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setVolume(Volume.HALF);
            config.save(config());

            assertSame(Volume.HALF, Config.load(config()).volume());
        }

        /**
         * There is no setter, so the round trip is what has to carry it: a file written by the menus
         * that dropped the entry would take somebody's latency back to the default the next time
         * they picked a palette.
         */
        @Test
        void theAudioLatencySurvivesTheRoundTrip() throws IOException {
            Files.writeString(config(), "audio.latency-ms=120\n");

            var config = Config.load(config());
            config.save(config());

            assertEquals(120, Config.load(config()).audioLatencyMs());
        }

        @Test
        void theSpriteLimitHackSurvivesTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setUnlimitedSprites(true);
            config.save(config());

            assertTrue(Config.load(config()).unlimitedSprites());
        }

        @Test
        void theRewindSettingsSurviveTheRoundTrip() throws IOException {
            var config = Config.load(config());
            config.setRewindSeconds(120);
            config.setRewindKey(KeyEvent.VK_HOME);
            config.save(config());

            var loaded = Config.load(config());

            assertEquals(120, loaded.rewindSeconds());
            assertEquals(KeyEvent.VK_HOME, loaded.rewindKey());
        }

        @Test
        void aRewindKeyOfNothingReadsBackAsNothing() throws IOException {
            // Rather than as the default, which is the trap an empty value falls into if the file
            // simply leaves the entry out.
            var config = Config.load(config());
            config.setRewindKey(KeyBindings.UNBOUND);
            config.save(config());

            assertEquals(KeyBindings.UNBOUND, Config.load(config()).rewindKey());
        }

        @Test
        void theRecentGamesSurviveTheRoundTrip() throws IOException {
            var patched = new RecentRom(directory.resolve("a.nes"), directory.resolve("a.ips"));

            var config = Config.load(config());
            config.addRecentRom(game("b.nes"));
            config.addRecentRom(patched);
            config.save(config());

            assertEquals(List.of(patched, game("b.nes")), Config.load(config()).recentRoms());
        }

        /**
         * Properties reads a backslash as an escape character, so a Windows path written straight
         * out comes back as C:romsgame.nes -- a path that is not the game's and is not anybody's.
         */
        @Test
        void aBackslashInAPathSurvivesTheRoundTrip() throws IOException {
            var game = new RecentRom(directory.resolve("C:\\roms\\game.nes"), null);

            var config = Config.load(config());
            config.addRecentRom(game);
            config.save(config());

            assertEquals(List.of(game), Config.load(config()).recentRoms());
        }

        /**
         * The file is written as Latin-1, which is what Properties reads. A Japanese filename does
         * not fit in it, so without the escaping this does not come back wrong -- the write throws
         * and no file is left behind at all, so it takes every other setting in the save with it.
         */
        @Test
        void aPathOutsideLatinOneSurvivesTheRoundTrip() throws IOException {
            var game = game("\u30C9\u30E9\u3048\u3082\u3093.nes");

            var config = Config.load(config());
            config.addRecentRom(game);
            config.save(config());

            assertEquals(List.of(game), Config.load(config()).recentRoms());
        }

        /**
         * A filename may hold one on every platform this runs on, and a value split over two lines
         * is a file that will not read back.
         */
        @Test
        void aTabInAPathSurvivesTheRoundTrip() throws IOException {
            var game = game("two\twords.nes");

            var config = Config.load(config());
            config.addRecentRom(game);
            config.save(config());

            assertEquals(List.of(game), Config.load(config()).recentRoms());
        }

        @Test
        void createsTheDirectory() throws IOException {
            var path = directory.resolve("nested").resolve("config.properties");

            Config.load(path).save(path);

            assertTrue(Files.isRegularFile(path));
        }

        @Test
        void writesKeyNamesRatherThanNumbers() throws IOException {
            Config.load(config()).save(config());

            var text = Files.readString(config());

            // The whole point of the format: the file can be edited without looking up that the
            // left arrow is 37.
            assertTrue(text.contains("controller1.a=VK_X"), text);
            assertTrue(text.contains("controller1.left=VK_LEFT"), text);
        }
    }

    @Nested
    @DisplayName("one file, one owner")
    class Sections {
        @Test
        void aSaveWritesEverySection() throws IOException {
            var config = Config.load(config());
            config.setKeyBindings(KeyBindings.defaults().with(Button.A, KeyEvent.VK_L));
            config.setPalette(Region.NTSC, OTHER);
            config.setScreenScale(ScreenScale.THREE_TIMES);
            config.setScreenshotScale(ScreenScale.FOUR_TIMES);
            config.setStatusBar(false);
            config.setRegion(RegionSetting.PAL);
            config.setFastForwardSpeed(EmulationSpeed.TWO_TIMES);
            config.setPauseInBackground(false);
            config.setMuted(true);
            config.setVolume(Volume.TENTH);
            config.setUnlimitedSprites(true);
            config.setOverclock(OverclockSetting.PLUS_50);
            config.setRewindSeconds(45);
            config.setRewindKey(KeyEvent.VK_BACK_SPACE);
            config.addRecentRom(game("a.nes"));
            config.save(config());

            var text = Files.readString(config());

            assertTrue(text.contains("video.palette=" + OTHER.id()), text);
            assertTrue(text.contains("video.palette.pal=" + Palettes.PAL_ID), text);
            assertTrue(text.contains("video.scale=3"), text);
            assertTrue(text.contains("video.screenshot.scale=4"), text);
            assertTrue(text.contains("ui.status-bar=false"), text);
            assertTrue(text.contains("emulation.region=pal"), text);
            assertTrue(text.contains("emulation.fast-forward=2x"), text);
            assertTrue(text.contains("emulation.pause-in-background=false"), text);
            assertTrue(text.contains("audio.muted=true"), text);
            assertTrue(text.contains("audio.volume=10"), text);
            assertTrue(text.contains("audio.latency-ms=" + AudioOutput.DEFAULT_LATENCY_MS), text);
            assertTrue(text.contains("hacks.unlimited-sprites=true"), text);
            assertTrue(text.contains("hacks.overclock=50"), text);
            assertTrue(text.contains("rewind.seconds=45"), text);
            assertTrue(text.contains("rewind.key=VK_BACK_SPACE"), text);
            assertTrue(text.contains("controller1.a=VK_L"), text);
            assertTrue(text.contains("recent.1=" + directory.resolve("a.nes")), text);
        }

        @Test
        void changingOneSettingKeepsTheOther() throws IOException {
            // Rebind a key, the way Settings > Controller... would.
            var first = Config.load(config());
            first.setKeyBindings(first.keyBindings().with(Button.A, KeyEvent.VK_L));
            first.save(config());

            // Then pick a palette in a later run, the way Settings > Palette... would. Saving
            // truncates the file, so a dialog that wrote only its own half would drop the binding
            // above -- which is the bug this class exists to keep from coming back.
            var second = Config.load(config());
            second.setPalette(Region.NTSC, OTHER);
            second.save(config());

            var reloaded = Config.load(config());

            assertSame(OTHER, reloaded.palette(Region.NTSC), "the palette just picked");
            assertEquals(KeyEvent.VK_L, reloaded.keyBindings().keyFor(Button.A),
                    "and the binding from the run before");
        }

        /**
         * The list is written by opening a game rather than by a dialog, so it is the one section
         * that is rewritten without anybody meaning to touch the file -- which makes it the likeliest
         * to take the rest of it away.
         */
        @Test
        void openingAGameKeepsTheSettings() throws IOException {
            var first = Config.load(config());
            first.setPalette(Region.NTSC, OTHER);
            first.save(config());

            var second = Config.load(config());
            second.addRecentRom(game("a.nes"));
            second.save(config());

            var reloaded = Config.load(config());

            assertEquals(List.of(game("a.nes")), reloaded.recentRoms(), "the game just opened");
            assertSame(OTHER, reloaded.palette(Region.NTSC), "and the palette from the run before");
        }
    }
}
