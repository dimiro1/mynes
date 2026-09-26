package com.github.dimiro1.mynes.ui.debugger;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.EmulatorRunner;
import com.github.dimiro1.mynes.ui.MenuKey;
import com.github.dimiro1.mynes.ui.Readout;
import net.miginfocom.swing.MigLayout;

import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JOptionPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A view of a stopped machine: where it is, what it was about to do, and what is in memory.
 * <p>
 * Everything here happens on the event dispatch thread, and the rule it keeps is stricter than the
 * CHR viewer's rather than looser. That one shows <em>memory</em>, where a stale element is a
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
 * There is deliberately no {@link com.github.dimiro1.mynes.ui.Sweep}. The CHR viewer's poll is
 * exactly the wrong idea here: it would be polling a running machine, and what came back could not
 * be trusted.
 * <p>
 * The panes are split rather than fixed because no two questions want the same shape of window: a
 * raster bug wants the registers, a corrupted table wants the memory, a lost jump wants the listing
 * and nothing else. The dividers remember nothing between sessions, which is deliberate for now --
 * a view that opened at a size chosen for the last bug is a small trap.
 */
public final class DebuggerPanel extends JPanel {
    private static final System.Logger logger = System.getLogger("Debugger");

    private final Debugger debugger;

    private final DisassemblyPanel disassembly = new DisassemblyPanel(new Listing());
    private final SourcePanel source = new SourcePanel(new Sources());
    private final JTabbedPane code = new JTabbedPane();
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
    private Cart cart;
    private MachineSnapshot snapshot;
    private SourceProgram sourceProgram;
    private Path sourceRoot;

    private final SystemFileChooser debugChooser = new SystemFileChooser();
    private final SystemFileChooser sourceChooser = new SystemFileChooser();
    private final SourceAssociations associations =
            SourceAssociations.load(SourceAssociations.DEFAULT_PATH);

    /**
     * Whether the machine is stopped because of something done in this view, which is what decides
     * whether closing it should let the machine go again.
     */
    private boolean stoppedByUs;

    /**
     * The breakpoints as the debugger last reported them, so that Run to Here can tell a point it
     * put down itself from one the user did and only take the first kind back up.
     */
    private Set<Integer> knownBreakpoints = Set.of();
    private Set<Integer> knownPRGBreakpoints = Set.of();

    /**
     * A breakpoint this view put down for Run to Here and owes the debugger back, or -1.
     */
    private int runToAddress = -1;
    private Set<Integer> runToPRG = Set.of();

    public DebuggerPanel(
            final NES nes, final EmulatorRunner runner, final Debugger debugger) {

        this(nes, runner, debugger, nes.getCart());
    }

    public DebuggerPanel(
            final NES nes, final EmulatorRunner runner, final Debugger debugger, final Cart cart) {

        this.nes = nes;
        this.runner = runner;
        this.debugger = debugger;
        this.cart = cart;
        this.points = new PointsPanel(new Editing());

        var filter = new SystemFileChooser.FileNameExtensionFilter("ld65 debug information", "dbg");
        debugChooser.addChoosableFileFilter(filter);
        debugChooser.setFileFilter(filter);
        debugChooser.setDialogTitle("Attach Debug Information");
        debugChooser.setApproveButtonText("Attach");

        sourceChooser.setFileSelectionMode(SystemFileChooser.DIRECTORIES_ONLY);
        sourceChooser.setDialogTitle("Find Source Files");
        sourceChooser.setApproveButtonText("Use Folder");

        init();
        restoreSources();
    }

    private void init() {
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
        code.addTab("Source", source);
        code.addTab("Disassembly", disassembly);
        code.setSelectedComponent(disassembly);

        // A split pane opens its divider at the first component's preferred width and never takes
        // one below its minimum, so both are said for every pane: the preferred sizes are where the
        // dividers start, and the minimums are what stops a drag from squashing a panel into
        // buttons drawn as "...". The points panel works its own minimum out from its rows.
        code.setPreferredSize(new Dimension(700, 400));
        code.setMinimumSize(new Dimension(380, 160));
        side.setPreferredSize(new Dimension(400, 400));
        side.setMinimumSize(new Dimension(300, 200));
        memory.setPreferredSize(new Dimension(660, 280));
        memory.setMinimumSize(new Dimension(420, 120));
        points.setPreferredSize(new Dimension(Math.max(440, points.getMinimumSize().width), 280));

        var top = split(JSplitPane.HORIZONTAL_SPLIT, code, side, 0.7);
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
        return "F5 Run   F10 Step   F8 Step Frame   F9 Breakpoint   " + MenuKey.text() + "G Go to";
    }

    /**
     * The same four actions as the buttons, on the keys a debugger usually puts them on.
     * <p>
     * Handed to the window rather than taken, because a {@code WHEN_IN_FOCUSED_WINDOW} binding is
     * the window's to give: whatever ends up holding this view holds one input map, and a view that
     * bound four function keys on it unasked would be one of several competing for them. There is
     * only ever one debugger, which is why these four can be asked for at all.
     * <p>
     * No clash with the game window's F5 and F7 quick save and load: those are bound on that window,
     * and the keyboard dispatcher ignores everything while it is not the active one -- which is also
     * what stops typing a hex address in here from pressing Select.
     */
    public void installKeysIn(final JRootPane root) {
        bind(root, "F5", this::resume);
        bind(root, "F8", this::stepOneFrame);
        bind(root, "F9", this::toggleBreakpointAtSelection);
        bind(root, "F10", this::stepInstruction);
    }

    private static void bind(final JRootPane root, final String key, final Runnable action) {
        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke(key), key);
        root.getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                action.run();
            }
        });
    }

    /**
     * Points the view at a new machine, which a power cycle or a region change brings.
     * <p>
     * Repointed rather than closed, which is the opposite of what happens to the CHR viewer, and
     * deliberately: that one's contents are entirely derived from the machine, so closing it loses
     * nothing. This one carries the user's own work -- the breakpoints, the address they were
     * looking at -- and throwing that away on a power cycle would discard the very thing they cycled
     * the power to test.
     */
    public void setMachine(final NES nes, final EmulatorRunner runner) {
        setMachine(nes, runner, nes.getCart());
    }

    public void setMachine(final NES nes, final EmulatorRunner runner, final Cart cart) {
        var sameCartridge = this.cart.sha256().equals(cart.sha256());

        this.nes = nes;
        this.runner = runner;
        this.cart = cart;
        snapshot = null;

        if (!sameCartridge) {
            sourceProgram = null;
            sourceRoot = null;
            source.detach();
            disassembly.setSourceProgram(null);
            code.setSelectedComponent(disassembly);
        }

        // What is on show describes a machine that no longer exists. The points stay -- they are the
        // user's, and keeping them is the whole reason this view is repointed rather than closed --
        // but the listing and the memory are emptied rather than left to be believed.
        disassembly.clear();
        memory.clear();
        stack.clear();

        // Read again rather than left alone, because a new cartridge is the one machine change that
        // clears them: the points are the user's while the game is the same game, and a list of
        // breakpoints that are no longer set would be the worst kind of stale.
        var breaks = Set.copyOf(debugger.breakpoints());
        var prgBreaks = Set.copyOf(debugger.prgBreakpoints());
        var conditions = Map.copyOf(debugger.conditions());

        knownBreakpoints = breaks;
        knownPRGBreakpoints = prgBreaks;

        points.show(breaks, conditions, prgBreaks, sourceProgram,
                Map.copyOf(debugger.watchpoints()));
        disassembly.setBreakpoints(breaks, conditions);
        source.setBreakpoints(prgBreaks);

        running();

        if (!sameCartridge) {
            restoreSources();
        }
    }

    /**
     * The machine has stopped. Called on the event dispatch thread with it already halted, which is
     * the only moment reading it is legal.
     */
    public void stopped(final Debugger.Stop stop) {
        stoppedByUs = true;

        snapshot = MachineSnapshot.of(nes, debugger);
        var breaks = Set.copyOf(debugger.breakpoints());
        var prgBreaks = Set.copyOf(debugger.prgBreakpoints());
        var conditions = Map.copyOf(debugger.conditions());

        knownBreakpoints = breaks;
        knownPRGBreakpoints = prgBreaks;

        disassembly.show(snapshot, breaks, conditions);
        registers.show(snapshot.machine());
        stack.show(snapshot);
        memory.show(snapshot, stop);
        points.show(breaks, conditions, prgBreaks, sourceProgram,
                Map.copyOf(debugger.watchpoints()));

        SourceProgram.SourceLine sourceLine = null;
        if (sourceProgram != null) {
            sourceLine = sourceProgram.lineAt(snapshot.prgOffset(stop.pc()));
            source.show(sourceProgram, sourceLine, prgBreaks);
        }

        status.setText(describe(stop, sourceLine));
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

        if (!runToPRG.isEmpty()) {
            var offsets = runToPRG;
            runToPRG = Set.of();
            edit(() -> offsets.forEach(debugger::removePRGBreakpoint));
        }
    }

    /**
     * The machine as it was at the end of a frame, while it goes on running.
     * <p>
     * Only the registers take it, and only while the machine is actually going: a panel showing a
     * frame boundary over a machine somebody has stopped at a breakpoint would be describing the
     * wrong moment, and the stop snapshot is both exact and already there.
     */
    public void readout(final Readout machine) {
        if (!stoppedByUs) {
            registers.live(machine);
        }
    }

    /**
     * The machine is going again, so what is on show is now a photograph rather than a machine.
     */
    public void running() {
        stoppedByUs = false;

        registers.stale();
        stack.stale();
        source.clearMachine();
        status.setText("Running");
        dot.setColour(Theme.running());

        run.setEnabled(false);
        breakNow.setEnabled(true);
        step.setEnabled(true);
        stepFrame.setEnabled(true);
    }

    /**
     * Lets the machine go on the way out. Called by whatever is holding this view as it goes.
     * <p>
     * Without this, closing the debugger while it has the machine stopped leaves a frozen emulator
     * and the only way out buried in the Machine menu, which looks exactly like a crash.
     */
    public void closing() {
        if (stoppedByUs && runner != null) {
            resume();
        }
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
        if (code.getSelectedComponent() == source) {
            var line = source.selectedLine();

            if (line != null && line.hasCode()) {
                toggleSourceBreakpoint(line);
            }

            return;
        }

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
            DebuggerPanel.this.toggleBreakpoint(address);
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

    private void toggleSourceBreakpoint(final SourceProgram.SourceLine line) {
        var offsets = line.breakpointOffsets();
        var remove = offsets.stream().allMatch(knownPRGBreakpoints::contains);

        edit(() -> offsets.forEach(offset -> {
            if (remove) {
                debugger.removePRGBreakpoint(offset);
            } else {
                debugger.addPRGBreakpoint(offset);
            }
        }));
    }

    /** The source listing's file and breakpoint actions. */
    private final class Sources implements SourcePanel.Actions {
        @Override
        public void attach() {
            if (debugChooser.showOpenDialog(DebuggerPanel.this) == SystemFileChooser.APPROVE_OPTION) {
                loadSources(debugChooser.getSelectedFile().toPath(), null, true);
            }
        }

        @Override
        public void locateSources() {
            if (sourceProgram == null) {
                return;
            }

            if (sourceRoot != null) {
                sourceChooser.setCurrentDirectory(sourceRoot.toFile());
            }

            if (sourceChooser.showOpenDialog(DebuggerPanel.this)
                    != SystemFileChooser.APPROVE_OPTION) {
                return;
            }

            loadSources(sourceProgram.debugFile(), sourceChooser.getSelectedFile().toPath(), false);
        }

        @Override
        public void detach() {
            var offsets = Set.copyOf(knownPRGBreakpoints);

            sourceProgram = null;
            sourceRoot = null;
            source.detach();
            disassembly.setSourceProgram(null);
            code.setSelectedComponent(disassembly);
            edit(() -> offsets.forEach(debugger::removePRGBreakpoint));

            try {
                associations.forget(cart.sha256());
            } catch (IOException e) {
                logger.log(System.Logger.Level.WARNING, "could not forget source association", e);
            }
        }

        @Override
        public void toggleBreakpoint(final SourceProgram.SourceLine line) {
            toggleSourceBreakpoint(line);
        }

        @Override
        public void runTo(final SourceProgram.SourceLine line) {
            var temporary = new LinkedHashSet<Integer>();

            for (var offset : line.breakpointOffsets()) {
                if (!knownPRGBreakpoints.contains(offset)) {
                    temporary.add(offset);
                }
            }

            runToPRG = Set.copyOf(temporary);
            edit(() -> temporary.forEach(debugger::addPRGBreakpoint));
            resume();
        }

        @Override
        public void showInDisassembly(final SourceProgram.SourceLine line) {
            if (!line.ranges().isEmpty()) {
                disassembly.goTo(line.ranges().getFirst().cpuAddress());
                code.setSelectedComponent(disassembly);
            }
        }
    }

    private void loadSources(
            final Path debugFile, final Path root, final boolean offerRoot) {
        try {
            var resolvedRoot = root;
            var loaded = Cc65DebugInfo.read(debugFile, cart.prgROM(), resolvedRoot);

            if (offerRoot && loaded.hasMissingFiles()) {
                var answer = JOptionPane.showConfirmDialog(
                        this,
                        "Some recorded source paths no longer exist. Choose the project folder?",
                        "Find Source Files",
                        JOptionPane.YES_NO_OPTION,
                        JOptionPane.QUESTION_MESSAGE);

                if (answer == JOptionPane.YES_OPTION
                        && sourceChooser.showOpenDialog(this) == SystemFileChooser.APPROVE_OPTION) {
                    resolvedRoot = sourceChooser.getSelectedFile().toPath();
                    loaded = Cc65DebugInfo.read(debugFile, cart.prgROM(), resolvedRoot);
                }
            }

            sourceProgram = loaded;
            sourceRoot = resolvedRoot;
            disassembly.setSourceProgram(loaded);

            var line = snapshot == null ? null
                    : loaded.lineAt(snapshot.prgOffset(snapshot.cpu().pc()));
            source.show(loaded, line, knownPRGBreakpoints);
            code.setSelectedComponent(source);

            try {
                associations.remember(cart.sha256(), debugFile, resolvedRoot);
            } catch (IOException e) {
                logger.log(System.Logger.Level.WARNING, "could not remember source association", e);
            }
        } catch (IOException | RuntimeException e) {
            logger.log(System.Logger.Level.ERROR, "could not load " + debugFile, e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not attach " + debugFile.getFileName() + ": " + e.getMessage(),
                    "Debug Information",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void restoreSources() {
        var remembered = associations.get(cart.sha256());

        if (remembered == null || !java.nio.file.Files.isRegularFile(remembered.debugFile())) {
            return;
        }

        try {
            var loaded = Cc65DebugInfo.read(
                    remembered.debugFile(), cart.prgROM(), remembered.sourceRoot());

            sourceProgram = loaded;
            sourceRoot = remembered.sourceRoot();
            disassembly.setSourceProgram(loaded);
            source.show(loaded, null, knownPRGBreakpoints);
            code.setSelectedComponent(source);
        } catch (IOException | RuntimeException e) {
            logger.log(System.Logger.Level.WARNING,
                    "could not restore " + remembered.debugFile(), e);
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
        public void removePRGBreakpoint(final int offset) {
            edit(() -> debugger.removePRGBreakpoint(offset));
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
            var prgBreaks = Set.copyOf(debugger.prgBreakpoints());
            var conditions = Map.copyOf(debugger.conditions());
            var watches = Map.copyOf(debugger.watchpoints());

            SwingUtilities.invokeLater(() -> {
                knownBreakpoints = breaks;
                knownPRGBreakpoints = prgBreaks;
                points.show(breaks, conditions, prgBreaks, sourceProgram, watches);
                disassembly.setBreakpoints(breaks, conditions);
                source.setBreakpoints(prgBreaks);
            });
        });
    }

    private static String describe(
            final Debugger.Stop stop, final SourceProgram.SourceLine sourceLine) {
        var stopped = "Stopped  ·  " + switch (stop.reason()) {
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

        return sourceLine == null ? stopped : stopped + "  ·  " + sourceLine.location();
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
