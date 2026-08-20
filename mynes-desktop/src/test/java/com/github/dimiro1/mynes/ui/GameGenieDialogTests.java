package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.cheat.GameGenieCode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Builds the dialog, empty and full.
 * <p>
 * Nothing here asserts on what it looks like, the same as {@code DebuggerFrameTests}. What it catches
 * is the class of mistake that only shows up when the thing is actually built: a MigLayout constraint
 * that does not parse, a list renderer that throws on its first row. Both compile perfectly and fail
 * the moment somebody opens the menu.
 * <p>
 * Skipped where there is no display, which includes the CI machine. What the dialog actually decides
 * -- whether six letters are a code, and what two codes for one address mean -- is in the core and is
 * tested without a window anywhere near it.
 */
class GameGenieDialogTests {

    @BeforeAll
    static void display() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to put a window on");
    }

    @Test
    void anEmptyDialogBuilds() throws Exception {
        onSwingThread(() -> new GameGenieDialog(null, List.of(), codes -> { }).dispose());
    }

    /**
     * With one of each length in it, so the renderer draws both the row that names a bank and the row
     * that does not.
     */
    @Test
    void aDialogWithCodesAlreadyInItBuilds() throws Exception {
        var codes = List.of(
                GameGenieCode.decode("SXIOPO"),
                GameGenieCode.decode("ZEXPYGLA"));

        onSwingThread(() -> new GameGenieDialog(null, codes, updated -> { }).dispose());
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
}
