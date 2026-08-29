package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.EmulatorRunner;
import net.miginfocom.swing.MigLayout;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A window over a stopped machine: where it is, what it was about to do, and what is in memory.
 * <p>
 * Everything here happens on the event dispatch thread, and the rule it keeps is stricter than the
 * CHR viewer's rather than looser. That window shows <em>memory</em>, where a stale element is a
 * tile a frame out of date and visibly harmless. This one shows <em>a machine at a moment in its
 * execution</em>, where values read at different instants would not be a slightly stale picture but
 * a machine that never existed -- and being believed is the whole of its job. So:
 * <ol>
 *   <li>the machine is read only from inside {@link #stopped}, which
 *       {@link EmulatorRunner#setStopListener} calls with the machine already halted;</li>
 *   <li>it is read exactly once per stop, into a {@link MachineSnapshot}, because Swing repaints
 *       whenever it likes and a panel that read the machine while painting would be reading a
 *       running one;</li>
 *   <li>everything that changes the machine or the debugger is posted onto the emulation thread,
 *       which is what lets {@link Debugger} have no synchronisation in it at all.</li>
 * </ol>
 * There is deliberately no refresh timer. The CHR viewer's poll is exactly the wrong idea here: it
 * would be polling a running machine, and what came back could not be trusted.
 */
public final class DebuggerFrame extends JFrame {
    private final Debugger debugger;

    private final DisassemblyPanel disassembly = new DisassemblyPanel();
    private final RegistersPanel registers = new RegistersPanel();
    private final MemoryPanel memory = new MemoryPanel();
    private final PointsPanel points;

    private final JLabel status = new JLabel("running");
    private final JButton run = new JButton("Run");
    private final JButton breakNow = new JButton("Break");
    private final JButton step = new JButton("Step");
    private final JButton stepFrame = new JButton("Step Frame");

    private NES nes;
    private EmulatorRunner runner;

    /**
     * Whether the machine is stopped because of something done in this window, which is what decides
     * whether closing it should let the machine go again.
     */
    private boolean stoppedByUs;

    public DebuggerFrame(
            final Component parent,
            final NES nes,
            final EmulatorRunner runner,
            final Debugger debugger) {

        this.nes = nes;
        this.runner = runner;
        this.debugger = debugger;
        this.points = new PointsPanel(new Editing());

        init(parent);
    }

    private void init(final Component parent) {
        setTitle("Debugger");
        setLayout(new MigLayout("fill, insets 8", "[grow,fill][320!]", "[][grow,fill][260!]"));

        run.addActionListener(e -> resume());
        breakNow.addActionListener(e -> runner.breakNow());
        step.addActionListener(e -> stepInstruction());
        stepFrame.addActionListener(e -> stepOneFrame());

        disassembly.addSelectionListener(this::toggleBreakpointAtSelection);

        var controls = new JPanel(new MigLayout("insets 0", "[][][][]push[]", ""));
        controls.add(run);
        controls.add(breakNow);
        controls.add(step);
        controls.add(stepFrame);
        controls.add(status);

        add(controls, "span 2, growx, wrap");
        add(disassembly, "grow");
        add(registers, "grow, wrap");
        add(memory, "grow");
        add(points, "grow");

        bindKeys();

        setSize(1000, 720);
        setLocationRelativeTo(parent);
        running();
    }

    /**
     * The same four actions as the buttons, on the keys a debugger usually puts them on.
     * <p>
     * No clash with the game window's F5 and F7 quick save and load: those are bound on that window,
     * and the keyboard dispatcher ignores everything while it is not the active one -- which is also
     * what stops typing a hex address in here from pressing Select.
     */
    private void bindKeys() {
        bind("F5", this::resume);
        bind("F8", this::stepOneFrame);
        bind("F9", this::toggleBreakpointAtSelection);
        bind("F10", this::stepInstruction);
    }

    private void bind(final String key, final Runnable action) {
        var root = getRootPane();

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        root.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                action.run();
            }
        });
    }

    /**
     * Points the window at a new machine, which a power cycle or a region change brings.
     * <p>
     * Repointed rather than closed, which is the opposite of what happens to the CHR viewer, and
     * deliberately: that window's contents are entirely derived from the machine, so closing it
     * loses nothing. This one carries the user's own work -- the breakpoints, the address they were
     * looking at -- and throwing that away on a power cycle would discard the very thing they cycled
     * the power to test.
     */
    public void setMachine(final NES nes, final EmulatorRunner runner) {
        this.nes = nes;
        this.runner = runner;

        // What is on show describes a machine that no longer exists. The points stay -- they are the
        // user's, and keeping them is the whole reason this window is repointed rather than closed --
        // but the listing and the memory are emptied rather than left to be believed.
        disassembly.clear();
        memory.clear();

        running();
    }

    /**
     * The machine has stopped. Called on the event dispatch thread with it already halted, which is
     * the only moment reading it is legal.
     */
    public void stopped(final Debugger.Stop stop) {
        stoppedByUs = true;

        var snapshot = MachineSnapshot.of(nes, debugger);
        var breaks = Set.copyOf(debugger.breakpoints());

        disassembly.show(snapshot, breaks);
        registers.show(snapshot);
        memory.show(snapshot);
        points.show(
                breaks, Map.copyOf(debugger.conditions()), Map.copyOf(debugger.watchpoints()));

        status.setText(describe(stop));

        run.setEnabled(true);
        breakNow.setEnabled(false);
        step.setEnabled(true);
        stepFrame.setEnabled(true);
    }

    /**
     * The machine is going again, so what is on show is now a photograph rather than a machine.
     */
    public void running() {
        stoppedByUs = false;

        registers.stale();
        status.setText("running");

        run.setEnabled(false);
        breakNow.setEnabled(true);
        step.setEnabled(true);
        stepFrame.setEnabled(true);
    }

    /**
     * Lets the machine go on the way out.
     * <p>
     * Without this, closing the window while it has the machine stopped leaves a frozen emulator
     * and the only way out buried in the Machine menu, which looks exactly like a crash.
     */
    @Override
    public void dispose() {
        if (stoppedByUs && runner != null) {
            resume();
        }

        super.dispose();
    }

    // ================================================================================== internals

    private void resume() {
        runner.resume();
        running();
    }

    private void stepInstruction() {
        runner.stepInstruction();
    }

    private void stepOneFrame() {
        runner.stepFrame();
    }

    private void toggleBreakpointAtSelection() {
        var address = disassembly.selectedAddress();

        if (address >= 0) {
            toggleBreakpoint(address);
        }
    }

    /**
     * Posted rather than done here, because the debugger belongs to the emulation thread. The lists
     * are then refreshed from what it actually holds rather than from what was asked for, so a
     * command that raced a clear cannot leave the window showing a point that is not there.
     */
    private void toggleBreakpoint(final int address) {
        edit(() -> debugger.toggleBreakpoint(address));
    }

    /**
     * What the points panel asks for, all of it posted onto the emulation thread.
     */
    private final class Editing implements PointsPanel.Points {
        @Override
        public void breakAt(final int address, final Condition condition) {
            edit(() -> debugger.addBreakpoint(address, condition));
        }

        @Override
        public void watchAt(final int address, final Debugger.Access on) {
            edit(() -> debugger.addWatchpoint(address, on));
        }

        @Override
        public void removeBreakpoint(final int address) {
            edit(() -> debugger.removeBreakpoint(address));
        }

        @Override
        public void removeWatchpoint(final int address) {
            edit(() -> debugger.removeWatchpoint(address));
        }

        @Override
        public void clear() {
            edit(debugger::clear);
        }
    }

    /**
     * Changes the points on the emulation thread and brings the answer back.
     * <p>
     * The lists are copied on the thread that owns them and handed over, rather than read from here
     * afterwards: the copy is what carries the change across, and reading the live collections from
     * this thread would be reading something the other one is entitled to be writing.
     */
    private void edit(final Runnable change) {
        runner.post(() -> {
            change.run();

            var breaks = Set.copyOf(debugger.breakpoints());
            var conditions = Map.copyOf(debugger.conditions());
            var watches = Map.copyOf(debugger.watchpoints());

            SwingUtilities.invokeLater(() -> {
                points.show(breaks, conditions, watches);
                disassembly.setBreakpoints(breaks);
            });
        });
    }

    private static String describe(final Debugger.Stop stop) {
        var reason = stop.reason().name().toLowerCase(Locale.ROOT);

        if (stop.reason() == Debugger.Reason.WATCHPOINT) {
            return String.format(
                    "%s: $%04X %s $%02X, by $%04X",
                    reason, stop.address(), stop.access().id(), stop.value(), stop.by());
        }

        return String.format("%s at $%04X", reason, stop.pc());
    }
}
