package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Region;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Locale;

/**
 * What the window says about itself, in the three places it says anything: the title bar, the status
 * bar's activity, and the control panel's first line.
 * <p>
 * <b>One sentence for three places.</b> {@link #state()} is written once and the three callers each
 * dress it differently -- the status bar shows it as it is, the title bar puts it in brackets in
 * lower case, and the dashboard drops it when it would only repeat the word already there. That is
 * what keeps the window from describing the same machine three ways, which is what happened when
 * each of them worked it out for itself.
 * <p>
 * <b>None of this is the machine's.</b> Whether the loop is paused, what the frame rate has
 * measured, which cartridge somebody put in, whether a movie is going -- not one of them can be read
 * off a NES, which is why the control panel is handed its line written rather than working it out.
 * Whichever frame the machine has actually reached is on the line below, with the rest of what the
 * machine is doing.
 * <p>
 * A record because it is a description rather than a describer: it holds no reference to the runner,
 * the cartridge or the machine, so one taken now and read later says what was true when it was
 * taken.
 *
 * @param running        whether there is a machine at all, which is a different question from
 *                       whether it is going anywhere.
 * @param paused         whether the loop is stopped. Wins over everything below it: a machine that
 *                       is not running is not running fast.
 * @param playingMovie   whether a movie is being replayed.
 * @param recordingMovie whether one is being written.
 * @param tracing        whether every instruction is going to a file.
 * @param fastForward    whether the loop is being paced at anything other than 1x.
 * @param frameRate      the measured rate, or {@link FrameRate#UNKNOWN} when nothing has measured
 *                       one yet.
 * @param region         which console the <em>window</em> says it is running, which is on the bar
 *                       before a cartridge is loaded too: it is what the next one will run under.
 * @param machineRegion  which console is actually running, or null when none is. Not always
 *                       {@link #region}, which is why both are here.
 * @param cartridge      the cartridge's file name, or null when nothing is loaded.
 * @param mapperNumber   the board the header asked for.
 * @param prgBytes       how much program ROM is on it.
 * @param chrBytes       how much character ROM is on it.
 * @param patch          the IPS patch applied on the way in, or null for an unpatched cartridge.
 */
record MachineDescription(
        boolean running,
        boolean paused,
        boolean playingMovie,
        boolean recordingMovie,
        boolean tracing,
        boolean fastForward,
        int frameRate,
        Region region,
        @Nullable Region machineRegion,
        @Nullable String cartridge,
        int mapperNumber,
        int prgBytes,
        int chrBytes,
        @Nullable String patch) {

    /**
     * What the machine is doing, when it is doing anything other than simply running.
     * <p>
     * The order is the order of surprise rather than of importance. A movie above a trace and both
     * above the speed: what the machine is doing to a <em>file</em> is a bigger surprise than how
     * fast it is going, and a recording or a trace somebody started an hour ago is the state most
     * worth being reminded of on every glance at the window.
     */
    String state() {
        if (!running) {
            return "";
        }

        if (paused) {
            return "Paused";
        }

        if (playingMovie) {
            return "Playback";
        }

        if (recordingMovie) {
            return "Recording";
        }

        if (tracing) {
            return "Tracing";
        }

        if (fastForward) {
            return "Fast forward";
        }

        return "";
    }

    /**
     * The title bar, which is for a window list and a dock rather than for whoever is playing.
     */
    String title() {
        if (cartridge == null) {
            return "MyNES";
        }

        var state = state();

        return "MyNES - " + cartridge + patched() + kind()
                + (state.isEmpty() ? "" : " (" + state.toLowerCase(Locale.ROOT) + ")");
    }

    /**
     * The control panel's first line: how the machine is being run.
     */
    String dashboard() {
        var parts = new ArrayList<String>();

        parts.add(!running ? "No machine" : paused ? "Paused" : "Running");

        if (frameRate != FrameRate.UNKNOWN) {
            parts.add(frameRate + " fps");
        }

        parts.add(region.label());

        if (cartridge != null) {
            parts.add(String.format(
                    "%s  (mapper %d, %dK+%dK)",
                    cartridge, mapperNumber, prgBytes / 1024, chrBytes / 1024));
        }

        var activity = state();

        // "Paused" is already the first thing on the line, and saying it twice would read as two
        // different facts that happen to agree.
        if (!activity.isEmpty() && !"Paused".equals(activity)) {
            parts.add(activity);
        }

        return String.join("  ·  ", parts);
    }

    /**
     * Which hack is playing, when one is.
     * <p>
     * The cartridge's own name is still first: a patched game is that game plus a patch, and a title
     * bar naming only the patch would leave nothing to say which ROM it was applied to.
     */
    private String patched() {
        return patch == null ? "" : " + " + patch;
    }

    /**
     * The kind of machine, when it is not the usual one.
     * <p>
     * Only PAL is worth the words. A machine's region is invisible from the picture until something
     * is wrong, and when it is wrong -- a game running fast because the header said nothing -- this
     * is the line that says which way to reach for.
     */
    private String kind() {
        return machineRegion == Region.PAL ? " (PAL)" : "";
    }
}
