package com.github.dimiro1.mynes.ui.debugger;

import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;

/**
 * A hex view of the CPU's address space, sixteen bytes to a row.
 * <p>
 * Every byte comes out of the snapshot rather than out of the machine, which is what makes it safe
 * to have a table model at all: Swing asks a model for cells whenever it repaints, and a model that
 * answered by reading the machine would be reading a running one.
 */
final class MemoryPanel extends JPanel {
    private static final int ROWS = 16;
    private static final int COLUMNS = 16;

    /**
     * The window the PPU and the controllers answer on, which {@code peek} deliberately reads as
     * zero rather than for real. Greyed, so that a row of zeros there is not mistaken for a machine
     * that has cleared its registers.
     */
    private static final int REGISTERS_FROM = 0x2000;
    private static final int REGISTERS_TO = 0x4020;

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JTextField address = new JTextField("0000", 6);

    private MachineSnapshot snapshot;
    private int base;

    MemoryPanel() {
        super(new MigLayout("insets 8, fill", "[][grow,fill]", "[][grow,fill]"));

        setBorder(BorderFactory.createTitledBorder("Memory"));

        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        table.setRowSelectionAllowed(false);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.setToolTipText("Registers at $2000-$401F read as zero on purpose: looking at them"
                + " for real would clear latches the game is using.");

        address.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        address.addActionListener(e -> goTo(address.getText()));

        add(new JLabel("Address"));
        add(address, "wrap");
        add(new JScrollPane(table), "span 2, grow");
    }

    void show(final MachineSnapshot snapshot) {
        this.snapshot = snapshot;

        model.fireTableDataChanged();
    }

    void clear() {
        snapshot = null;

        model.fireTableDataChanged();
    }

    /**
     * Moves the view.
     * <p>
     * A bare number here is <em>hexadecimal</em>, unlike the REPL's, where it is decimal. This is a
     * box next to a grid of hex, and somebody typing {@code 6000} into it means $6000; being taken
     * to $1770 instead would be a small daily annoyance. A leading {@code $} or {@code 0x} works
     * too, and a word that is not a number at all leaves the view where it was rather than jumping
     * somewhere arbitrary.
     */
    private void goTo(final String text) {
        var trimmed = text.trim();

        try {
            var parsed = trimmed.startsWith("$")
                    ? Integer.parseInt(trimmed.substring(1), 16)
                    : trimmed.startsWith("0x") || trimmed.startsWith("0X")
                    ? Integer.parseInt(trimmed.substring(2), 16)
                    : Integer.parseInt(trimmed, 16);

            base = (parsed & 0xFFFF) & ~(COLUMNS - 1);
            model.fireTableDataChanged();
        } catch (NumberFormatException e) {
            address.setText(String.format("%04X", base));
        }
    }

    private final class Model extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return ROWS;
        }

        @Override
        public int getColumnCount() {
            return COLUMNS + 2;
        }

        @Override
        public String getColumnName(final int column) {
            if (column == 0) {
                return "";
            }

            return column == COLUMNS + 1 ? "ASCII" : String.format("%X", column - 1);
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            var start = (base + row * COLUMNS) & 0xFFFF;

            if (column == 0) {
                return String.format("$%04X", start);
            }

            if (snapshot == null) {
                return column == COLUMNS + 1 ? "" : "--";
            }

            if (column == COLUMNS + 1) {
                var text = new StringBuilder(COLUMNS);

                for (var i = 0; i < COLUMNS; i++) {
                    var value = snapshot.read(start + i);

                    text.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
                }

                return text.toString();
            }

            return String.format("%02X", snapshot.read(start + column - 1));
        }
    }

    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                final JTable table,
                final Object value,
                final boolean selected,
                final boolean focused,
                final int row,
                final int column) {

            super.getTableCellRendererComponent(table, value, selected, focused, row, column);

            var start = (base + row * COLUMNS) & 0xFFFF;

            setHorizontalAlignment(column == COLUMNS + 1 ? SwingConstants.LEFT : SwingConstants.CENTER);
            setForeground(start >= REGISTERS_FROM && start < REGISTERS_TO
                    ? Color.GRAY
                    : table.getForeground());

            return this;
        }
    }
}
