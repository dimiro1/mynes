package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.ui.AppearanceAware;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/** Named or addressed RAM values, read from the same stopped snapshot as the memory view. */
final class WatchesPanel extends JPanel implements AppearanceAware {
    interface Writer {
        void write(int address, int value, int width);
    }

    private record Watch(String name, int width) {
        String label() {
            return name + (width == 2 ? ":16" : "");
        }
    }

    private final List<Watch> watches = new ArrayList<>();
    private final WatchModel model = new WatchModel();
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);
    private final JTextField entry = new JTextField(18);
    private final Writer writer;

    private SourceProgram program;
    private MachineSnapshot snapshot;
    private boolean stopped;

    WatchesPanel(final Writer writer) {
        super(new BorderLayout(0, 6));
        this.writer = writer;

        var add = new JButton("Add");
        var remove = new JButton("Remove");
        entry.setToolTipText("RAM symbol or hex address; add :16 for a little-endian word");
        entry.addActionListener(e -> addWatch());
        add.addActionListener(e -> addWatch());
        remove.addActionListener(e -> {
            var row = table.getSelectedRow();
            if (row >= 0) {
                watches.remove(table.convertRowIndexToModel(row));
                model.fireTableDataChanged();
            }
        });

        var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.add(new JLabel("Symbol or address"));
        controls.add(entry);
        controls.add(add);
        controls.add(remove);

        table.setFont(Theme.MONOSPACED);
        table.setRowHeight(table.getFontMetrics(Theme.MONOSPACED).getHeight() + 5);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        add(controls, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        refreshAppearance();
    }

    @Override
    public void refreshAppearance() {
        scroll.setBorder(BorderFactory.createLineBorder(Theme.dim()));
        table.repaint();
    }

    void setSourceProgram(final SourceProgram program) {
        this.program = program;
        model.fireTableDataChanged();
    }

    void show(final MachineSnapshot snapshot) {
        this.snapshot = snapshot;
        stopped = true;
        model.fireTableDataChanged();
    }

    void stale() {
        stopped = false;
        model.fireTableDataChanged();
    }

    void clearMachine() {
        snapshot = null;
        stale();
    }

    private void addWatch() {
        var text = entry.getText().trim();
        var width = text.endsWith(":16") ? 2 : 1;
        var name = width == 2 ? text.substring(0, text.length() - 3).trim() : text;
        if (!name.matches("(?:\\$|0[xX])[0-9a-fA-F]{1,4}")
                && !name.matches("[0-9a-fA-F]{1,4}")
                && !name.matches("[A-Za-z_@.][A-Za-z_0-9@.]*")) {
            JOptionPane.showMessageDialog(this, "Enter a RAM symbol or a hex address.",
                    "Watch", JOptionPane.ERROR_MESSAGE);
            return;
        }
        var watch = new Watch(name, width);
        if (!watches.contains(watch)) {
            watches.add(watch);
            model.fireTableRowsInserted(watches.size() - 1, watches.size() - 1);
        }
        entry.setText("");
    }

    private int addressOf(final Watch watch) {
        if (program != null) {
            for (var symbol : program.ramSymbols()) {
                if (symbol.name().equals(watch.name())) return symbol.value();
            }
        }
        if (watch.name().startsWith("$") || watch.name().startsWith("0x")
                || watch.name().startsWith("0X")
                || watch.name().matches("[0-9a-fA-F]{1,4}")) {
            return Addresses.parse(watch.name());
        }
        return -1;
    }

    private static boolean writable(final int address, final int width) {
        return address >= 0 && address + width - 1 < 0x2000
                || address >= 0x6000 && address + width - 1 < 0x8000;
    }

    private final class WatchModel extends AbstractTableModel {
        @Override
        public int getRowCount() {
            return watches.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(final int column) {
            return switch (column) {
                case 0 -> "Watch";
                case 1 -> "Address";
                default -> "Value";
            };
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            var watch = watches.get(row);
            var address = addressOf(watch);
            return switch (column) {
                case 0 -> watch.label();
                case 1 -> address < 0 ? "—" : String.format("$%04X", address);
                default -> snapshot == null || address < 0 ? "—" : watch.width() == 2
                        ? String.format("$%04X", snapshot.read(address)
                                | snapshot.read(address + 1) << 8)
                        : String.format("$%02X", snapshot.read(address));
            };
        }

        @Override
        public boolean isCellEditable(final int row, final int column) {
            var watch = watches.get(row);
            return column == 2 && stopped && writable(addressOf(watch), watch.width());
        }

        @Override
        public void setValueAt(final Object value, final int row, final int column) {
            if (!isCellEditable(row, column)) return;
            var watch = watches.get(row);
            try {
                var word = value.toString().trim().replaceFirst("^(?:\\$|0[xX])", "");
                var parsed = Integer.parseInt(word, 16);
                if (parsed < 0 || parsed >= 1 << watch.width() * 8) {
                    throw new NumberFormatException();
                }
                writer.write(addressOf(watch), parsed, watch.width());
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(WatchesPanel.this,
                        "Enter a hex " + (watch.width() == 2 ? "word" : "byte") + ".",
                        "Watch", JOptionPane.ERROR_MESSAGE);
            }
        }
    }
}
