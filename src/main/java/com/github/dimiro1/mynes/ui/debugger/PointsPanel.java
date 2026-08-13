package com.github.dimiro1.mynes.ui.debugger;

import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.Font;
import java.util.Set;
import java.util.function.IntConsumer;

/**
 * The breakpoints and the watchpoints, listed and editable.
 * <p>
 * Two lists over one pair of controls, because they are the same gesture on different questions:
 * stop when the machine <em>reaches</em> here, and stop when it <em>writes</em> here.
 * <p>
 * The lists are this window's own copy rather than a view onto the debugger, the same way the Debug
 * menu's ticks are the front end's copy of the PPU's layer switches. Anything that changes a point
 * is posted onto the emulation thread and the list is updated from here, which is what lets the
 * debugger itself have no synchronisation in it at all.
 */
final class PointsPanel extends JPanel {
    private final DefaultListModel<Integer> breakpoints = new DefaultListModel<>();
    private final DefaultListModel<Integer> watchpoints = new DefaultListModel<>();

    private final JTextField entry = new JTextField(6);

    PointsPanel(final IntConsumer onBreak, final IntConsumer onWatch, final Runnable onClear) {
        super(new MigLayout("insets 8, fill", "[grow,fill][grow,fill]", "[][grow,fill][]"));

        setBorder(BorderFactory.createTitledBorder("Points"));

        entry.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        var addBreak = new JButton("Break at");
        var addWatch = new JButton("Watch");
        var clear = new JButton("Clear all");

        addBreak.addActionListener(e -> withAddress(onBreak));
        addWatch.addActionListener(e -> withAddress(onWatch));
        clear.addActionListener(e -> onClear.run());

        // Double-clicking a listed point picks it up again, which is the same gesture the
        // disassembly uses to put one down.
        var breakList = list(breakpoints, address -> onBreak.accept(address));
        var watchList = list(watchpoints, address -> onWatch.accept(address));

        add(new JLabel("Breakpoints"));
        add(new JLabel("Watchpoints"), "wrap");
        add(new JScrollPane(breakList), "grow");
        add(new JScrollPane(watchList), "grow, wrap");
        add(entry, "split 4, growx");
        add(addBreak);
        add(addWatch);
        add(clear, "span 2");
    }

    /**
     * Replaces both lists with what the debugger actually holds, which is the only thing that keeps
     * this window's copy honest after a clear or a load.
     */
    void show(final Set<Integer> breaks, final Set<Integer> watches) {
        breakpoints.clear();
        breaks.forEach(breakpoints::addElement);

        watchpoints.clear();
        watches.forEach(watchpoints::addElement);
    }

    private JList<Integer> list(
            final DefaultListModel<Integer> model, final IntConsumer onDoubleClick) {

        var list = new JList<>(model);

        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setCellRenderer((jList, address, index, selected, focused) -> {
            var label = new JLabel(String.format("$%04X", address));

            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            label.setOpaque(true);
            label.setBackground(selected ? jList.getSelectionBackground() : jList.getBackground());
            label.setForeground(selected ? jList.getSelectionForeground() : jList.getForeground());

            return label;
        });

        list.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(final java.awt.event.MouseEvent e) {
                var selected = list.getSelectedValue();

                if (e.getClickCount() == 2 && selected != null) {
                    onDoubleClick.accept(selected);
                }
            }
        });

        return list;
    }

    /**
     * Takes an address, and does nothing at all with a word that is not one.
     * <p>
     * Hexadecimal by default, with {@code $} and {@code 0x} accepted, which is the same rule the
     * memory view's address box keeps and for the same reason: every address in this window is
     * written in hex, so one typed into it is meant in hex.
     */
    private void withAddress(final IntConsumer action) {
        var text = entry.getText().trim();

        if (text.isEmpty()) {
            return;
        }

        try {
            var parsed = text.startsWith("$")
                    ? Integer.parseInt(text.substring(1), 16)
                    : text.startsWith("0x") || text.startsWith("0X")
                    ? Integer.parseInt(text.substring(2), 16)
                    : Integer.parseInt(text, 16);

            action.accept(parsed & 0xFFFF);
            entry.setText("");
        } catch (NumberFormatException e) {
            entry.selectAll();
        }
    }
}
