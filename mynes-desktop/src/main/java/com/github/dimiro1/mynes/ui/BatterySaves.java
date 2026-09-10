package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.state.BatteryRAM;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The cartridge's {@code .sav} file, kept in step with the chip the game is writing to.
 * <p>
 * A class rather than three methods on the window because there is a <b>rule</b> in here that needs
 * a memory of its own: the file is written when the bytes have changed and not otherwise, which
 * takes a copy of what they held last time. {@link #shadow} is that copy, and it is the whole
 * reason this is an object. A game that saves once an hour costs one file write an hour and one
 * array comparison a minute for the rest of it.
 * <p>
 * <b>Three entry points on two threads, and the difference is not cosmetic.</b> {@link #load} and
 * {@link #save} are called with the emulation thread stopped or not yet started, which is what
 * makes reading the machine from the event dispatch thread safe there, and they put their dialogs
 * up directly. {@link #autosave} runs <em>on</em> the emulation thread, which is what makes reading
 * the mapper's array safe there -- so its dialog has to be handed back to the event dispatch
 * thread, the only one allowed to put one on the screen.
 * <p>
 * Which file is not decided here. The window works out the game's path -- a romhack is filed apart
 * from the cartridge it was cut against, and a cartridge out of a zip is filed beside the zip --
 * and hands it in, because that decision belongs to whatever knows what was opened.
 */
final class BatterySaves {

    private static final Logger logger = System.getLogger("UI");

    /**
     * How often the cartridge RAM is checked and written out while a game is running.
     * <p>
     * There is a save on quit and one on changing cartridges, and neither helps the laptop that runs
     * out of power mid-dungeon. A minute of lost progress is a tolerable worst case, and the check
     * costs an {@link Arrays#equals} against a shadow copy -- so nothing is written unless the game
     * has actually saved something, and nothing is added to the hot path of every store to $6000,
     * which a dirty flag on the mapper would have been.
     * <p>
     * A minute is also far too long to be worth its own thread, so the window runs it off a Swing
     * timer and posts the work to the machine's.
     */
    static final int AUTOSAVE_MILLIS = 60_000;

    private final Component parent;

    /**
     * What the cartridge's RAM held the last time it was written out, or when the machine started.
     * <p>
     * Compared against rather than trusting a flag, because there is no flag: nothing in the
     * hardware says "the game has saved". A board with a battery on it is one whose RAM survives
     * the power going off, and a game writes to it whenever it likes.
     */
    private byte[] shadow = new byte[0];

    BatterySaves(final Component parent) {
        this.parent = parent;
    }

    /**
     * Fills the cartridge's RAM from its {@code .sav} file, if it has a battery and there is one.
     */
    void load(final NES nes, final Path gamePath) {
        if (nes == null || gamePath == null || !BatteryRAM.isWorthSaving(nes)) {
            return;
        }

        var path = BatteryRAM.pathFor(gamePath);

        try {
            var read = BatteryRAM.read(nes, path);

            if (read >= 0) {
                logger.log(Level.INFO,
                        "restored " + read + " bytes of save RAM from " + path.getFileName());
            }
        } catch (IOException e) {
            // Worth a dialog rather than a log line: carrying on means the game says the save file
            // is corrupt, and the player deserves to know it was the emulator that could not read
            // it.
            logger.log(Level.ERROR, "could not read the save file", e);
            JOptionPane.showMessageDialog(
                    parent,
                    "Could not read " + path.getFileName() + ": " + e.getMessage()
                            + "\n\nThe game will start as though its battery were flat.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Takes the copy every later {@link #autosave} is compared against.
     * <p>
     * Called once the machine has its battery, so that whatever the RAM arrived holding is the
     * starting point and the first check after a load does not rewrite an unchanged file.
     */
    void track(final NES nes) {
        shadow = nes.getBus().getMapper().prgRAM().clone();
    }

    /**
     * Writes the cartridge's RAM out, if a real console would have kept it.
     * <p>
     * Unconditional, unlike {@link #autosave}: the callers are the window closing and a cartridge
     * being replaced, and neither gets a second chance to be right.
     */
    void save(final NES nes, final Path gamePath) {
        if (nes == null || gamePath == null || !BatteryRAM.isWorthSaving(nes)) {
            return;
        }

        var path = BatteryRAM.pathFor(gamePath);

        try {
            BatteryRAM.write(nes, path);
            logger.log(Level.INFO, "wrote save RAM to " + path.getFileName());
        } catch (IOException e) {
            logger.log(Level.ERROR, "could not write the save file", e);
            JOptionPane.showMessageDialog(
                    parent,
                    "Could not write " + path.getFileName() + ": " + e.getMessage()
                            + "\n\nThe game's progress since it was last saved may be lost.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Writes the cartridge RAM out if the game has changed it since the last time, and does nothing
     * at all if it has not.
     * <p>
     * Runs on the emulation thread.
     */
    void autosave(final NES nes, final Path gamePath) {
        if (nes == null || gamePath == null || !BatteryRAM.isWorthSaving(nes)) {
            return;
        }

        var ram = nes.getBus().getMapper().prgRAM();

        if (Arrays.equals(ram, shadow)) {
            return;
        }

        var path = BatteryRAM.pathFor(gamePath);

        try {
            BatteryRAM.write(nes, path);
            shadow = ram.clone();
            logger.log(Level.INFO, "the game saved, so " + path.getFileName() + " was written");
        } catch (IOException e) {
            // Hopped back onto the event dispatch thread, which is the only one allowed to put a
            // dialog on the screen -- this is the one of the three that is not already on it.
            var what = "Could not write " + path.getFileName();

            logger.log(Level.ERROR, what, e);
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    parent, what + ": " + e.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
        }
    }
}
