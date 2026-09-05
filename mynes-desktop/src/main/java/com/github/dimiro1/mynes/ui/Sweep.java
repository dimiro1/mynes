package com.github.dimiro1.mynes.ui;

import javax.swing.Timer;
import java.awt.Component;
import java.awt.event.HierarchyEvent;

/**
 * A refresh timer that runs only while the thing it refreshes is on screen.
 * <p>
 * Every debug view here is a poll of a machine that will not hold still, and a poll of a machine
 * nobody is looking at is work done for nothing -- or worse, work done against a machine that has
 * been thrown away, which is what a viewer left running across a power cycle would do. The windows
 * used to answer that twice over: a {@code dispose} that stopped the timer, and an
 * {@code isShowing} guard inside the tick for the window that was merely put away.
 * <p>
 * A panel has no {@code dispose}, and once several of them are tabs in one window most of them are
 * behind another tab at any moment -- so the timer follows {@code isShowing} itself, which is the
 * one question both of those were really asking. It is false for a tab that is not in front, for a
 * window that is minimised or closed, and for a panel that was built into an image and never shown
 * at all, which is how a test gets a panel that polls nothing. A window is a {@link Component}
 * too, which is what lets the control panel sweep on being open rather than on some piece of
 * itself.
 */
public final class Sweep {
    private Sweep() {
    }

    /**
     * Runs {@code work} every {@code millis} for as long as {@code showing} is on screen.
     * <p>
     * Nothing is handed back, and there is nothing to stop: the timer is reachable only from the
     * listener the component itself is holding, so a component that goes away takes it with it.
     *
     * @param showing whose being on screen decides whether the work is worth doing, which is
     *                normally the panel {@code work} refreshes.
     */
    public static void every(final int millis, final Component showing, final Runnable work) {
        var timer = new Timer(millis, e -> work.run());

        showing.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                return;
            }

            if (showing.isShowing()) {
                timer.start();
            } else {
                timer.stop();
            }
        });

        // A component is not on screen while it is being built, since nothing has been given it to
        // be on screen in -- but a caller is free to sweep something that has been up for a while,
        // and one that did would otherwise wait for a change that has already happened.
        if (showing.isShowing()) {
            timer.start();
        }
    }
}
