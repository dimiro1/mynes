package com.github.dimiro1.mynes.ui.debugger;

import com.formdev.flatlaf.FlatLaf;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.util.Locale;

/**
 * Every colour the debugger paints with, in one place.
 * <p>
 * The structural half of it is public and the syntax colours are not, which is the line worth
 * keeping: what a window's headings, its muted text and its running-or-stopped green are is one
 * question for the whole control panel, and what colour a branch instruction is drawn in is the
 * disassembly's alone.
 * <p>
 * The structural ones -- muted text, the accent, selection -- come out of the look and feel, so
 * that the window keeps looking like the rest of the program if the theme ever changes, with a
 * plain fallback for a machine that fell back to Metal. The syntax colours are this window's own:
 * a look and feel has no opinion about what colour a branch is. Each has a light and dark version.
 * <p>
 * Hue is carrying meaning here, so the choices are not arbitrary: warm for a value, cool for a
 * place, and the same red for a breakpoint wherever one is drawn -- the gutter, the points table,
 * the byte a watchpoint caught.
 */
public final class Theme {
    /**
     * The one font every listing in the window uses, so that columns line up across panels.
     */
    static final Font MONOSPACED = new Font(Font.MONOSPACED, Font.PLAIN, 12);

    public static boolean isDark() {
        return FlatLaf.isLafDark();
    }

    private static Color pick(final int light, final int dark) {
        return new Color(isDark() ? dark : light);
    }

    private Theme() {
    }

    /**
     * The title over a panel: small capitals in the muted colour rather than a titled border,
     * because five boxes with lines round them is what the old window looked like and the lines
     * were most of what made it look old.
     */
    public static JLabel heading(final String text) {
        var label = new MutedLabel(text.toUpperCase(Locale.ROOT));

        label.setFont(label.getFont().deriveFont(Font.BOLD, 11f));
        label.setForeground(muted());
        label.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));

        return label;
    }

    /**
     * The line under a heading that says what the thing below it is: the same muted colour, at the
     * ordinary weight and size, so that it reads as an aside rather than as another label.
     * <p>
     * Here rather than in each panel because three of them wanted one and each had written its own,
     * which is three places for the same three lines to drift apart in.
     */
    public static JLabel note(final String text) {
        var label = new MutedLabel(text);

        label.setForeground(muted());
        label.setFont(label.getFont().deriveFont(11f));

        return label;
    }

    public static Color muted() {
        return colour("Label.disabledForeground", Color.GRAY);
    }

    /**
     * Fainter than muted, for the bytes column and the zeros in memory: present, but not what
     * anyone is reading.
     */
    public static Color dim() {
        var muted = muted();
        var back = background();

        return blend(muted, back, 0.55f);
    }

    public static Color foreground() {
        return colour("Label.foreground", Color.BLACK);
    }

    public static Color background() {
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
        return pick(0xD73A49, 0xFF7B72);
    }

    public static Color running() {
        return pick(0x2DA44E, 0x56D364);
    }

    public static Color stopped() {
        return pick(0xBF8700, 0xE3B341);
    }

    static Color stackPointer() {
        return pick(0x1A7F37, 0x7EE787);
    }

    static Color changed() {
        return pick(0x9A6700, 0xE3B341);
    }

    static Color changedRow() {
        return tint(changed(), 0.14f);
    }

    /**
     * The row the machine is standing on, and the byte at the PC: the accent, mostly background.
     */
    static Color currentRow() {
        return tint(accent(), 0.16f);
    }

    static Color colourFor(final Syntax.Kind kind) {
        return switch (kind) {
            case FLOW -> pick(0x8250DF, 0xD2A8FF);
            case DATA -> pick(0x0550AE, 0x79C0FF);
            case MATH -> pick(0x116329, 0x7EE787);
            case MISC -> muted();
            case ILLEGAL -> breakpoint();
            case IMMEDIATE -> pick(0x953800, 0xF2B17A);
            case ADDRESS -> pick(0x0A3069, 0x9ECBFF);
            case REGISTER -> pick(0x6639BA, 0xBC8CFF);
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

    private static final class MutedLabel extends JLabel {
        MutedLabel(final String text) {
            super(text);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setForeground(muted());
        }
    }
}
