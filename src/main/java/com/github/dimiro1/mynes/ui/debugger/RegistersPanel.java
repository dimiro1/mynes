package com.github.dimiro1.mynes.ui.debugger;

import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.Font;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The registers, and where the beam is.
 * <p>
 * The PPU's frame, scanline and dot are here rather than in a window of their own because "where is
 * the beam" is the actual question whenever a machine stops inside a raster effect, and having to
 * go and look it up somewhere else is the difference between using a debugger and fighting one.
 * <p>
 * Nothing here is live. While the machine runs these hold whatever the last stop said and go grey to
 * admit it, rather than flickering through thirty thousand values a second that nobody could read
 * and none of which would be self consistent.
 */
final class RegistersPanel extends JPanel {
    private final Map<String, JLabel> values = new LinkedHashMap<>();

    RegistersPanel() {
        super(new MigLayout("insets 8, wrap 2", "[40!][grow,fill]", ""));

        setBorder(BorderFactory.createTitledBorder("Registers"));

        for (var name : new String[]{"PC", "A", "X", "Y", "SP", "P", "cyc", "frame", "line", "dot"}) {
            var value = new JLabel("--");

            value.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            values.put(name, value);

            add(new JLabel(name));
            add(value);
        }
    }

    void show(final MachineSnapshot snapshot) {
        var cpu = snapshot.cpu();

        set("PC", String.format("$%04X", cpu.pc()));
        set("A", String.format("$%02X", cpu.a()));
        set("X", String.format("$%02X", cpu.x()));
        set("Y", String.format("$%02X", cpu.y()));
        set("SP", String.format("$%02X", cpu.sp()));
        set("P", String.format("$%02X  %s", cpu.p(), snapshot.flags()));
        set("cyc", Long.toString(cpu.cycles()));
        set("frame", Long.toString(snapshot.frame()));
        set("line", Integer.toString(snapshot.scanline()));
        set("dot", Integer.toString(snapshot.dot()));

        values.values().forEach(label -> label.setForeground(getForeground()));
    }

    /**
     * Greys everything, because what is shown is now what the machine looked like a while ago.
     */
    void stale() {
        values.values().forEach(label -> label.setForeground(Color.GRAY));
    }

    private void set(final String name, final String value) {
        values.get(name).setText(value);
    }
}
