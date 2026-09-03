package com.github.dimiro1.mynes.ui.debugger;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

/**
 * Every colour the debugger paints with, in one place.
 * <p>
 * The structural ones -- muted text, the accent, selection -- come out of the look and feel, so
 * that the window keeps looking like the rest of the program if the theme ever changes, with a
 * plain fallback for a machine that fell back to Metal. The syntax colours are this window's own:
 * a look and feel has no opinion about what colour a branch is. They are picked to read on FlatLaf
 * Light's white, which is the one theme the program ships.
 * <p>
 * Hue is carrying meaning here, so the choices are not arbitrary: warm for a value, cool for a
 * place, and the same red for a breakpoint wherever one is drawn -- the gutter, the points table,
 * the byte a watchpoint caught.
 */
final class Theme {
    /**
     * The one font every listing in the window uses, so that columns line up across panels.
     */
    static final Font MONOSPACED = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    private static final Color FLOW = new Color(0x8250DF);
    private static final Color DATA = new Color(0x0550AE);
    private static final Color MATH = new Color(0x116329);
    private static final Color IMMEDIATE = new Color(0x953800);
    private static final Color ADDRESS = new Color(0x0A3069);
    private static final Color REGISTER = new Color(0x6639BA);

    private static final Color BREAKPOINT = new Color(0xD73A49);
    private static final Color RUNNING = new Color(0x2DA44E);
    private static final Color STOPPED = new Color(0xBF8700);
    private static final Color STACK_POINTER = new Color(0x1A7F37);

    private Theme() {
    }

    /**
     * The title over a panel: small capitals in the muted colour rather than a titled border,
     * because five boxes with lines round them is what the old window looked like and the lines
     * were most of what made it look old.
     */
    static JLabel heading(final String text) {
        var label = new JLabel(text.toUpperCase(Locale.ROOT));

        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setForeground(muted());
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));

        return label;
    }

    static Color muted() {
        return colour("Label.disabledForeground", Color.GRAY);
    }

    /**
     * Fainter than muted, for the bytes column and the zeros in memory: present, but not what
     * anyone is reading.
     */
    static Color dim() {
        var muted = muted();
        var back = background();

        return blend(muted, back, 0.55f);
    }

    static Color foreground() {
        return colour("Label.foreground", Color.BLACK);
    }

    static Color background() {
        return colour("List.background", Color.WHITE);
    }

    static Color accent() {
        return colour("Component.accentColor", new Color(0x2675BF));
    }

    static Color selectionBackground() {
        return colour("List.selectionBackground", new Color(0x2675BF));
    }

    static Color selectionForeground() {
        return colour("List.selectionForeground", Color.WHITE);
    }

    static Color breakpoint() {
        return BREAKPOINT;
    }

    static Color running() {
        return RUNNING;
    }

    static Color stopped() {
        return STOPPED;
    }

    static Color stackPointer() {
        return STACK_POINTER;
    }

    /**
     * The row the machine is standing on, and the byte at the PC: the accent, mostly background.
     */
    static Color currentRow() {
        return tint(accent(), 0.16f);
    }

    static Color colourFor(final Syntax.Kind kind) {
        return switch (kind) {
            case FLOW -> FLOW;
            case DATA -> DATA;
            case MATH -> MATH;
            case MISC -> muted();
            case ILLEGAL -> BREAKPOINT;
            case IMMEDIATE -> IMMEDIATE;
            case ADDRESS -> ADDRESS;
            case REGISTER -> REGISTER;
            case PUNCTUATION -> muted();
            case TEXT -> foreground();
        };
    }

    /**
     * The colour over the window's background at this opacity, flattened rather than left
     * translucent so that a list painting it as a row background does not stack it on top of a
     * selection.
     */
    static Color tint(final Color colour, final float alpha) {
        return blend(colour, background(), alpha);
    }

    private static Color blend(final Color over, final Color under, final float alpha) {
        var beta = 1 - alpha;

        return new Color(
                Math.round(over.getRed() * alpha + under.getRed() * beta),
                Math.round(over.getGreen() * alpha + under.getGreen() * beta),
                Math.round(over.getBlue() * alpha + under.getBlue() * beta));
    }

    private static Color colour(final String key, final Color fallback) {
        var colour = UIManager.getColor(key);

        return colour == null ? fallback : colour;
    }
}
