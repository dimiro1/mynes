package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.ExecutionProfile;
import com.github.dimiro1.mynes.ui.AppearanceAware;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** Opt-in instruction counts, run cycles, and source-line coverage. */
final class ProfilerPanel extends JPanel implements AppearanceAware {
    interface Navigator {
        void navigate(SourceProgram.SourceLine line, int prgOffset);
    }

    private record Row(String location, long instructions, long cycles,
                       SourceProgram.SourceLine line, int prgOffset) {
    }

    private static final class Totals {
        final String location;
        final SourceProgram.SourceLine line;
        final int prgOffset;
        long instructions;
        long cycles;

        Totals(final String location, final SourceProgram.SourceLine line, final int prgOffset) {
            this.location = location;
            this.line = line;
            this.prgOffset = prgOffset;
        }
    }

    private final JButton toggle = new JButton("Start");
    private final JLabel summary = new JLabel("Start profiling to measure executed code.");
    private final ProfileModel model = new ProfileModel();
    private final JTable table = new JTable(model);
    private final JScrollPane scroll = new JScrollPane(table);

    private SourceProgram program;
    private ExecutionProfile.Snapshot snapshot;

    ProfilerPanel(final Runnable toggleAction, final Runnable resetAction,
                  final Runnable refreshAction, final Navigator navigateAction) {
        super(new BorderLayout(0, 6));
        toggle.addActionListener(e -> toggleAction.run());
        var reset = new JButton("Reset");
        reset.addActionListener(e -> resetAction.run());
        var refresh = new JButton("Refresh");
        refresh.addActionListener(e -> refreshAction.run());

        var controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        controls.add(toggle);
        controls.add(reset);
        controls.add(refresh);
        controls.add(summary);

        table.setFont(Theme.MONOSPACED);
        table.setRowHeight(table.getFontMetrics(Theme.MONOSPACED).getHeight() + 5);
        table.setFillsViewportHeight(true);
        table.getTableHeader().setReorderingAllowed(false);
        table.setToolTipText("Double-click a Source / PRG location to open it in the code viewer");
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                if (event.getClickCount() != 2 || !javax.swing.SwingUtilities.isLeftMouseButton(event)
                        || table.columnAtPoint(event.getPoint()) != 0) return;
                var viewRow = table.rowAtPoint(event.getPoint());
                if (viewRow < 0) return;
                var row = model.row(table.convertRowIndexToModel(viewRow));
                navigateAction.navigate(row.line(), row.prgOffset());
            }
        });
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

    void setRecording(final boolean recording) {
        toggle.setText(recording ? "Stop" : "Start");
    }

    void setSourceProgram(final SourceProgram program) {
        this.program = program;
        rebuild();
    }

    void show(final ExecutionProfile.Snapshot snapshot) {
        this.snapshot = snapshot;
        rebuild();
    }

    void clear() {
        snapshot = null;
        setRecording(false);
        summary.setText("Start profiling to measure executed code.");
        model.setRows(List.of());
    }

    private void rebuild() {
        if (snapshot == null) return;
        Map<Object, Totals> totals = new HashMap<>();
        var covered = new HashSet<SourceProgram.SourceLine>();
        for (var entry : snapshot.entries()) {
            var line = program == null ? null : program.lineAt(entry.prgOffset());
            var location = line == null ? String.format("PRG $%05X", entry.prgOffset())
                    : line.location();
            if (line != null) covered.add(line);
            var key = line == null ? entry.prgOffset() : line;
            var count = totals.computeIfAbsent(key,
                    ignored -> new Totals(location, line, entry.prgOffset()));
            count.instructions += entry.instructions();
            count.cycles += entry.cycles();
        }

        var rows = new ArrayList<Row>();
        totals.values().forEach(count -> rows.add(new Row(count.location,
                count.instructions, count.cycles, count.line, count.prgOffset)));
        rows.sort(Comparator.comparingLong(Row::cycles).reversed());
        model.setRows(rows);

        var coverage = program == null ? snapshot.entries().size() + " PRG locations"
                : covered.size() + " / " + program.executableLineCount() + " source lines";
        summary.setText(String.format("%,d instructions · %,d run cycles · %s covered",
                snapshot.instructions(), snapshot.cycles(), coverage));
    }

    private static final class ProfileModel extends AbstractTableModel {
        private List<Row> rows = List.of();

        Row row(final int index) {
            return rows.get(index);
        }

        void setRows(final List<Row> rows) {
            this.rows = List.copyOf(rows);
            fireTableDataChanged();
        }

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
                case 0 -> "Source / PRG";
                case 1 -> "Instructions";
                default -> "Run cycles";
            };
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            var item = rows.get(row);
            return switch (column) {
                case 0 -> item.location();
                case 1 -> item.instructions();
                default -> item.cycles();
            };
        }
    }
}
