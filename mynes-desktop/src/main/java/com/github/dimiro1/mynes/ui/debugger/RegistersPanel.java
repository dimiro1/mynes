package com.github.dimiro1.mynes.ui.debugger;

import net.miginfocom.swing.MigLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The registers, and where the beam is.
 * <p>
 * The PPU's frame, scanline and dot are here rather than in a window of their own because "where is
 * the beam" is the actual question whenever a machine stops inside a raster effect, and having to
 * go and look it up somewhere else is the difference between using a debugger and fighting one. The
 * scroll registers are beside them for the same reason: a split that landed a line early is a
 * question about {@code v} and {@code t} at the moment of the stop, and nowhere else shows them.
 * <p>
 * The flags are eight chips rather than a string of letters, set ones in the accent and clear ones
 * faded, because "is carry set" is answered by a glance at a colour where it was answered by
 * finding a capital in {@code nv-bdIzc}.
 * <p>
 * Nothing here is live. While the machine runs these hold whatever the last stop said and go grey to
 * admit it, rather than flickering through thirty thousand values a second that nobody could read
 * and none of which would be self consistent.
 */
final class RegistersPanel extends JPanel {
    private static final String FLAGS = "NV-BDIZC";

    private final Map<String, JLabel> values = new LinkedHashMap<>();
    private final List<JLabel> chips = new ArrayList<>(8);

    /**
     * The flags as they were last shown, so that {@link #stale()} can fade them without forgetting
     * which were set.
     */
    private int p;

    RegistersPanel() {
        super(new MigLayout(
                "insets 8 8 4 8, wrap 4, gapy 1",
                "[right]10[grow,fill]16[right]10[grow,fill]",
                ""));

        add(Theme.heading("CPU"), "span 4, left, gapbottom 2");

        row("PC");
        row("SP");
        row("A");
        row("X");
        row("Y");
        row("P");

        var flags = new JPanel(new MigLayout("insets 0, gap 3"));

        for (var i = 0; i < FLAGS.length(); i++) {
            var chip = new JLabel(String.valueOf(FLAGS.charAt(i)));

            chip.setFont(Theme.MONOSPACED.deriveFont(Font.BOLD));
            chip.setForeground(Theme.muted());
            chip.setToolTipText(flagName(FLAGS.charAt(i)));
            chips.add(chip);
            flags.add(chip);
        }

        row("cycles");
        var caption = new JLabel("flags");
        caption.setForeground(Theme.muted());

        add(caption, "right");
        add(flags);

        add(Theme.heading("PPU"), "span 4, left, gaptop 10, gapbottom 2");

        row("frame");
        row("beam");
        row("v");
        row("t");
        row("fine x");
        row("latch");
        row("render");
        row("sprites");
        row("patterns", "span 3");
    }

    void show(final MachineSnapshot snapshot) {
        var cpu = snapshot.cpu();

        set("PC", String.format("$%04X", cpu.pc()), Integer.toString(cpu.pc()));
        set("A", String.format("$%02X", cpu.a()), decimalAndBinary(cpu.a()));
        set("X", String.format("$%02X", cpu.x()), decimalAndBinary(cpu.x()));
        set("Y", String.format("$%02X", cpu.y()), decimalAndBinary(cpu.y()));
        set("SP", String.format("$%02X", cpu.sp()), String.format("stack top $%04X", snapshot.stackTop()));
        set("P", String.format("$%02X", cpu.p()), snapshot.flags());
        set("cycles", Long.toString(cpu.cycles()), null);

        set("frame", Long.toString(snapshot.frame()), null);
        set("beam", snapshot.scanline() + " : " + snapshot.dot(), "scanline : dot");
        set("v", String.format("$%04X", snapshot.v()), "the VRAM address the beam is reading");
        set("t", String.format("$%04X", snapshot.t()), "the address the next frame starts from");
        set("fine x", Integer.toString(snapshot.fineX()), null);
        set("latch", snapshot.writeLatch() ? "second write" : "first write",
                "which half of a $2005/$2006 pair comes next");
        set("render", snapshot.renderingEnabled() ? "on" : "off", "$2001 bits 3 and 4");
        set("patterns", String.format(
                        "bg $%04X  spr $%04X",
                        snapshot.backgroundPatternTable(), snapshot.spritePatternTable()),
                "$2000 bits 4 and 3");
        set("sprites", "8x" + snapshot.spriteHeight(), "$2000 bit 5");

        p = cpu.p();

        values.values().forEach(label -> label.setForeground(Theme.foreground()));
        paintChips(false);
    }

    /**
     * Greys everything, because what is shown is now what the machine looked like a while ago.
     */
    void stale() {
        values.values().forEach(label -> label.setForeground(Theme.muted()));
        paintChips(true);
    }

    private void paintChips(final boolean stale) {
        for (var bit = 0; bit < 8; bit++) {
            var set = (p & (0x80 >> bit)) != 0;
            var chip = chips.get(bit);

            chip.setForeground(set && !stale ? Theme.accent() : Theme.dim());
            chip.setFont(Theme.MONOSPACED.deriveFont(set ? Font.BOLD : Font.PLAIN));
        }
    }

    private void row(final String name) {
        row(name, "");
    }

    private void row(final String name, final String constraints) {
        var label = new JLabel(name);
        var value = new JLabel("--");

        label.setForeground(Theme.muted());
        value.setFont(Theme.MONOSPACED);
        values.put(name, value);

        add(label);
        add(value, constraints);
    }

    private void set(final String name, final String value, final String tooltip) {
        var label = values.get(name);

        label.setText(value);
        label.setToolTipText(tooltip);
    }

    private static String decimalAndBinary(final int value) {
        return String.format("%d  %%%8s", value, Integer.toBinaryString(value)).replace(' ', '0');
    }

    private static String flagName(final char flag) {
        return switch (flag) {
            case 'N' -> "negative";
            case 'V' -> "overflow";
            case 'B' -> "break";
            case 'D' -> "decimal";
            case 'I' -> "interrupt disable";
            case 'Z' -> "zero";
            case 'C' -> "carry";
            default -> "unused";
        };
    }
}
