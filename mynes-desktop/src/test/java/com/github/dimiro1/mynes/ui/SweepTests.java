package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The rule every debug view's refresh timer keeps: it runs while somebody is looking and not
 * otherwise.
 */
class SweepTests {
    /**
     * Fast enough that a tick would have happened many times over inside the wait below, so a
     * count of zero means the timer never started rather than that it was not given long enough.
     */
    private static final int FAST = 5;

    private static final int LONG_ENOUGH_MILLIS = 200;

    /**
     * The half that matters most, and the one that can be asked anywhere: a view nobody has put on
     * screen polls nothing. That is what stops a test, or the README's camera, from clocking a
     * machine nobody asked it to -- and what used to be a {@code dispose} the caller had to
     * remember.
     */
    @Test
    void aViewThatIsNotOnScreenIsNeverSwept() throws Exception {
        var ticks = new AtomicInteger();
        var panel = new JPanel();

        Sweep.every(FAST, panel, ticks::incrementAndGet);

        Thread.sleep(LONG_ENOUGH_MILLIS);

        assertEquals(0, ticks.get(), "nothing is showing it, so there is nothing to refresh");
    }

    /**
     * And the other half, which needs a window to be on screen in and so only runs where there is
     * one.
     */
    @Test
    void aViewOnScreenIsSweptUntilItGoesAway() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to put a window on");

        var ticks = new AtomicInteger();
        var panel = new JPanel();
        var frame = new JFrame[1];

        SwingUtilities.invokeAndWait(() -> {
            Sweep.every(FAST, panel, ticks::incrementAndGet);

            frame[0] = new JFrame("sweep");
            frame[0].add(panel);
            frame[0].pack();
            frame[0].setVisible(true);
        });

        Thread.sleep(LONG_ENOUGH_MILLIS);

        var swept = ticks.get();
        assertTrue(swept > 0, "a view on screen is refreshed");

        SwingUtilities.invokeAndWait(frame[0]::dispose);

        // The timer is stopped on the event dispatch thread, so let whatever was already queued
        // arrive before deciding it has stopped.
        SwingUtilities.invokeAndWait(() -> {
        });

        var atClose = ticks.get();

        Thread.sleep(LONG_ENOUGH_MILLIS);

        assertEquals(atClose, ticks.get(), "and a view that has gone is not");
    }
}
