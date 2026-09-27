package com.github.dimiro1.mynes.ui.debugger;

import javax.swing.AbstractButton;
import javax.swing.Icon;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Path2D;

/** Small vector symbols for debugger actions, coloured by what the action does. */
final class DebuggerIcons {
    enum Symbol implements Icon {
        RUN, BREAK, INTO, OVER, FRAME, GO, TARGET, POINT, WATCH, REMOVE, CLEAR;

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }

        @Override
        public void paintIcon(final Component component, final Graphics graphics,
                              final int x, final int y) {
            var g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y);
                g.setColor(colour(component));
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);
                switch (this) {
                    case RUN -> {
                        var triangle = new Path2D.Double();
                        triangle.moveTo(4, 2);
                        triangle.lineTo(13, 8);
                        triangle.lineTo(4, 14);
                        triangle.closePath();
                        g.fill(triangle);
                    }
                    case BREAK -> {
                        g.fillRoundRect(3, 2, 4, 12, 1, 1);
                        g.fillRoundRect(9, 2, 4, 12, 1, 1);
                    }
                    case INTO -> {
                        g.drawLine(8, 2, 8, 10);
                        g.drawLine(5, 7, 8, 10);
                        g.drawLine(11, 7, 8, 10);
                        g.drawLine(3, 14, 13, 14);
                    }
                    case OVER -> {
                        var arc = new Path2D.Double();
                        arc.moveTo(3, 10);
                        arc.curveTo(3, 1, 13, 1, 13, 10);
                        g.draw(arc);
                        g.drawLine(10, 7, 13, 10);
                        g.drawLine(15, 7, 13, 10);
                        g.drawLine(4, 14, 12, 14);
                    }
                    case FRAME -> {
                        var triangle = new Path2D.Double();
                        triangle.moveTo(2, 3);
                        triangle.lineTo(10, 8);
                        triangle.lineTo(2, 13);
                        triangle.closePath();
                        g.fill(triangle);
                        g.drawLine(13, 3, 13, 13);
                    }
                    case GO -> {
                        g.drawLine(2, 8, 13, 8);
                        g.drawLine(9, 4, 13, 8);
                        g.drawLine(9, 12, 13, 8);
                    }
                    case TARGET -> {
                        g.drawOval(3, 3, 10, 10);
                        g.drawLine(8, 1, 8, 5);
                        g.drawLine(8, 11, 8, 15);
                        g.drawLine(1, 8, 5, 8);
                        g.drawLine(11, 8, 15, 8);
                    }
                    case POINT -> {
                        g.fillOval(2, 2, 8, 8);
                        g.drawLine(11, 9, 11, 15);
                        g.drawLine(8, 12, 14, 12);
                    }
                    case WATCH -> {
                        var eye = new Path2D.Double();
                        eye.moveTo(1, 8);
                        eye.curveTo(4, 3, 12, 3, 15, 8);
                        eye.curveTo(12, 13, 4, 13, 1, 8);
                        g.draw(eye);
                        g.fillOval(6, 6, 4, 4);
                    }
                    case REMOVE -> {
                        g.drawOval(2, 2, 12, 12);
                        g.drawLine(5, 8, 11, 8);
                    }
                    case CLEAR -> {
                        g.drawLine(4, 4, 12, 12);
                        g.drawLine(12, 4, 4, 12);
                    }
                }
            } finally {
                g.dispose();
            }
        }

        private Color colour(final Component component) {
            if (!component.isEnabled()) {
                return Theme.muted();
            }

            // The app ships a light theme; keep the icons legible if a dark look and feel is used.
            var background = Theme.background();
            var dark = background.getRed() * 299 + background.getGreen() * 587
                    + background.getBlue() * 114 < 128_000;
            return switch (this) {
                case RUN -> dark ? new Color(0x58D77B) : Theme.running();
                case BREAK, POINT, REMOVE, CLEAR ->
                        dark ? new Color(0xF07178) : Theme.breakpoint();
                case WATCH -> dark ? new Color(0xE6B450) : new Color(0xA66A00);
                case INTO, OVER, FRAME, GO, TARGET ->
                        dark ? new Color(0x70B8FF) : Theme.accent();
            };
        }
    }

    static void set(final AbstractButton button, final Symbol symbol) {
        button.setIcon(symbol);
        button.setDisabledIcon(symbol);
        button.setIconTextGap(7);
    }

    private DebuggerIcons() {
    }
}
