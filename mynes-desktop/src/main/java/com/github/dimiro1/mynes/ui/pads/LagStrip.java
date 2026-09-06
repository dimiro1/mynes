package com.github.dimiro1.mynes.ui.pads;

import com.github.dimiro1.mynes.ui.debugger.Theme;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * The last couple of seconds, one column per frame, marked where the game never read the pad.
 * <p>
 * A count on its own would say "three frames in the last two seconds", which is a number nobody can
 * act on. What is worth seeing is the <em>pattern</em>, because the pattern is the diagnosis: every
 * other column is a main loop overrunning its frame by a little, which is what
 * {@code --hack overclock} fixes; a solid run is a level being loaded, which is a game doing what
 * it meant to; and one column every few seconds is the host stopping for a moment rather than
 * anything the game did.
 * <p>
 * Newest at the right, because that is the direction time runs in every other graph. It arrives
 * fifteen frames at a time, since that is how often a readout is taken, so the strip walks left in
 * steps rather than smoothly.
 */
final class LagStrip extends JComponent {
    private static final int HEIGHT = 22;

    /**
     * How wide one frame is drawn, and so how wide the whole strip wants to be: three pixels is
     * enough for a single lag frame to be visible on its own without the strip needing half the
     * window.
     */
    private static final int COLUMN = 3;

    private boolean[] polled = new boolean[0];

    LagStrip(final int frames) {
        setPreferredSize(new Dimension(frames * COLUMN, HEIGHT));
        setMinimumSize(getPreferredSize());
    }

    void setPolled(final boolean[] polled) {
        this.polled = polled;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        var height = getHeight();
        var columns = Math.max(1, getWidth() / COLUMN);

        // Right aligned: the newest frame is the rightmost column, and a window that has not filled
        // up yet is short at the old end rather than at the new one.
        var offset = columns - polled.length;

        for (var i = 0; i < polled.length; i++) {
            var at = offset + i;

            if (at < 0) {
                continue;
            }

            // A frame the game read the pad in is a mark on the floor and a frame it did not is a
            // bar the height of the strip, rather than two colours of the same bar: what is being
            // looked for is rare, and rare things should be the tall ones. The floor is drawn in
            // the muted colour rather than the dim one so that a game with no lag at all still has
            // a strip, which is the difference between "nothing to report" and "nothing here".
            g.setColor(polled[i] ? Theme.muted() : Theme.stopped());
            g.fillRect(at * COLUMN, polled[i] ? height - 3 : 0, COLUMN - 1, polled[i] ? 3 : height);
        }
    }
}
