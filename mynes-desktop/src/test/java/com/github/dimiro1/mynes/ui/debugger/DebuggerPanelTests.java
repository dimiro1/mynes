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
