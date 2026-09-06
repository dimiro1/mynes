package com.github.dimiro1.mynes.ui.usage;

import com.github.dimiro1.mynes.ui.debugger.Theme;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * The last couple of seconds of how much of each frame the program used, one column per frame.
 * <p>
 * A single percentage is the wrong shape for this question, for the reason the lag strip beside it
 * in the Pads tab is not a count: what matters is the <em>pattern</em>. A game sitting at seventy
 * per cent is a game with room to spare. One alternating between sixty and a hundred is a game
 * whose main loop overruns every other frame, which is the every-other-frame stutter, and it
 * averages to a number that looks perfectly healthy. A run of full columns is a level being loaded.
 * <p>
 * Newest at the right, because that is the direction time runs in every other graph here, and it
 * arrives fifteen frames at a time since that is how often a reading is taken -- so it walks left in
 * steps rather than smoothly.
 * <p>
 * <b>Full scale is one frame and there is no threshold anywhere.</b> A column that reaches the top
 * is a frame with no wait left in it, which is the thing worth seeing; colouring the tall ones
 * differently would mean picking a number to call "too much", and where that number is depends on
 * the game.
 */
final class LoadGraph extends JComponent {
    private static final int HEIGHT = 72;

    /**
     * How wide one frame asks to be drawn when nothing is stretching the graph. Three pixels, the
     * same as the lag strip's, which is enough for one bad frame among good ones to be visible on
     * its own.
     */
    private static final int COLUMN = 3;

    private final int frames;

    private double[] busy = new double[0];

    LoadGraph(final int frames) {
        this.frames = frames;

        setPreferredSize(new Dimension(frames * COLUMN, HEIGHT));
        setMinimumSize(getPreferredSize());
    }

    void show(final double[] busy) {
        this.busy = busy;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        var height = getHeight();
        var width = getWidth();

        // The floor, so that a window nobody has filled yet is still visibly a graph rather than an
        // empty rectangle -- the same reason the lag strip draws a mark on every frame it counted.
        // And the ceiling, which is not decoration: the whole question here is how close to the top
        // the columns are getting, and a bar chart with no line at full scale cannot be read for
        // that at all.
        g.setColor(Theme.dim());
        g.fillRect(0, height - 1, width, 1);
        g.fillRect(0, 0, width, 1);

        // Right aligned: the newest frame is the rightmost column, and a window that has not filled
        // up yet is short at the old end rather than at the new one.
        var offset = frames - busy.length;

        g.setColor(Theme.running());

        for (var i = 0; i < busy.length; i++) {
            // Both edges from the frame number rather than a width worked out once, so that the
            // rounding is shared between neighbours and the columns stay evenly spaced at any
            // width -- the same arithmetic the memory strips use.
            var left = (offset + i) * width / frames;
            var right = (offset + i + 1) * width / frames;

            // At least a pixel, so a frame that was measured and spent almost none of itself is
            // told apart from a frame nobody measured at all.
            var tall = Math.max(1, (int) Math.round(Math.min(1, busy[i]) * height));

            g.fillRect(left, height - tall, Math.max(1, right - left - 1), tall);
        }
    }
}
