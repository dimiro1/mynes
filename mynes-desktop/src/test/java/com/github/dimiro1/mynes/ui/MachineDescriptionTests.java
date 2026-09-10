package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The words the window puts on itself, and the one rule that holds the three places together: they
 * are all reading the same sentence.
 */
class MachineDescriptionTests {

    /**
     * A machine playing a cartridge with nothing unusual about it.
     */
    private static MachineDescription playing() {
        return new MachineDescription(
                true, false, false, false, false, false,
                60, Region.NTSC, Region.NTSC,
                "Super Mario Bros.nes", 0, 32 * 1024, 8 * 1024, null);
    }

    @Test
    void withNothingLoadedTheTitleIsJustTheProgramsName() {
        var nothing = new MachineDescription(
                false, false, false, false, false, false,
                FrameRate.UNKNOWN, Region.NTSC, null, null, 0, 0, 0, null);

        assertEquals("MyNES", nothing.title());
        assertEquals("", nothing.state(), "there is nothing being done to a machine that is not there");
        assertEquals("No machine  ·  NTSC", nothing.dashboard());
    }

    @Test
    void aRunningGameIsNamedInTheTitleWithNoStateAfterIt() {
        assertEquals("MyNES - Super Mario Bros.nes", playing().title());
    }

    @Test
    void theDashboardCarriesTheRateTheRegionAndTheBoard() {
        assertEquals(
                "Running  ·  60 fps  ·  NTSC  ·  Super Mario Bros.nes  (mapper 0, 32K+8K)",
                playing().dashboard());
    }

    /**
     * The one thing that separates the two regions in the window: a PAL game running fast because
     * the header said nothing is invisible until something says which way to reach.
     */
    @Test
    void onlyAPalMachineIsNamedInTheTitle() {
        var pal = new MachineDescription(
                true, false, false, false, false, false,
                50, Region.PAL, Region.PAL, "Tetris.nes", 1, 32 * 1024, 0, null);

        assertTrue(pal.title().contains("(PAL)"), pal.title());
        assertTrue(!playing().title().contains("NTSC"), "the usual machine is not worth the words");
    }

    /**
     * A patched game is that game plus a patch, so the cartridge is still named first -- a title
     * naming only the hack would leave nothing to say which ROM it was applied to.
     */
    @Test
    void aPatchedGameNamesTheCartridgeAndThenThePatch() {
        var hacked = new MachineDescription(
                true, false, false, false, false, false,
                60, Region.NTSC, Region.NTSC, "smb.nes", 0, 32 * 1024, 8 * 1024, "hack.ips");

        assertEquals("MyNES - smb.nes + hack.ips", hacked.title());
    }

    /**
     * The order is the order of surprise: what the machine is doing to a file beats how fast it is
     * going, and a machine that is not running is not running fast.
     */
    @Test
    void pauseWinsOverEverythingAndAFileWinsOverTheSpeed() {
        assertEquals("Paused", busy(true, true, true, true, true).state());
        assertEquals("Playback", busy(false, true, true, true, true).state());
        assertEquals("Recording", busy(false, false, true, true, true).state());
        assertEquals("Tracing", busy(false, false, false, true, true).state());
        assertEquals("Fast forward", busy(false, false, false, false, true).state());
        assertEquals("", busy(false, false, false, false, false).state());
    }

    /**
     * The whole reason this is one value rather than three methods on the window: the status bar
     * shows the sentence as it is and the title bar puts it in brackets in lower case, so they can
     * never come to describe the same machine differently.
     */
    @Test
    void theTitleAndTheStatusBarAreReadingTheSameSentence() {
        var recording = busy(false, false, true, false, false);

        assertEquals("Recording", recording.state());
        assertTrue(recording.title().endsWith(" (recording)"), recording.title());
    }

    /**
     * Because it is already the first word on the line, and saying it twice would read as two facts
     * that happen to agree rather than as one.
     */
    @Test
    void theDashboardDoesNotSayPausedTwice() {
        var paused = busy(true, false, false, false, false);

        assertEquals("Paused", paused.state());
        assertEquals(1, paused.dashboard().split("Paused", -1).length - 1, paused.dashboard());
    }

    private static MachineDescription busy(
            final boolean paused,
            final boolean playing,
            final boolean recording,
            final boolean tracing,
            final boolean fast
    ) {
        return new MachineDescription(
                true, paused, playing, recording, tracing, fast,
                60, Region.NTSC, Region.NTSC, "game.nes", 4, 128 * 1024, 128 * 1024, null);
    }
}
