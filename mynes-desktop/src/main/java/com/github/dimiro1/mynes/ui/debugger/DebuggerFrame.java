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
import javax.swing.JSplitPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
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
 * <p>
 * The panes are split rather than fixed because no two questions want the same shape of window: a
 * raster bug wants the registers, a corrupted table wants the memory, a lost jump wants the listing
 * and nothing else. The dividers remember nothing between sessions, which is deliberate for now --
 * a window that opened at a size chosen for the last bug is a small trap.
 */
public final class DebuggerFrame extends JFrame {
    private final Debugger debugger;

    private final DisassemblyPanel disassembly = new DisassemblyPanel(new Listing());
    private final RegistersPanel registers = new RegistersPanel();
    private final StackPanel stack = new StackPanel();
    private final MemoryPanel memory = new MemoryPanel();
    private final PointsPanel points;

    private final Dot dot = new Dot();
    private final JLabel status = new JLabel("Running");
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

    /**
     * The breakpoints as the debugger last reported them, so that Run to Here can tell a point it
     * put down itself from one the user did and only take the first kind back up.
     */
    private Set<Integer> knownBreakpoints = Set.of();

    /**
     * A breakpoint this window put down for Run to Here and owes the debugger back, or -1.
     */
    private int runToAddress = -1;

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
        setLayout(new MigLayout("fill, insets 0, gap 0", "[grow,fill]", "[][grow,fill][]"));

        run.addActionListener(e -> resume());
        breakNow.addActionListener(e -> runner.breakNow());
        step.addActionListener(e -> stepInstruction());
        stepFrame.addActionListener(e -> stepOneFrame());

        run.setToolTipText("Let the machine go (F5)");
        breakNow.setToolTipText("Stop at the next instruction");
        step.setToolTipText("Run one instruction (F10)");
        stepFrame.setToolTipText("Run to the end of the frame (F8)");

        var controls = new JPanel(new MigLayout("insets 8 8 4 8, gap 4", "[][][][]push", ""));
        controls.add(run);
        controls.add(breakNow);
        controls.add(step);
        controls.add(stepFrame);

        var side = new JPanel(new MigLayout("insets 0, fill, wrap 1, gap 0", "[grow,fill]", "[][grow,fill]"));
        side.add(registers);
        side.add(stack, "hmin 120");
        // A split pane opens its divider at the first component's preferred width and never takes
        // one below its minimum, so both are said for every pane: the preferred sizes are where the
        // dividers start, and the minimums are what stops a drag from squashing a panel into
        // buttons drawn as "...". The points panel works its own minimum out from its rows.
        disassembly.setPreferredSize(new Dimension(700, 400));
        disassembly.setMinimumSize(new Dimension(380, 160));
        side.setPreferredSize(new Dimension(400, 400));
        side.setMinimumSize(new Dimension(300, 200));
        memory.setPreferredSize(new Dimension(660, 280));
        memory.setMinimumSize(new Dimension(420, 120));
        points.setPreferredSize(new Dimension(Math.max(440, points.getMinimumSize().width), 280));

        var top = split(JSplitPane.HORIZONTAL_SPLIT, disassembly, side, 0.7);
        var bottom = split(JSplitPane.HORIZONTAL_SPLIT, memory, points, 0.62);
        var body = split(JSplitPane.VERTICAL_SPLIT, top, bottom, 0.56);

        var hints = new JLabel(hints());
        hints.setForeground(Theme.muted());
        hints.setFont(hints.getFont().deriveFont(11f));

        var strip = new JPanel(new MigLayout("insets 5 10 7 10, gap 6", "[][grow][]", "[]"));
        strip.add(dot);
        strip.add(status);
        strip.add(hints);

        add(controls, "wrap");
        add(body, "wrap");
        add(strip);

        bindKeys();

        setSize(1120, 780);
        setMinimumSize(new Dimension(900, 620));
        setLocationRelativeTo(parent);
        running();
    }

    private static JSplitPane split(
            final int orientation, final Component first, final Component second, final double weight) {

        var pane = new JSplitPane(orientation, true, first, second);

        pane.setResizeWeight(weight);
        pane.setDividerSize(6);
        pane.setBorder(null);

        return pane;
    }

    /**
     * The keys, spelled for the platform: {@code ⌘G} here, {@code Ctrl+G} elsewhere.
     */
    private static String hints() {
        var shortcut = InputEvent.getModifiersExText(
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());

        return "F5 Run   F10 Step   F8 Step Frame   F9 Breakpoint   " + shortcut + "G Go to";
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
        stack.clear();

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
        var conditions = Map.copyOf(debugger.conditions());

        knownBreakpoints = breaks;

        disassembly.show(snapshot, breaks, conditions);
        registers.show(snapshot);
        stack.show(snapshot);
        memory.show(snapshot, stop);
        points.show(breaks, conditions, Map.copyOf(debugger.watchpoints()));

        status.setText(describe(stop));
        dot.setColour(Theme.stopped());

        run.setEnabled(true);
        breakNow.setEnabled(false);
        step.setEnabled(true);
        stepFrame.setEnabled(true);

        // The point Run to Here put down has done its job, wherever the machine actually stopped:
        // a watchpoint that fired first is a real answer, and leaving the temporary point behind
        // would stop the machine there again later, at a place nobody asked to break any more.
        if (runToAddress >= 0) {
            var address = runToAddress;

            runToAddress = -1;
            edit(() -> debugger.removeBreakpoint(address));
        }
    }

    /**
     * The machine is going again, so what is on show is now a photograph rather than a machine.
     */
    public void running() {
        stoppedByUs = false;

        registers.stale();
        stack.stale();
        status.setText("Running");
        dot.setColour(Theme.running());

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
     * What the listing asks for.
     */
    private final class Listing implements DisassemblyPanel.Actions {
        @Override
        public void toggleBreakpoint(final int address) {
            DebuggerFrame.this.toggleBreakpoint(address);
        }

        /**
         * A breakpoint and a resume, with the breakpoint taken back at the next stop -- unless the
         * user already had one there, in which case it is theirs and stays.
         */
        @Override
        public void runTo(final int address) {
            if (!knownBreakpoints.contains(address)) {
                runToAddress = address;
                edit(() -> debugger.addBreakpoint(address));
            }

            resume();
        }

        @Override
        public void showInMemory(final int address) {
            memory.goTo(address);
        }
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
                knownBreakpoints = breaks;
                points.show(breaks, conditions, watches);
                disassembly.setBreakpoints(breaks, conditions);
            });
        });
    }

    private static String describe(final Debugger.Stop stop) {
        return "Stopped  ·  " + switch (stop.reason()) {
            case BREAKPOINT -> String.format("breakpoint at $%04X", stop.pc());
            case WATCHPOINT -> String.format(
                    "watchpoint: $%04X %s $%02X by the instruction at $%04X",
                    stop.address(),
                    stop.access() == Debugger.Access.READ ? "read as" : "written with",
                    stop.value(),
                    stop.by());
            case STEP -> String.format("stepped to $%04X", stop.pc());
            case FRAME -> String.format("end of frame, at $%04X", stop.pc());
            case ASKED -> String.format("at $%04X", stop.pc());
        };
    }

    /**
     * The little circle beside the status: one colour for a machine that is going and another for
     * one that is not, which is readable from across the room where the word is not.
     */
    private static final class Dot extends JComponent {
        private Color colour = Color.GRAY;

        private Dot() {
            setPreferredSize(new Dimension(10, 10));
            setMinimumSize(getPreferredSize());
        }

        void setColour(final Color colour) {
            this.colour = colour;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics g) {
            var g2 = (Graphics2D) g.create();

            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(colour);
                g2.fillOval(1, 1, getWidth() - 2, getHeight() - 2);
            } finally {
                g2.dispose();
            }
        }
    }
}
