package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.EmulatorRunner;

import javax.swing.JFrame;
import java.awt.Component;
import java.awt.Dimension;

/**
 * A window with {@link DebuggerPanel} in it and nothing else.
 * <p>
 * Everything only a window can give is here rather than in the view: the title, the size, the root
 * pane the four function keys are bound on, and the moment of closing -- which the debugger cares
 * about more than the other views do, since it is the one that may have left the machine stopped.
 */
public final class DebuggerFrame extends JFrame {
    private final DebuggerPanel view;

    public DebuggerFrame(
            final Component parent,
            final NES nes,
            final EmulatorRunner runner,
            final Debugger debugger) {

        this.view = new DebuggerPanel(nes, runner, debugger);

        setTitle("Debugger");
        add(view);

        view.installKeysIn(getRootPane());

        setSize(1120, 780);
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(parent);
    }

    /**
     * Points the window at a new machine, which a power cycle or a region change brings.
     */
    public void setMachine(final NES nes, final EmulatorRunner runner) {
        view.setMachine(nes, runner);
    }

    /**
     * The machine has stopped. Called on the event dispatch thread with it already halted, which is
     * the only moment reading it is legal.
     */
    public void stopped(final Debugger.Stop stop) {
        view.stopped(stop);
    }

    /**
     * The machine is going again, so what is on show is now a photograph rather than a machine.
     */
    public void running() {
        view.running();
    }

    /**
     * Lets the machine go on the way out; see {@link DebuggerPanel#closing()}.
     */
    @Override
    public void dispose() {
        view.closing();
        super.dispose();
    }
}
