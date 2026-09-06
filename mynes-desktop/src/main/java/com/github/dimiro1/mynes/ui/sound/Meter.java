package com.github.dimiro1.mynes.ui.sound;

import com.github.dimiro1.mynes.ui.debugger.Theme;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * How loud one voice has been, as a bar.
 * <p>
 * A peak rather than a level: what the readout carries is the loudest that voice has been since the
 * last one, a quarter of a second ago, because a level sampled four times a second from a wave
 * oscillating hundreds of times a second is a random number. A peak over the same window is the
 * thing a meter is for -- it says "this voice made a sound recently", which is exactly the question
 * somebody looking at a silent game is asking.
 */
final class Meter extends JComponent {
    private static final int WIDTH = 84;
    private static final int HEIGHT = 9;

    /**
     * How many blocks the bar is drawn in. A run of blocks reads as a level at a glance where a
     * smooth bar reads as a progress bar, and eight of them is as fine as this is worth being: the
     * four voices with an envelope have sixteen steps and nobody is reading the difference between
     * two of them.
     */
    private static final int BLOCKS = 8;

    private double level;

    Meter() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setMinimumSize(getPreferredSize());
    }

    void setLevel(final double level) {
        if (level != this.level) {
            this.level = level;
            repaint();
        }
    }

    @Override
    protected void paintComponent(final Graphics g) {
        var lit = (int) Math.ceil(level * BLOCKS);
        var block = getWidth() / BLOCKS;

        for (var i = 0; i < BLOCKS; i++) {
            g.setColor(i < lit ? Theme.running() : Theme.dim());
            g.fillRect(i * block, 0, block - 2, getHeight());
        }
    }
}
