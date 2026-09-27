package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.AudioOutput;
import com.github.dimiro1.mynes.ui.EmulatorRunner;
import com.github.dimiro1.mynes.ui.ScreenComponent;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JTabbedPane;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the whole view, drives it through both of its states, and paints it.
 * <p>
 * Nothing here asserts on what it looks like -- that is a job for eyes. What it catches is the class
 * of mistake that only shows up when the thing is actually built: a MigLayout constraint that does
 * not parse, a renderer that throws on its first row, a panel handed a snapshot before it has one.
 * All of those compile perfectly and fail the moment the view is drawn.
 * <p>
 * This used to be a window and used to be skipped on any machine without a display, which is every
 * machine that runs the build. It is a panel now, so this runs everywhere.
 */
class DebuggerPanelTests {
    private static NES nes;
    private static Debugger debugger;
    private static EmulatorRunner runner;

    @BeforeAll
    static void machine() {
        nes = new NES(Cart.load(rom(), "debugger-panel.nes"));
        debugger = new Debugger();
        debugger.attach(nes);

        // Never started, so the machine is this thread's throughout and nothing below races.
        runner = new EmulatorRunner(nes, new ScreenComponent(), debugger, 0,
                AudioOutput.DEFAULT_LATENCY_MS);
    }

    @Test
    void theViewBuildsAndShowsAStoppedMachine() {
        var view = new DebuggerPanel(nes, runner, debugger);

        view.stopped(new Debugger.Stop(Debugger.Reason.BREAKPOINT, 0x8000, null, -1, -1, -1));
        Views.paint(view);

        view.running();
        Views.paint(view);
    }

    @Test
    void aWatchpointStopIsDescribedRatherThanCrashingOnItsExtraFields() {
        var view = new DebuggerPanel(nes, runner, debugger);

        view.stopped(new Debugger.Stop(
                Debugger.Reason.WATCHPOINT, 0x8003, Debugger.Access.WRITE, 0x0300, 0x42, 0x8000));
        Views.paint(view);

        view.stopped(new Debugger.Stop(
                Debugger.Reason.WATCHPOINT, 0x8003, Debugger.Access.READ, 0x0300, 0x42, 0x8000));
        Views.paint(view);
    }

    /**
     * The points panel has to render both kinds of point, and a conditional breakpoint's extra
     * column is exactly the sort of thing that compiles and then throws on the first row.
     */
    @Test
    void bothKindsOfPointAreListedRatherThanCrashingOnTheirExtraColumns() {
        var view = new DebuggerPanel(nes, runner, debugger);

        debugger.addBreakpoint(0x8000, Condition.parse("a == $10"));
        debugger.addBreakpoint(0x8003);
        debugger.addWatchpoint(0x0300, Debugger.Access.READ);
        debugger.addWatchpoint(0x0301, Debugger.Access.BOTH);

        try {
            view.stopped(new Debugger.Stop(Debugger.Reason.BREAKPOINT, 0x8000, null, -1, -1, -1));
            Views.paint(view);
        } finally {
            debugger.clear();
        }
    }

    @Test
    void aFreshViewSurvivesBeingPointedAtAnotherMachine() {
        var view = new DebuggerPanel(nes, runner, debugger);

        view.setMachine(nes, runner);
        view.stopped(new Debugger.Stop(Debugger.Reason.STEP, 0x8000, null, -1, -1, 0x8000));
        Views.paint(view);
    }

    @Test
    void stateTreeAndDetailTabsKeepTheCodeViewUncluttered() {
        var view = new DebuggerPanel(nes, runner, debugger);
        view.stopped(new Debugger.Stop(Debugger.Reason.STEP, 0x8000, null, -1, -1, 0x8000));

        var tree = Views.find(view, JTree.class);
        assertNotNull(tree);
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        assertEquals(6, root.getChildCount());
        assertEquals("CPU", root.getChildAt(0).toString());
        assertEquals("PPU", root.getChildAt(1).toString());
        assertEquals("APU", root.getChildAt(2).toString());
        assertEquals("Input", root.getChildAt(3).toString());
        assertEquals("Stack", root.getChildAt(4).toString());
        assertEquals("Variables", root.getChildAt(5).toString());
        assertTrue(tree.isExpanded(new TreePath(((DefaultMutableTreeNode) root.getChildAt(0)).getPath())));
        assertFalse(tree.isExpanded(new TreePath(((DefaultMutableTreeNode) root.getChildAt(1)).getPath())));

        var program = new SourceProgram(Path.of("game.dbg"), List.of(), Map.of(), List.of(),
                List.of(new SourceProgram.Symbol("counter", "lab", 0x20, null, null, List.of())),
                0x4000, List.of());
        Views.find(view, RegistersPanel.class).setSourceProgram(program);
        var variables = (DefaultMutableTreeNode) root.getChildAt(5);
        assertTrue(variables.getChildAt(0).toString().startsWith("counter   $0020: $"));

        var tabs = detailTabs(view);
        assertNotNull(tabs);
        assertEquals("Memory", tabs.getTitleAt(tabs.getSelectedIndex()));
        Views.paint(view);

        tabs.setSelectedIndex(1);
        var pc = (DefaultMutableTreeNode) ((DefaultMutableTreeNode) root.getChildAt(0)).getChildAt(0);
        var bounds = tree.getPathBounds(new TreePath(pc.getPath()));
        assertNotNull(bounds);
        tree.dispatchEvent(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, bounds.x + 4, bounds.y + bounds.height / 2,
                2, false, MouseEvent.BUTTON1));
        assertEquals("Memory", tabs.getTitleAt(tabs.getSelectedIndex()));
    }

    private static JTabbedPane detailTabs(final Container parent) {
        for (var child : parent.getComponents()) {
            if (child instanceof JTabbedPane tabs && tabs.indexOfTab("Memory") >= 0) {
                return tabs;
            }
            if (child instanceof Container container) {
                var found = detailTabs(container);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        image[16] = 0x4C;
        image[17] = 0x00;
        image[18] = (byte) 0x80;

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
