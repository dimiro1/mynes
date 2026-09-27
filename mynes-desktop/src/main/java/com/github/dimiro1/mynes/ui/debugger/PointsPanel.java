package com.github.dimiro1.mynes.ui.debugger;

import com.formdev.flatlaf.FlatClientProperties;
import com.github.dimiro1.mynes.ui.AppearanceAware;
import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Debugger;
import net.miginfocom.swing.MigLayout;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/** Breakpoints and watchpoints in one list, with a single place to add or remove them. */
final class PointsPanel extends JPanel implements AppearanceAware {
    interface Points {
        void breakAt(int address, Condition condition);
        void watchAt(int address, Debugger.Access on);
        void removeBreakpoint(int address);
        void removePRGBreakpoint(int offset);
        void removeWatchpoint(int address);
        void clear();
    }

    private enum Kind {
        BREAKPOINT("Breakpoint"), SOURCE("Source"), WATCHPOINT("Watchpoint");

        private final String label;

        Kind(final String label) {
            this.label = label;
        }
    }

    private record Point(Kind kind, int key, String place, String detail) {
    }

    private final Model model = new Model();
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);
    private final JTextField entry = new JTextField(12);
    private final JComboBox<Debugger.Access> facing = new JComboBox<>(Debugger.Access.values());
    private final JLabel complaint = new JLabel(" ");
    private final JButton remove = new JButton("Remove");
    private final Points points;

    PointsPanel(final Points points) {
        super(new MigLayout("insets 4 8 8 8, fill, wrap 1, gapy 4", "[grow,fill]",
                "[][grow,fill][][][]"));
        this.points = points;

        table.setFont(Theme.MONOSPACED);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.setRowHeight(table.getFontMetrics(Theme.MONOSPACED).getHeight() + 4);
        table.setPreferredScrollableViewportSize(new Dimension(420, table.getRowHeight() * 5));

        var metrics = table.getFontMetrics(Theme.MONOSPACED);
        var columns = table.getColumnModel();
        columns.getColumn(0).setMinWidth(metrics.stringWidth("Watchpoint") + 16);
        columns.getColumn(0).setMaxWidth(metrics.stringWidth("Watchpoint") + 16);
        columns.getColumn(1).setMinWidth(metrics.stringWidth("PRG+$00000") + 16);
        columns.getColumn(1).setMaxWidth(metrics.stringWidth("PRG+$00000") + 16);

        remove.putClientProperty(FlatClientProperties.BUTTON_TYPE,
                FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
        remove.setToolTipText("Remove the selected point (Delete)");
        remove.setEnabled(false);
        remove.addActionListener(event -> removeSelected());
        table.getSelectionModel().addListSelectionListener(
                event -> remove.setEnabled(table.getSelectedRow() >= 0));
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("DELETE"), "remove");
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("BACK_SPACE"), "remove");
        table.getActionMap().put("remove", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent event) {
                removeSelected();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                if (event.getClickCount() == 2) {
                    removeSelected();
                }
            }
        });

        entry.setFont(Theme.MONOSPACED);
        entry.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT,
                "$C000, or $C000 if a == $10");
        entry.setToolTipText("Address in hex. Enter sets a breakpoint.");
        facing.setSelectedItem(Debugger.Access.WRITE);
        facing.setToolTipText("Which way a watchpoint looks");
        complaint.setForeground(Theme.breakpoint());
        complaint.setFont(complaint.getFont().deriveFont(Font.PLAIN, 11f));

        var addBreak = new JButton("Break at");
        var addWatch = new JButton("Watch");
        var clear = new JButton("Clear all");
        DebuggerIcons.set(addBreak, DebuggerIcons.Symbol.POINT);
        DebuggerIcons.set(addWatch, DebuggerIcons.Symbol.WATCH);
        DebuggerIcons.set(remove, DebuggerIcons.Symbol.REMOVE);
        DebuggerIcons.set(clear, DebuggerIcons.Symbol.CLEAR);
        addBreak.setToolTipText("Stop before the instruction at this address");
        addWatch.setToolTipText("Stop after an instruction touches this address");

        var breakTyped = (Runnable) () -> withEntry(typed ->
                points.breakAt(typed.address(), typed.condition()));
        addBreak.addActionListener(event -> breakTyped.run());
        entry.addActionListener(event -> breakTyped.run());
        addWatch.addActionListener(event -> withEntry(typed -> {
            if (typed.condition() != null) {
                throw new IllegalArgumentException("a watchpoint takes no condition.");
            }
            points.watchAt(typed.address(), (Debugger.Access) facing.getSelectedItem());
        }));
        clear.addActionListener(event -> points.clear());

        var header = new JPanel(new MigLayout("insets 0", "[]push[]", "[]"));
        header.add(Theme.heading("Points"));
        header.add(remove);
        var entryRow = new JPanel(new MigLayout("insets 0, gap 4", "[grow,fill][]", ""));
        entryRow.add(entry);
        entryRow.add(addBreak);
        var watchRow = new JPanel(new MigLayout("insets 0, gap 4", "[][]push[]", ""));
        watchRow.add(facing);
        watchRow.add(addWatch);
        watchRow.add(clear);

        scroll.setBorder(BorderFactory.createLineBorder(Theme.dim()));
        add(header);
        add(scroll, "grow, hmin 70");
        add(entryRow, "growx");
        add(watchRow, "growx");
        add(complaint, "growx");
    }

    @Override
    public void refreshAppearance() {
        complaint.setForeground(Theme.breakpoint());
        scroll.setBorder(BorderFactory.createLineBorder(Theme.dim()));
        table.repaint();
    }

    @Override
    public Dimension getMinimumSize() {
        return new Dimension(420, 170);
    }

    void show(
            final Set<Integer> breaks,
            final Map<Integer, Condition> conditions,
            final Set<Integer> prgBreaks,
            final SourceProgram source,
            final Map<Integer, Debugger.Access> watches) {
        var selected = selected();
        var rows = new ArrayList<Point>(breaks.size() + prgBreaks.size() + watches.size());
        breaks.stream().sorted().forEach(address -> {
            var condition = conditions.get(address);
            rows.add(new Point(Kind.BREAKPOINT, address, String.format("$%04X", address),
                    condition == null ? "" : "if " + condition.text()));
        });
        prgBreaks.stream().sorted().forEach(offset -> {
            var line = source == null ? null : source.lineForBreakpoint(offset);
            rows.add(new Point(Kind.SOURCE, offset, String.format("PRG+$%05X", offset),
                    line == null ? "" : line.location()));
        });
        watches.entrySet().stream().sorted(Comparator.comparingInt(Map.Entry::getKey))
                .forEach(watch -> rows.add(new Point(Kind.WATCHPOINT, watch.getKey(),
                        String.format("$%04X", watch.getKey()), watch.getValue().id())));

        model.rows = List.copyOf(rows);
        model.fireTableDataChanged();
        if (selected != null) {
            for (var index = 0; index < rows.size(); index++) {
                var point = rows.get(index);
                if (point.kind() == selected.kind() && point.key() == selected.key()) {
                    table.setRowSelectionInterval(index, index);
                    break;
                }
            }
        }
    }

    private Point selected() {
        var index = table.getSelectedRow();
        return index < 0 || index >= model.rows.size() ? null : model.rows.get(index);
    }

    private void removeSelected() {
        var selected = selected();
        if (selected == null) {
            return;
        }
        switch (selected.kind()) {
            case BREAKPOINT -> points.removeBreakpoint(selected.key());
            case SOURCE -> points.removePRGBreakpoint(selected.key());
            case WATCHPOINT -> points.removeWatchpoint(selected.key());
        }
    }

    private void withEntry(final Consumer<Addresses.Entry> action) {
        var text = entry.getText().trim();
        if (text.isEmpty()) {
            return;
        }
        try {
            action.accept(Addresses.parseEntry(text));
            entry.setText("");
            complaint.setText(" ");
        } catch (IllegalArgumentException e) {
            complaint.setText(e.getMessage());
            entry.selectAll();
        }
    }

    private static final class Model extends AbstractTableModel {
        private List<Point> rows = List.of();

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(final int column) {
            return switch (column) {
                case 0 -> "Type";
                case 1 -> "Address";
                default -> "Detail";
            };
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            var point = rows.get(row);
            return switch (column) {
                case 0 -> point.kind().label;
                case 1 -> point.place();
                default -> point.detail();
            };
        }
    }

    private static final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                final JTable table, final Object value, final boolean isSelected,
                final boolean focused, final int row, final int column) {
            super.getTableCellRendererComponent(table, value, isSelected, false, row, column);
            setFont(Theme.MONOSPACED);
            if (!isSelected) {
                setForeground(column == 0 ? Theme.muted()
                        : column == 1 ? Theme.breakpoint() : Theme.foreground());
            }
            return this;
        }
    }
}
