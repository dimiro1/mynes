package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.ui.input.KeyBindings;
import com.github.dimiro1.mynes.ui.input.KeyBindings.Button;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
            config.save(config());

            assertSame(VideoFilter.NTSC, Config.load(config()).videoFilter());
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
            config.setRegion(RegionSetting.PAL);
            config.setFastForwardSpeed(EmulationSpeed.TWO_TIMES);
            config.setMuted(true);
            config.setUnlimitedSprites(true);
            config.setOverclock(OverclockSetting.PLUS_50);
            config.setRewindSeconds(45);
            config.setRewindKey(KeyEvent.VK_BACK_SPACE);
            config.save(config());

            var text = Files.readString(config());

            assertTrue(text.contains("video.palette=" + OTHER.id()), text);
            assertTrue(text.contains("video.palette.pal=" + Palettes.PAL_ID), text);
            assertTrue(text.contains("video.scale=3"), text);
            assertTrue(text.contains("video.screenshot.scale=4"), text);
            assertTrue(text.contains("emulation.region=pal"), text);
            assertTrue(text.contains("emulation.fast-forward=2x"), text);
            assertTrue(text.contains("audio.muted=true"), text);
            assertTrue(text.contains("hacks.unlimited-sprites=true"), text);
            assertTrue(text.contains("hacks.overclock=50"), text);
            assertTrue(text.contains("rewind.seconds=45"), text);
            assertTrue(text.contains("rewind.key=VK_BACK_SPACE"), text);
            assertTrue(text.contains("controller1.a=VK_L"), text);
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
    }
}
