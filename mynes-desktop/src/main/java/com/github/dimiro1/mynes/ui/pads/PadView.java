package com.github.dimiro1.mynes.ui.pads;

import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.ui.debugger.Theme;

import javax.swing.JComponent;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * One controller, drawn, with whatever is held lit up.
 * <p>
 * A picture rather than eight ticks in a row, because the shape is the reading: Up and Left
 * together is a diagonal, and Left and Right together -- which a keyboard can send and a moulded
 * cross cannot -- is a bug in whatever is feeding the pad, and both are recognised without being
 * read. The eight flags are all it takes to draw one, so the picture costs nothing that the ticks
 * would not have.
 * <p>
 * The lit colour is the meter's, since it means the same thing in both places: this is happening
 * now.
 */
final class PadView extends JComponent {
    /**
     * A little over 2.6 to 1, which is the shape of the thing.
     */
    private static final int WIDTH = 236;
    private static final int HEIGHT = 92;

    /**
     * How far in from the edge of the component the body is drawn, leaving room for the labels
     * under the two smaller buttons.
     */
    private static final int INSET = 4;

    private static final int CORNER = 10;

    /**
     * The cross: where its centre is, how long an arm is from that centre, and how wide one is.
     */
    private static final int PAD_X = 52;
    private static final int PAD_Y = 44;
    private static final int ARM = 24;
    private static final int ARM_WIDTH = 16;

    /**
     * Select and Start: the size of one, and where the left edge of each sits.
     */
    private static final int SMALL_WIDTH = 30;
    private static final int SMALL_HEIGHT = 10;
    private static final int SELECT_X = 96;
    private static final int START_X = 134;
    private static final int SMALL_Y = 46;

    /**
     * B and A: the diameter of one, and where each is centred.
     */
    private static final int ROUND = 26;
    private static final int B_X = 182;
    private static final int A_X = 214;
    private static final int ROUND_Y = 44;

    /**
     * The two label sizes, made here rather than derived from the component's own font, which is
     * null until something puts it in a window -- and this is painted into an image by a test.
     */
    private static final Font SMALL_LABEL = new Font(Font.SANS_SERIF, Font.PLAIN, 8);
    private static final Font ROUND_LABEL = new Font(Font.SANS_SERIF, Font.PLAIN, 9);

    private int held;

    PadView() {
        setPreferredSize(new Dimension(WIDTH, HEIGHT));
        setMinimumSize(getPreferredSize());
    }

    void setHeld(final int held) {
        if (held != this.held) {
            this.held = held;
            repaint();
        }
    }

    @Override
    protected void paintComponent(final Graphics g) {
        var canvas = (Graphics2D) g.create();

        canvas.setRenderingHint(
                RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        canvas.setColor(Theme.dim());
        canvas.setStroke(new BasicStroke(1));
        canvas.drawRoundRect(
                INSET, INSET, WIDTH - 2 * INSET, HEIGHT - 2 * INSET, CORNER, CORNER);

        cross(canvas);

        small(canvas, SELECT_X, Controller.BUTTON_SELECT, "SELECT");
        small(canvas, START_X, Controller.BUTTON_START, "START");

        round(canvas, B_X, Controller.BUTTON_B, "B");
        round(canvas, A_X, Controller.BUTTON_A, "A");

        canvas.dispose();
    }

    /**
     * The four arms of the cross, each lit on its own. Drawn as four rectangles meeting at the
     * middle rather than as one plus sign, since which of them is lit is the whole question -- and
     * the middle is left in the unlit colour, because nothing is pressing it.
     */
    private void cross(final Graphics2D canvas) {
        var half = ARM_WIDTH / 2;

        arm(canvas, PAD_X - half, PAD_Y - ARM, ARM_WIDTH, ARM - half, Controller.BUTTON_UP);
        arm(canvas, PAD_X - half, PAD_Y + half, ARM_WIDTH, ARM - half, Controller.BUTTON_DOWN);
        arm(canvas, PAD_X - ARM, PAD_Y - half, ARM - half, ARM_WIDTH, Controller.BUTTON_LEFT);
        arm(canvas, PAD_X + half, PAD_Y - half, ARM - half, ARM_WIDTH, Controller.BUTTON_RIGHT);

        canvas.setColor(Theme.dim());
        canvas.fillRect(PAD_X - half, PAD_Y - half, ARM_WIDTH, ARM_WIDTH);
    }

    private void arm(
            final Graphics2D canvas,
            final int x,
            final int y,
            final int width,
            final int height,
            final int button) {

        canvas.setColor(colourOf(button));
        canvas.fillRect(x, y, width, height);
    }

    private void small(
            final Graphics2D canvas, final int x, final int button, final String name) {

        canvas.setColor(colourOf(button));
        canvas.fillRoundRect(x, SMALL_Y, SMALL_WIDTH, SMALL_HEIGHT, 6, 6);

        canvas.setColor(Theme.muted());
        canvas.setFont(SMALL_LABEL);

        var width = canvas.getFontMetrics().stringWidth(name);

        canvas.drawString(name, x + (SMALL_WIDTH - width) / 2, SMALL_Y + SMALL_HEIGHT + 11);
    }

    private void round(
            final Graphics2D canvas, final int x, final int button, final String name) {

        canvas.setColor(colourOf(button));
        canvas.fillOval(x - ROUND / 2, ROUND_Y - ROUND / 2, ROUND, ROUND);

        canvas.setColor(Theme.muted());
        canvas.setFont(ROUND_LABEL);

        var width = canvas.getFontMetrics().stringWidth(name);

        canvas.drawString(name, x - width / 2, ROUND_Y + ROUND / 2 + 14);
    }

    private Color colourOf(final int button) {
        return (held & button) != 0 ? Theme.running() : Theme.dim();
    }
}
