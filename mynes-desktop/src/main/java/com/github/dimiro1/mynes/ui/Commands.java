package com.github.dimiro1.mynes.ui;

import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * The things the control panel can make the machine do, as {@link javax.swing.Action}s.
 * <p>
 * Beside {@link Switches} rather than inside it, because a command and a switch are different
 * things and the difference is the whole of why {@code Switches} exists: a switch has state that
 * two places could disagree about, and a command has none. What a command does share is whether it
 * can be used -- Power Cycle and Game Genie... are greyed out while a movie is running, Reset until
 * there is a machine to reset -- and an Action carries that as readily as it carries a tick, so a
 * button in the column and an item in the menu grey out together and neither owns the answer.
 * <p>
 * Only the five the column shows are here. Open..., Quit, the save state slots and the movie items
 * are the menu bar's alone, and an Action for each would be five more names to keep true for
 * nothing.
 * <p>
 * Every one of them starts greyed. There is no machine when the window opens, and a button that
 * looks pressable and does nothing is worse than one that says so.
 */
public final class Commands {
    private final Command reset = new Command("Reset", KeyEvent.VK_R, shortcut(KeyEvent.VK_R));

    private final Command powerCycle =
            new Command("Power Cycle", KeyEvent.VK_C, shiftShortcut(KeyEvent.VK_R));

    private final Command gameGenie = new Command("Game Genie...", KeyEvent.VK_G, null);

    /**
     * Its own command rather than a tick, because starting one asks where it should go and stopping
     * one does not -- the shape Record Movie... has in the Machine menu, and for the same reason.
     */
    private final Command startTrace = new Command("Start Trace...", KeyEvent.VK_T, null);

    private final Command stopTrace = new Command("Stop Trace", KeyEvent.VK_UNDEFINED, null);

    /**
     * The same shape again, for the tune rather than for the instructions.
     */
    private final Command startMusic = new Command("Start Music...", KeyEvent.VK_M, null);

    private final Command stopMusic = new Command("Stop Music", KeyEvent.VK_UNDEFINED, null);

    public Command reset() {
        return reset;
    }

    public Command powerCycle() {
        return powerCycle;
    }

    public Command gameGenie() {
        return gameGenie;
    }

    public Command startTrace() {
        return startTrace;
    }

    public Command stopTrace() {
        return stopTrace;
    }

    public Command startMusic() {
        return startMusic;
    }

    public Command stopMusic() {
        return stopMusic;
    }

    private static KeyStroke shortcut(final int key) {
        return KeyStroke.getKeyStroke(key, MenuKey.mask());
    }

    private static KeyStroke shiftShortcut(final int key) {
        return KeyStroke.getKeyStroke(key, MenuKey.mask() | InputEvent.SHIFT_DOWN_MASK);
    }

    /**
     * One thing to do, wherever it is offered.
     */
    public static final class Command extends AbstractAction {
        private @Nullable Runnable onRun;

        private Command(
                final String label, final int mnemonic, final @Nullable KeyStroke accelerator) {

            super(label);

            if (mnemonic != KeyEvent.VK_UNDEFINED) {
                putValue(MNEMONIC_KEY, mnemonic);
            }

            if (accelerator != null) {
                putValue(ACCELERATOR_KEY, accelerator);
            }

            setEnabled(false);
        }

        /**
         * What to do when somebody asks for it. One listener, not a list, for the reason
         * {@link Switches.Toggle#onChange} takes one.
         */
        public Command onRun(final Runnable listener) {
            onRun = listener;
            return this;
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            if (onRun != null) {
                onRun.run();
            }
        }
    }
}
