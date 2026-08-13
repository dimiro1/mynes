package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.Disassembler;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.util.Set;

/**
 * A listing around the program counter, with a gutter showing where the breakpoints are.
 * <p>
 * The lines above the current one are the addresses that <em>really ran</em>, taken from the
 * debugger's trail, rather than the result of disassembling backwards. Backwards is not solvable on
 * a variable length instruction set: from any address there are several ways the bytes before it
 * could have been divided up and no way to tell which one the CPU took. Guessing produces a listing
 * that looks authoritative and is wrong, which is worse than showing nothing.
 */
final class DisassemblyPanel extends JScrollPane {
    /**
     * How much history to show above the PC, and how far ahead to disassemble.
     */
    private static final int BEHIND = 12;
    private static final int AHEAD = 40;

    /**
     * One line of the listing.
     *
     * @param current whether this is the instruction about to run.
     * @param ran     whether it is one that already has, which is what greys the history out.
     */
    record Row(int address, String bytes, String text, boolean current, boolean ran) {
    }

    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);

    private Set<Integer> breakpoints = Set.of();

    DisassemblyPanel() {
        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());

        setViewportView(list);
        setBorder(BorderFactory.createTitledBorder("Disassembly"));
    }

    /**
     * The address the selection is on, or -1, which is what "toggle a breakpoint here" needs.
     */
    int selectedAddress() {
        var row = list.getSelectedValue();

        return row == null ? -1 : row.address();
    }

    void addSelectionListener(final Runnable onDoubleClick) {
        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(final java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2) {
                    onDoubleClick.run();
                }
            }
        });
    }

    /**
     * Redraws the listing around wherever the machine has stopped.
     */
    void show(final MachineSnapshot snapshot, final Set<Integer> breakpoints) {
        this.breakpoints = breakpoints;

        model.clear();

        var pc = snapshot.cpu().pc();
        var trail = snapshot.trail();

        for (var i = Math.max(0, trail.length - BEHIND); i < trail.length; i++) {
            // The last entry of the trail is the instruction that just ran, which is not the one
            // about to run: showing it twice would put a second arrow on the listing.
            if (trail[i] != pc) {
                model.addElement(row(snapshot, trail[i], false, true));
            }
        }

        var at = pc;

        for (var i = 0; i < AHEAD; i++) {
            var line = Disassembler.at(snapshot::read, at);

            model.addElement(row(snapshot, at, i == 0, false));
            at = (at + line.bytes().length) & 0xFFFF;
        }

        var current = Math.min(model.getSize() - AHEAD, model.getSize() - 1);

        if (current >= 0) {
            list.setSelectedIndex(current);
            list.ensureIndexIsVisible(Math.min(model.getSize() - 1, current + 6));
            list.ensureIndexIsVisible(current);
        }
    }

    void clear() {
        model.clear();
    }

    /**
     * Redraws the gutter after a point has been put down or picked up, without disturbing the
     * listing itself -- which still describes wherever the machine stopped.
     */
    void setBreakpoints(final Set<Integer> breakpoints) {
        this.breakpoints = breakpoints;

        repaint();
    }

    private Row row(
            final MachineSnapshot snapshot,
            final int address,
            final boolean current,
            final boolean ran) {

        var line = Disassembler.at(snapshot::read, address);

        return new Row(address, line.hex(), line.text(), current, ran);
    }

    private final class Renderer extends JLabel implements ListCellRenderer<Row> {
        private Renderer() {
            setOpaque(true);
            setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        }

        @Override
        public Component getListCellRendererComponent(
                final JList<? extends Row> list,
                final Row row,
                final int index,
                final boolean selected,
                final boolean focused) {

            var gutter = breakpoints.contains(row.address()) ? "●" : " ";
            var arrow = row.current() ? "▶" : " ";

            setText(String.format(
                    "%s%s $%04X  %-8s  %s", gutter, arrow, row.address(), row.bytes(), row.text()));

            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            setForeground(colourFor(row, selected, list));

            return this;
        }

        private Color colourFor(final Row row, final boolean selected, final JList<?> list) {
            if (selected) {
                return list.getSelectionForeground();
            }

            if (row.ran()) {
                return Color.GRAY;
            }

            return list.getForeground();
        }
    }
}
