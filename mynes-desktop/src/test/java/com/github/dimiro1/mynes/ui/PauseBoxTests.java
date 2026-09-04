package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.Toolkit;
import java.awt.event.KeyEvent;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The Pause tick the debug windows carry, which has one job beyond looking like a checkbox: it has
 * to follow a machine that four other things can stop.
 */
class PauseBoxTests {
    /**
     * A machine that can be stopped and asked whether it is stopped, and nothing else.
     */
    private static final class Machine implements PauseControl {
        private boolean paused;
        private int told;

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public void setPaused(final boolean paused) {
            this.paused = paused;
            told++;
        }
    }

    @Test
    void tickingItStopsTheMachine() {
        var machine = new Machine();
        var box = new PauseBox(machine);

        assertFalse(box.isSelected());

        box.doClick();
        assertTrue(machine.paused, "and the machine hears about it once");
        assertEquals(1, machine.told);

        box.doClick();
        assertFalse(machine.paused);
        assertEquals(2, machine.told);
    }

    /**
     * The case the tick exists for as much as its own: a breakpoint, or the Machine menu, or one of
     * the other three debug windows has stopped the machine, and this one has to catch up.
     */
    @Test
    void itFollowsAMachineSomebodyElseStopped() {
        var machine = new Machine();
        var box = new PauseBox(machine);

        machine.paused = true;
        box.refresh();

        assertTrue(box.isSelected());
        assertEquals(0, machine.told, "following is not telling: the machine hears nothing");

        machine.paused = false;
        box.refresh();

        assertFalse(box.isSelected());
        assertEquals(0, machine.told);
    }

    /**
     * A window over a machine nothing is clocking -- a test, or the README's camera -- gets a tick
     * that says so rather than one that lies about being able to stop it.
     */
    @Test
    void thereIsNothingToTickWhenNobodyIsRunningTheMachine() {
        var box = new PauseBox(PauseControl.NONE);

        assertFalse(box.isEnabled());

        box.refresh();
        assertFalse(box.isSelected());
    }

    @Test
    void theMachineMenusShortcutWorksInTheWindowItIsPutIn() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no toolkit to ask for the shortcut key");

        var root = new JRootPane();
        var box = new PauseBox(new Machine());

        box.installIn(root);

        var stroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());
        var name = root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke);

        assertNotNull(name, "the shortcut is not bound");
        assertNotNull(root.getActionMap().get(name), "and nothing happens when it is pressed");
    }

    @Test
    void aWindowOverNothingGetsNoShortcutEither() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no toolkit to ask for the shortcut key");

        var root = new JRootPane();

        new PauseBox(PauseControl.NONE).installIn(root);

        var stroke = KeyStroke.getKeyStroke(
                KeyEvent.VK_P, Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx());

        assertEquals(
                null,
                root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).get(stroke),
                "a shortcut that did nothing would be worse than none");
    }
}
