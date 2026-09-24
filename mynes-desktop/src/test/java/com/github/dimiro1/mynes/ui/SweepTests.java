package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The rule every debug view's refresh timer keeps: it runs while somebody is looking and not
 * otherwise.
 * <p>
 * <b>The two halves are not checked the same way, and cannot be.</b> That something eventually
 * happens is waited for, since the only wrong answer a wait can give is a slow machine reported as
 * a broken one. That something never happens has nothing to wait for, so it is a fixed pause and
 * then a look -- which is sound here because the timer is stopped on the event dispatch thread
 * before the pause begins.
 */
class SweepTests {
    /**
     * Fast enough that a tick is due many times over inside any of the waits below, so a count of
     * zero means the timer never started rather than that it was not given long enough.
     */
    private static final int FAST = 5;

    /**
     * How long to watch for something that should not happen. Only the negative halves use it: a
     * pause is the only way to check an absence, and there is no length that makes one certain.
     */
    private static final int LONG_ENOUGH_MILLIS = 200;

    /**
     * How long to wait for something that should. Generous to the point of being silly against the
     * five milliseconds a tick is due in, because the cost of being wrong is asymmetric -- too
     * short is a red build nobody can reproduce, and too long is a few milliseconds on the one run
     * where the machine was busy. A passing test never waits anything like this.
     */
    private static final int PATIENCE_MILLIS = 10_000;

    private static final int POLL_MILLIS = 5;

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

        try {
            // The precondition the assumption above is only a proxy for. isHeadless() is whether
            // the JVM has a display at all, which is a weaker question than whether the window
            // just asked for reached the screen -- so the panel is asked instead, and a machine
            // that cannot show one has nothing to sweep and nothing to say about sweeping.
            assumeTrue(eventually(panel::isShowing), "the window never reached the screen");

            assertTrue(eventually(() -> ticks.get() > 0), "a view on screen is refreshed");
        } finally {
            // In a finally so that a failure above leaves no window behind on somebody's desk.
            SwingUtilities.invokeAndWait(frame[0]::dispose);
        }

        // The timer is stopped on the event dispatch thread, so let whatever was already queued
        // arrive before deciding it has stopped.
        SwingUtilities.invokeAndWait(() -> {
        });

        var atClose = ticks.get();

        Thread.sleep(LONG_ENOUGH_MILLIS);

        assertEquals(atClose, ticks.get(), "and a view that has gone is not");
    }

    /**
     * Waits for something to become true, rather than sleeping a length somebody picked by eye and
     * hoping it was enough.
     * <p>
     * The window above is what makes that distinction matter. Putting the first one of a JVM's life
     * on screen is the most expensive thing the event dispatch thread ever does -- a native peer, a
     * graphics pipeline, a font or two -- and on a cold or loaded machine it can hold that thread
     * long enough for a fixed pause to expire with the timer started and not yet run. Which reads
     * as "the timer never started", and is the one answer this test must not give by accident.
     *
     * @return whether it became true before {@link #PATIENCE_MILLIS} ran out.
     */
    private static boolean eventually(final BooleanSupplier condition) throws InterruptedException {
        var deadline = System.nanoTime() + PATIENCE_MILLIS * 1_000_000L;

        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline >= 0) {
                return false;
            }

            // On the test's thread rather than the dispatch thread, which is the whole point:
            // waiting here is what leaves that one free to get to the work being waited for.
            Thread.sleep(POLL_MILLIS);
        }

        return true;
    }
}
