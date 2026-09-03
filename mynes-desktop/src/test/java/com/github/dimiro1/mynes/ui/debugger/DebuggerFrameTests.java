package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.AudioOutput;
import com.github.dimiro1.mynes.ui.EmulatorRunner;
import com.github.dimiro1.mynes.ui.ScreenComponent;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Builds the whole window and drives it through both of its states.
 * <p>
 * Nothing here asserts on what it looks like -- that is a job for eyes. What it catches is the class
 * of mistake that only shows up when the thing is actually built: a MigLayout constraint that does
 * not parse, a renderer that throws on its first row, a panel handed a snapshot before it has one.
 * All of those compile perfectly and fail the moment the window opens.
 * <p>
 * Skipped where there is no display, which includes the CI machine. A {@link javax.swing.JFrame}
 * needs a native peer and there is nothing to be done about that; the parts that do not -- the
 * panels, and {@link MachineSnapshot} -- are tested without one.
 */
class DebuggerFrameTests {
    private static NES nes;
    private static Debugger debugger;
    private static EmulatorRunner runner;

    @BeforeAll
    static void machine() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to put a window on");

        nes = new NES(Cart.load(rom(), "debugger-frame.nes"));
        debugger = new Debugger();
        debugger.attach(nes);

        // Never started, so the machine is this thread's throughout and nothing below races.
        runner = new EmulatorRunner(nes, new ScreenComponent(), debugger, 0,
                AudioOutput.DEFAULT_LATENCY_MS);
    }

    @Test
    void theWindowBuildsAndShowsAStoppedMachine() throws Exception {
        onSwingThread(() -> {
            var frame = new DebuggerFrame(null, nes, runner, debugger);

            try {
                frame.stopped(
                        new Debugger.Stop(
                                Debugger.Reason.BREAKPOINT, 0x8000, null, -1, -1, -1));
                frame.running();
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void aWatchpointStopIsDescribedRatherThanCrashingOnItsExtraFields() throws Exception {
        onSwingThread(() -> {
            var frame = new DebuggerFrame(null, nes, runner, debugger);

            try {
                frame.stopped(new Debugger.Stop(
                        Debugger.Reason.WATCHPOINT,
                        0x8003,
                        Debugger.Access.WRITE,
                        0x0300,
                        0x42,
                        0x8000));
                frame.stopped(new Debugger.Stop(
                        Debugger.Reason.WATCHPOINT,
                        0x8003,
                        Debugger.Access.READ,
                        0x0300,
                        0x42,
                        0x8000));
            } finally {
                frame.dispose();
            }
        });
    }

    /**
     * The points panel has to render both kinds of point, and a conditional breakpoint's extra
     * column is exactly the sort of thing that compiles and then throws on the first row.
     */
    @Test
    void bothKindsOfPointAreListedRatherThanCrashingOnTheirExtraColumns() throws Exception {
        onSwingThread(() -> {
            var frame = new DebuggerFrame(null, nes, runner, debugger);

            debugger.addBreakpoint(0x8000, Condition.parse("a == $10"));
            debugger.addBreakpoint(0x8003);
            debugger.addWatchpoint(0x0300, Debugger.Access.READ);
            debugger.addWatchpoint(0x0301, Debugger.Access.BOTH);

            try {
                frame.stopped(new Debugger.Stop(
                        Debugger.Reason.BREAKPOINT, 0x8000, null, -1, -1, -1));
            } finally {
                frame.dispose();
                debugger.clear();
            }
        });
    }

    @Test
    void aFreshWindowSurvivesBeingPointedAtAnotherMachine() throws Exception {
        onSwingThread(() -> {
            var frame = new DebuggerFrame(null, nes, runner, debugger);

            try {
                frame.setMachine(nes, runner);
                frame.stopped(
                        new Debugger.Stop(
                                Debugger.Reason.STEP, 0x8000, null, -1, -1, 0x8000));
            } finally {
                frame.dispose();
            }
        });
    }

    /**
     * Swing wants its components built on its own thread, and an exception thrown over there would
     * otherwise be logged and swallowed rather than failing anything.
     */
    private static void onSwingThread(final Runnable work) throws Exception {
        var failure = new Exception[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                work.run();
            } catch (RuntimeException | Error e) {
                failure[0] = new Exception(e);
            }
        });

        assertDoesNotThrow(() -> {
            if (failure[0] != null) {
                throw failure[0];
            }
        });
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
