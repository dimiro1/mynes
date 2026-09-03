package com.github.dimiro1.mynes.ui.debugger;

import com.formdev.flatlaf.FlatClientProperties;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * The breakpoints and the watchpoints, listed and editable.
 * <p>
 * Two tables over one entry field, because they are the same gesture on different questions: stop
 * when the machine <em>reaches</em> here, and stop when it <em>touches</em> here.
 * <p>
 * The tables are this window's own copy rather than a view onto the debugger, the same way the Debug
 * menu's ticks are the front end's copy of the PPU's layer switches. Anything that changes a point
 * is posted onto the emulation thread and the tables are updated from here, which is what lets the
 * debugger itself have no synchronisation in it at all.
 * <p>
 * Each table has a Remove button and answers the Delete key, where the old lists answered only a
 * double click -- a gesture nothing on the screen suggested. The double click still works.
 */
final class PointsPanel extends JPanel {
    /**
     * What this panel can ask for. One interface rather than five constructor parameters, and it is
     * five because putting a point down and picking one up stopped being the same gesture: a
     * breakpoint typed over an existing one now sets its condition rather than removing it.
     */
    interface Points {
        void breakAt(int address, Condition condition);

        void watchAt(int address, Debugger.Access on);

        void removeBreakpoint(int address);

        void removeWatchpoint(int address);

        void clear();
    }

    /**
     * One line of either table: an address, and whatever else there is to say about it -- the
     * condition on a breakpoint, the direction of a watchpoint.
     */
    private record Point(int address, String detail) {
    }

    private final PointTable breakpoints;
    private final PointTable watchpoints;

    private final JTextField entry = new JTextField(12);
    private final JComboBox<Debugger.Access> facing = new JComboBox<>(Debugger.Access.values());
    private final JLabel complaint = new JLabel(" ");

    PointsPanel(final Points points) {
        super(new MigLayout(
                "insets 4 8 8 8, fill, wrap 1, gapy 4",
                "[grow,fill]",
                "[][grow,fill][][grow,fill][]4[][]"));

        breakpoints = new PointTable("Breakpoints", "Condition", points::removeBreakpoint);
        watchpoints = new PointTable("Watchpoints", "On", points::removeWatchpoint);

        entry.setFont(Theme.MONOSPACED);
        entry.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "$C000, or $C000 if a == $10");
        entry.setToolTipText("An address to break at or watch, in hex. Enter sets a breakpoint.");

        facing.setSelectedItem(Debugger.Access.WRITE);
        facing.setToolTipText("Which way a watchpoint looks");

        complaint.setForeground(Theme.breakpoint());
        complaint.setFont(complaint.getFont().deriveFont(Font.PLAIN, 11f));

        var addBreak = new JButton("Break at");
        var addWatch = new JButton("Watch");
        var clear = new JButton("Clear all");

        addBreak.setToolTipText("Stop before the instruction at this address");
        addWatch.setToolTipText("Stop after an instruction touches this address");

        var breakTyped = (Runnable) () -> withEntry(typed ->
                points.breakAt(typed.address(), typed.condition()));

        addBreak.addActionListener(e -> breakTyped.run());
        entry.addActionListener(e -> breakTyped.run());

        addWatch.addActionListener(e -> withEntry(typed -> {
            if (typed.condition() != null) {
                throw new IllegalArgumentException("a watchpoint takes no condition.");
            }

            points.watchAt(typed.address(), (Debugger.Access) facing.getSelectedItem());
        }));

        clear.addActionListener(e -> points.clear());

        // Two short rows rather than one long one. A button that is given less than its text asks
        // for is drawn with an ellipsis, and a row of five in a pane somebody has dragged narrow is
        // exactly how "Break at" became "Br...".
        var entryRow = new JPanel(new MigLayout("insets 0, gap 4", "[grow,fill][]", ""));
        entryRow.add(entry);
        entryRow.add(addBreak);

        var watchRow = new JPanel(new MigLayout("insets 0, gap 4", "[][]push[]", ""));
        watchRow.add(facing);
        watchRow.add(addWatch);
        watchRow.add(clear);

        add(breakpoints.header);
        add(breakpoints.scroll, "grow, hmin 60");
        add(watchpoints.header, "gaptop 8");
        add(watchpoints.scroll, "grow, hmin 60");
        add(entryRow, "growx");
        add(watchRow, "growx");
        add(complaint, "growx");
    }

    /**
     * Never narrower than the widest row wants, which is what keeps the split pane from handing this
     * panel a width its buttons cannot be drawn in.
     */
    @Override
    public Dimension getMinimumSize() {
        return new Dimension(getPreferredSize().width, 220);
    }

    /**
     * Replaces both tables with what the debugger actually holds, which is the only thing that
     * keeps this window's copy honest after a clear or a load.
     */
    void show(
            final Set<Integer> breaks,
            final Map<Integer, Condition> conditions,
            final Map<Integer, Debugger.Access> watches) {

        var breakRows = new ArrayList<Point>(breaks.size());

        for (var address : breaks) {
            var condition = conditions.get(address);

            breakRows.add(new Point(address, condition == null ? "" : "if " + condition.text()));
        }

        var watchRows = new ArrayList<Point>(watches.size());

        watches.forEach((address, on) -> watchRows.add(new Point(address, on.id())));

        breakpoints.show(breakRows);
        watchpoints.show(watchRows);
    }

    // ================================================================================== internals

    /**
     * What was typed into the one text field: an address, and optionally a condition after
     * {@code if}.
     */
    private void withEntry(final Consumer<Addresses.Entry> action) {
        var text = entry.getText().trim();

        if (text.isEmpty()) {
            return;
        }

        // Says what is wrong rather than shrugging. A condition that could not be read used to be a
        // silently ignored button press, which on a debugger is the worst possible answer: the
        // point looks set and the machine never stops.
        try {
            action.accept(Addresses.parseEntry(text));

            entry.setText("");
            complaint.setText(" ");
        } catch (IllegalArgumentException e) {
            complaint.setText(e.getMessage());
            entry.selectAll();
        }
    }

    /**
     * One kind of point as a table: the addresses in one column and what there is to say about
     * each in the other, with a heading and a Remove button above it.
     */
    private static final class PointTable {
        final JPanel header;
        final JScrollPane scroll;

        private final Model model = new Model();
        private final JTable table = new JTable(model);
        private final JButton remove = new JButton("Remove");
        private final String detailName;

        PointTable(final String title, final String detailName, final IntConsumer onRemove) {
            this.detailName = detailName;

            table.setFont(Theme.MONOSPACED);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setShowGrid(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setFillsViewportHeight(true);
            table.getTableHeader().setReorderingAllowed(false);
            table.setDefaultRenderer(Object.class, new Renderer());
            table.setRowHeight(table.getFontMetrics(Theme.MONOSPACED).getHeight() + 4);
            table.setPreferredScrollableViewportSize(new Dimension(200, table.getRowHeight() * 4));

            // As wide as the header's word or the cells' address, whichever the font makes wider:
            // the header is in the look and feel's font and the cells in the monospaced one.
            var metrics = table.getFontMetrics(Theme.MONOSPACED);
            var headerMetrics = table.getTableHeader().getFontMetrics(table.getTableHeader().getFont());
            var addressWidth = Math.max(
                    metrics.stringWidth("$0000"), headerMetrics.stringWidth("Address"))
                    + metrics.charWidth('0') * 2;

            table.getColumnModel().getColumn(0).setMinWidth(addressWidth);
            table.getColumnModel().getColumn(0).setMaxWidth(addressWidth);

            remove.putClientProperty(FlatClientProperties.BUTTON_TYPE,
                    FlatClientProperties.BUTTON_TYPE_TOOLBAR_BUTTON);
            remove.setToolTipText("Take the selected one out (Delete)");
            remove.setEnabled(false);
            remove.addActionListener(e -> removeSelected(onRemove));

            table.getSelectionModel().addListSelectionListener(
                    e -> remove.setEnabled(table.getSelectedRow() >= 0));

            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .put(KeyStroke.getKeyStroke("DELETE"), "remove");
            table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .put(KeyStroke.getKeyStroke("BACK_SPACE"), "remove");
            table.getActionMap().put("remove", new AbstractAction() {
                @Override
                public void actionPerformed(final ActionEvent e) {
                    removeSelected(onRemove);
                }
            });

            table.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(final MouseEvent e) {
                    if (e.getClickCount() == 2) {
                        removeSelected(onRemove);
                    }
                }
            });

            header = new JPanel(new MigLayout("insets 0", "[]push[]", "[]"));
            header.add(Theme.heading(title));
            header.add(remove);

            scroll = new JScrollPane(table);
            scroll.setBorder(BorderFactory.createLineBorder(Theme.dim()));
        }

        void show(final List<Point> rows) {
            var selected = table.getSelectedRow();

            model.rows = List.copyOf(rows);
            model.fireTableDataChanged();

            if (selected >= 0 && selected < rows.size()) {
                table.setRowSelectionInterval(selected, selected);
            }
        }

        private void removeSelected(final IntConsumer onRemove) {
            var row = table.getSelectedRow();

            if (row >= 0 && row < model.rows.size()) {
                onRemove.accept(model.rows.get(row).address());
            }
        }

        private final class Model extends AbstractTableModel {
            List<Point> rows = List.of();

            @Override
            public int getRowCount() {
                return rows.size();
            }

            @Override
            public int getColumnCount() {
                return 2;
            }

            @Override
            public String getColumnName(final int column) {
                return column == 0 ? "Address" : detailName;
            }

            @Override
            public Object getValueAt(final int row, final int column) {
                var point = rows.get(row);

                return column == 0 ? String.format("$%04X", point.address()) : point.detail();
            }
        }

        private static final class Renderer extends DefaultTableCellRenderer {
            @Override
            public Component getTableCellRendererComponent(
                    final JTable table,
                    final Object value,
                    final boolean isSelected,
                    final boolean focused,
                    final int row,
                    final int column) {

                super.getTableCellRendererComponent(table, value, isSelected, false, row, column);

                setFont(Theme.MONOSPACED);

                if (!isSelected) {
                    setForeground(column == 0 ? Theme.breakpoint() : Theme.foreground());
                }

                return this;
            }
        }
    }
}
