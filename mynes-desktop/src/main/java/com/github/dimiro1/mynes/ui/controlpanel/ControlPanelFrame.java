package com.github.dimiro1.mynes.ui.controlpanel;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.Commands;
import com.github.dimiro1.mynes.ui.EmulatorRunner;
import com.github.dimiro1.mynes.ui.PauseControl;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.Sweep;
import com.github.dimiro1.mynes.ui.Switches;
import com.github.dimiro1.mynes.ui.cartridge.CartridgePanel;
import com.github.dimiro1.mynes.ui.chrviewer.CHRViewerPanel;
import com.github.dimiro1.mynes.ui.debugger.DebuggerPanel;
import com.github.dimiro1.mynes.ui.pads.PadsPanel;
import com.github.dimiro1.mynes.ui.ppuviewer.NametableViewerPanel;
import com.github.dimiro1.mynes.ui.ppuviewer.OAMViewerPanel;
import com.github.dimiro1.mynes.ui.ppuviewer.PaletteViewerPanel;
import com.github.dimiro1.mynes.ui.sound.SoundPanel;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.KeyStroke;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Rectangle;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * One window over everything the machine is doing, and every lever on it.
 * <p>
 * The five debug windows used to be five windows, which meant that watching the sprites while
 * stepping through the code was a matter of arranging two of them, and that reaching for the
 * overclock meant leaving both and finding the game window. They are panels now -- see
 * {@link DebuggerPanel} and the three in {@code ppuviewer} -- and this is where they are put: one
 * instrument at a time filling the window, and every switch in the program in a fixed column down
 * the side.
 * <p>
 * <b>One instrument at a time, rather than the debugger beside one.</b> That was tried first and is
 * the arrangement the shape of these things refuses: the debugger wants a thousand pixels before
 * its own memory view starts eliding its buttons, the widest instrument wants five hundred, and the
 * column wants two hundred and twenty. Seventeen hundred and fifty is wider than a laptop, so
 * side by side is a layout that is correct on a desk and broken in a bag -- and a default that
 * depends on which screen somebody opened it on is not a default. The debugger is still the main
 * view: it is the first tab and the one that is up when the window opens.
 * <p>
 * <b>A window of its own rather than a part of the game window.</b>
 * {@code KeyboardInput.dispatchKeyEvent} returns early while the game window is not the active one,
 * and that one line is what stops typing {@code $C000} into a debugger from pressing Select.
 * Docking these panels into the game window would mean rebuilding that rule by focus owner; a
 * second window gets it for nothing, exactly as the five it replaces did.
 * <p>
 * <b>No menu bar.</b> The tabs are the list of what there is to look at and the column is the list
 * of what there is to do, so a menu would be a third list of the same things.
 * <p>
 * <b>One Pause tick and one {@code Cmd+P}</b>, both in the column. Every
 * {@code WHEN_IN_FOCUSED_WINDOW} binding in a window shares one map, so five instruments each
 * installing the shortcut would be five things fighting over it. The debugger's F5, F8, F9 and F10
 * are the only per-instrument keys there are, and there is only ever one debugger.
 */
public final class ControlPanelFrame extends JFrame {
    /**
     * How often the Pause tick is brought into line with the machine. The same quarter second every
     * instrument sweeps on, and two reads of a boolean.
     */
    private static final int REFRESH_MILLIS = 250;

    /**
     * The margin the window is kept inside the display by, when what it asks for is more than there
     * is. A window wider than the screen is one whose column nobody can reach.
     */
    private static final int MARGIN = 60;


    private final Switches switches;
    private final PauseControl pauseControl;

    private final JTabbedPane tabs = new JTabbedPane();
    private final Dashboard dashboard = new Dashboard();

    private final @Nullable Component parent;

    /**
     * Whichever machine is running, so that the readout can be switched on when the window is shown
     * and off when it is put away. Null before the first one arrives.
     */
    private @Nullable EmulatorRunner runner;

    private @Nullable DebuggerPanel debugger;
    private @Nullable Instruments instruments;

    /**
     * Whether the window has been given a size yet. It is packed around its instruments the first
     * time a machine arrives rather than opened at a number picked here: the debugger decides how
     * wide it has to be and the column how much wider, and a number would be either too small for
     * a desk or too large for a laptop the day either of them changes.
     */
    private boolean sized;

    /**
     * Which tab the config file said was in front, held until there are tabs to put it in front of.
     * Cleared once it has been used, so that a power cycle keeps whatever is in front now rather
     * than going back to whatever was in front when the program started.
     */
    private @Nullable String remembered;

    public ControlPanelFrame(
            final @Nullable Component parent,
            final Switches switches,
            final Commands commands,
            final PauseControl pauseControl,
            final Layout layout) {

        this.switches = switches;
        this.pauseControl = pauseControl;
        this.parent = parent;
        this.remembered = layout.tab();

        setTitle("Control Panel");
        setLayout(new BorderLayout());

        // Never in two rows. A tab strip that wraps moves every tab whenever the window is resized,
        // which is the one thing a list of places has to not do.
        tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        add(dashboard, BorderLayout.NORTH);
        add(tabs, BorderLayout.CENTER);
        add(new JScrollPane(new ControlsColumn(switches, commands)), BorderLayout.EAST);

        // A tick over a machine nobody is clocking cannot stop it, and says so rather than lying
        // about it -- which is the case a test and the README's camera are in.
        switches.pause().setEnabled(pauseControl.isReal());

        installPauseShortcut();
        applyLayout(layout);

        // Nothing is read off the machine and nothing is posted to this thread while the window is
        // put away, which is nearly always: the Debugger.isArmed() rule, for the dashboard.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                observeTheMachine();
            }
        });

        // A window put away is not a machine that is meant to stay stopped. Closing this while the
        // debugger has it standing at a breakpoint would otherwise leave a frozen emulator with the
        // only way out buried in a menu, which looks exactly like a crash.
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                if (debugger != null) {
                    debugger.closing();
                }
            }
        });

        Sweep.every(REFRESH_MILLIS, this, this::followTheMachine);
    }

    /**
     * Points the window at a machine, which is also what a power cycle and a region change bring.
     * <p>
     * The debugger is repointed and the rest are rebuilt, which is exactly what the five windows
     * did one at a time and for the same two reasons. A viewer's contents are entirely derived from
     * the machine, so there is nothing in one worth carrying over; the debugger holds the user's own
     * work -- the breakpoints, the address they were looking at -- and throwing that away on a power
     * cycle would discard the very thing they cycled the power to test.
     * <p>
     * Which tab is in front is carried over either way, because that is the window's arrangement
     * rather than any machine's.
     */
    public void setMachine(
            final NES nes,
            final EmulatorRunner runner,
            final Debugger points,
            final Cart cart,
            final NESPalette colours) {

        if (this.runner != null) {
            this.runner.setFrameObserver(null);
        }

        this.runner = runner;

        observeTheMachine();

        if (debugger == null) {
            debugger = new DebuggerPanel(nes, runner, points);
            debugger.installKeysIn(getRootPane());
        } else {
            debugger.setMachine(nes, runner);
        }

        var inFront = tabs.getSelectedIndex();

        instruments = new Instruments(nes, cart, colours);

        tabs.removeAll();

        // First, and not in a scroll pane: it is the main view, and it is the one instrument that
        // lays itself out into whatever room it is given rather than asking for a fixed size.
        tabs.addTab("Debugger", debugger);
        instruments.addTo(tabs);

        if (remembered != null) {
            select(remembered);
            remembered = null;
        } else if (inFront >= 0 && inFront < tabs.getTabCount()) {
            tabs.setSelectedIndex(inFront);
        }

        if (!sized) {
            sized = true;
            packInsideTheDisplay();
            setLocationRelativeTo(parent);
        }
    }

    /**
     * The first line of the dashboard: how the machine is being run, which only the thing running
     * it knows. The other two lines come off the machine itself.
     */
    public void setRunning(final String text) {
        dashboard.setRunning(text);
    }

    /**
     * Draws every instrument in {@code colours} from now on, following Settings &gt; Palette...
     */
    public void setPalette(final NESPalette colours) {
        if (instruments != null) {
            instruments.setPalette(colours);
        }
    }

    /**
     * The machine has stopped. Called on the event dispatch thread with it already halted, which is
     * the only moment reading it is legal.
     */
    public void stopped(final Debugger.Stop stop) {
        if (debugger != null) {
            debugger.stopped(stop);
        }
    }

    /**
     * The machine is going again, so what the debugger is showing is a photograph rather than a
     * machine.
     */
    public void running() {
        if (debugger != null) {
            debugger.running();
        }
    }

    /**
     * Where the window is and how it is arranged, for the config file to keep. Not {@code layout},
     * which {@link java.awt.Container} took in 1996 and deprecated in 1998.
     */
    public Layout currentLayout() {
        var inFront = tabs.getSelectedIndex();

        return new Layout(getBounds(), inFront < 0 ? null : tabs.getTitleAt(inFront));
    }

    /**
     * Lets the machine go on the way out, which is the debugger's rule rather than the window's:
     * closing this while it has the machine stopped at a breakpoint would leave a frozen emulator
     * with the only way out buried in a menu, which looks exactly like a crash.
     */
    @Override
    public void dispose() {
        if (debugger != null) {
            debugger.closing();
        }

        super.dispose();
    }

    // ================================================================================== internals

    /**
     * The instruments a machine brings with it, built and thrown away together.
     */
    private record Instruments(
            NametableViewerPanel nametables,
            OAMViewerPanel sprites,
            PaletteViewerPanel palette,
            CHRViewerPanel tiles,
            SoundPanel sound,
            CartridgePanel cartridge,
            PadsPanel pads) {

        private Instruments(final NES nes, final Cart cart, final NESPalette colours) {
            this(
                    new NametableViewerPanel(nes, colours),
                    new OAMViewerPanel(nes.getPPU(), colours),
                    new PaletteViewerPanel(nes.getPPU(), colours),
                    new CHRViewerPanel(cart, nes.getPPU(), colours),
                    new SoundPanel(),
                    new CartridgePanel(cart),
                    new PadsPanel());
        }

        /**
         * The pictures in the order the questions come in -- what is on the screen, what is over
         * it, what colours both are drawn through, and only then what the game has to draw with --
         * and then the three parts of the machine that have no picture at all: what it sounds
         * like, what it is made of, and what it is being told.
         */
        void addTo(final JTabbedPane pane) {
            pane.addTab("Nametables", fixed(nametables));
            pane.addTab("Sprites", filling(sprites));
            pane.addTab("Palette", fixed(palette));
            pane.addTab("Tiles", fixed(tiles));
            pane.addTab("Sound", filling(sound));
            pane.addTab("Cartridge", filling(cartridge));
            pane.addTab("Pads", filling(pads));
        }

        void setPalette(final NESPalette colours) {
            nametables.setPalette(colours);
            sprites.setPalette(colours);
            palette.setPalette(colours);
            tiles.setPalette(colours);
        }

        void show(final Readout readout) {
            sound.show(readout);
            cartridge.show(readout);
            pads.show(readout);
        }
    }

    /**
     * An instrument with something in it worth making bigger, given the whole tab.
     * <p>
     * Only the sprites, and only because of the table: sixty four rows in a window packed to show
     * thirteen of them is the one place here where a taller tab is worth something.
     */
    private static JComponent filling(final JComponent instrument) {
        return scrolling(instrument);
    }

    /**
     * An instrument that is a raster of a fixed size, kept at it and centred in the room left over.
     * <p>
     * This is not decoration. A {@link javax.swing.JViewport} <em>grows</em> a view smaller than
     * itself, and every one of these was built to be packed into a window of its own -- so the
     * options row under a raster ended up pinned to the bottom of the tab with a hand's width of
     * nothing above it, and anything that draws across the whole of its component came out
     * stretched. A {@link GridBagLayout} holding one thing leaves it at the size it asked for, and
     * is what the viewport grows instead.
     */
    private static JComponent fixed(final JComponent instrument) {
        var holder = new JPanel(new GridBagLayout());

        holder.add(instrument, new GridBagConstraints());

        return scrolling(holder);
    }

    private static JComponent scrolling(final JComponent view) {
        var pane = new JScrollPane(view);

        pane.setBorder(null);
        pane.getVerticalScrollBar().setUnitIncrement(16);

        return pane;
    }

    /**
     * Asks the machine for a readout while this window is on screen, and stops asking when it is
     * not.
     */
    private void observeTheMachine() {
        if (runner == null) {
            return;
        }

        if (isShowing()) {
            runner.setFrameObserver(this::describe);
        } else {
            runner.setFrameObserver(null);
            dashboard.clear();
        }
    }

    /**
     * A frame has finished. Called on the event dispatch thread with a record that holds no
     * reference to the machine -- see {@link Readout} -- which is what makes it safe to look at
     * after the machine has gone on running.
     * <p>
     * Public because the README's camera hands one over the same way, over a machine that is
     * stopped exactly where the picture wants it and has no thread clocking it to send one.
     */
    public void describe(final Readout readout) {
        dashboard.show(readout);

        if (instruments != null) {
            instruments.show(readout);
        }

        if (debugger != null) {
            debugger.readout(readout);
        }
    }

    private void select(final String tab) {
        for (var index = 0; index < tabs.getTabCount(); index++) {
            if (tab.equals(tabs.getTitleAt(index))) {
                tabs.setSelectedIndex(index);
                return;
            }
        }
    }

    /**
     * Brings the Pause tick into line with the machine, whoever stopped it.
     * <p>
     * Asked rather than told, because a machine stops and starts for reasons that never touch a
     * tick: a breakpoint, the debugger's own Run button, a movie ending. {@code set} rather than
     * anything that notifies, so that following the machine cannot turn into telling it what to do.
     */
    private void followTheMachine() {
        if (pauseControl.isReal() && switches.pause().isOn() != pauseControl.isPaused()) {
            switches.pause().set(pauseControl.isPaused());
        }
    }

    /**
     * The Machine menu's own Pause shortcut, on this window too, because the window that has the
     * keyboard is the window the shortcut has to work in. The stroke is read off the switch rather
     * than spelled again, so this and the menu item cannot come to disagree about which key it is.
     */
    private void installPauseShortcut() {
        var stroke = (KeyStroke) switches.pause().getValue(Action.ACCELERATOR_KEY);

        if (stroke == null) {
            return;
        }

        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, "mynes.pause");
        getRootPane().getActionMap().put("mynes.pause", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                if (switches.pause().isEnabled()) {
                    switches.pause().press();
                }
            }
        });
    }

    /**
     * Puts the window back where it was, or somewhere sensible the first time.
     * <p>
     * <b>Remembered bounds are checked against the displays there actually are.</b> A window sized
     * on a second monitor and reopened without one would otherwise come up somewhere nobody can
     * reach, which looks exactly like a window that failed to open.
     */
    private void applyLayout(final Layout layout) {
        var bounds = layout.bounds();

        if (bounds != null && isOnAScreen(bounds)) {
            setBounds(bounds);
            sized = true;
        }
    }

    /**
     * The size the window's own contents ask for, or as much of it as the display has.
     * <p>
     * Packed rather than given a number, because what it needs is whatever the widest tab needs and
     * that is the debugger's business rather than this class's -- a number here would be a second
     * opinion about the debugger's layout, wrong the day either changes. The clamp is for the
     * laptop that has less than the debugger wants: better a window with a scrollbar in it than one
     * whose column is off the side of the screen.
     */
    private void packInsideTheDisplay() {
        pack();

        if (GraphicsEnvironment.isHeadless()) {
            return;
        }

        var screen = getGraphicsConfiguration().getBounds();

        setSize(
                Math.min(getWidth(), screen.width - MARGIN),
                Math.min(getHeight(), screen.height - MARGIN));
    }

    private static boolean isOnAScreen(final Rectangle bounds) {
        if (GraphicsEnvironment.isHeadless()) {
            return false;
        }

        for (var device : GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            if (device.getDefaultConfiguration().getBounds().intersects(bounds)) {
                return true;
            }
        }

        return false;
    }
}
