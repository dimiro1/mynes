package com.github.dimiro1.mynes.ui.sound;

import com.github.dimiro1.mynes.ui.debugger.Theme;

import javax.swing.JComponent;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * The waveform the five voices add up to, over the last frame.
 * <p>
 * The one thing in this window that is about the sound rather than about the chip. Every number
 * beside it describes a register; this describes what came out -- which is where a voice fighting
 * another one, a DMC sample sitting as an offset under everything, or the clipping that a game
 * writing full volume on all five gets, are all obvious and none of them is in a register anywhere.
 * <p>
 * Decimated rather than averaged on the way here, which is
 * {@link com.github.dimiro1.mynes.ui.EmulatorRunner}'s doing and is deliberate: averaging is a low
 * pass, and it would take the corners off the square wave the pulses actually make.
 */
final class Scope extends JComponent {
    private static final int HEIGHT = 96;

    /**
     * What the top and bottom of the box are worth, which is <em>not</em> the top of the sixteen bit
     * range.
     * <p>
     * Measured rather than assumed: fifteen seconds of Super Mario Bros.' first level peaks at 0.20
     * of full scale and averages 0.02. Two high passes take the DC out on the way here -- the 90Hz
     * one is the coupling capacitor, and is what stops a DMC sample sitting as an offset under
     * everything -- so what reaches this is the swing rather than the level, and a trace drawn
     * against the whole range is a flat line with a wobble in it.
     * <p>
     * Fixed rather than scaled to whatever is in the buffer, which is the important half: a scope
     * that normalised itself would draw silence at full scale the moment the last note ended. What
     * is louder than this clips against the edge, which is a truthful thing for a scope to do.
     */
    private static final double FULL_SCALE = Short.MAX_VALUE * 0.2;

    private final Color colour;
    private final double fullScale;

    /**
     * Whether zero is the middle of the box or the bottom of it.
     * <p>
     * The mixed trace swings both ways -- two high passes have taken the DC out of it by the time
     * it gets here -- so it is drawn about the middle. A voice's own level never goes below zero,
     * so drawing it about the middle would waste half the box and halve the detail; it is drawn
     * off the floor instead, which is also what a level looks like.
     */
    private final boolean centred;

    private short[] samples = new short[0];

    /**
     * The mixed output, scaled to what a game actually puts out.
     */
    Scope() {
        this(Theme.running(), FULL_SCALE, HEIGHT, true);
    }

    /**
     * One voice on its own, in its own colour and against its own full scale -- see
     * {@link Traces#fullScaleOf}.
     */
    Scope(final Color colour, final double fullScale, final int height, final boolean centred) {
        this.colour = colour;
        this.fullScale = fullScale;
        this.centred = centred;

        setPreferredSize(new Dimension(480, height));
        setMinimumSize(new Dimension(120, height));
    }

    void show(final short[] samples) {
        this.samples = samples;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        var g2 = (Graphics2D) g.create();

        try {
            var zero = centred ? getHeight() / 2 : getHeight() - 1;
            var reach = centred ? getHeight() / 2 : getHeight() - 1;

            g2.setColor(Theme.dim());
            g2.drawLine(0, zero, getWidth(), zero);

            if (samples.length < 2) {
                return;
            }

            g2.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(colour);

            var previous = zero;

            for (var x = 0; x < getWidth(); x++) {
                var at = x * (samples.length - 1) / Math.max(1, getWidth() - 1);
                var swing = (int) (samples[at] / fullScale * reach);
                var y = zero - Math.max(-reach, Math.min(reach, swing));

                g2.drawLine(x == 0 ? x : x - 1, previous, x, y);
                previous = y;
            }
        } finally {
            g2.dispose();
        }
    }
}
