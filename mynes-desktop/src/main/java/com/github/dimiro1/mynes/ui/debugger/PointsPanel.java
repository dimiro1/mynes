package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Debugger;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import java.awt.Color;
import java.awt.Font;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The breakpoints and the watchpoints, listed and editable.
 * <p>
 * Two lists over one pair of controls, because they are the same gesture on different questions:
 * stop when the machine <em>reaches</em> here, and stop when it <em>touches</em> here.
 * <p>
 * The lists are this window's own copy rather than a view onto the debugger, the same way the Debug
 * menu's ticks are the front end's copy of the PPU's layer switches. Anything that changes a point
 * is posted onto the emulation thread and the list is updated from here, which is what lets the
 * debugger itself have no synchronisation in it at all.
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
     * One line of either list: an address, and whatever else there is to say about it -- the
     * condition on a breakpoint, the direction of a watchpoint.
     */
    private record Point(int address, String detail) {
    }

    private final DefaultListModel<Point> breakpoints = new DefaultListModel<>();
    private final DefaultListModel<Point> watchpoints = new DefaultListModel<>();

    private final JTextField entry = new JTextField(10);
    private final JComboBox<Debugger.Access> facing = new JComboBox<>(Debugger.Access.values());
    private final JLabel complaint = new JLabel(" ");

    PointsPanel(final Points points) {
        super(new MigLayout("insets 8, fill", "[grow,fill][grow,fill]", "[][grow,fill][][]"));

        setBorder(BorderFactory.createTitledBorder("Points"));

        entry.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        entry.setToolTipText("$C000, or $C000 if a == $10");

        facing.setSelectedItem(Debugger.Access.WRITE);
        facing.setToolTipText("which way a watchpoint looks");

        complaint.setForeground(Color.RED);
        complaint.setFont(complaint.getFont().deriveFont(Font.PLAIN, 11f));

        var addBreak = new JButton("Break at");
        var addWatch = new JButton("Watch");
        var clear = new JButton("Clear all");

        addBreak.addActionListener(e -> withEntry(typed ->
                points.breakAt(typed.address(), typed.condition())));

        addWatch.addActionListener(e -> withEntry(typed -> {
            if (typed.condition() != null) {
                throw new IllegalArgumentException("a watchpoint takes no condition.");
            }

            points.watchAt(typed.address(), (Debugger.Access) facing.getSelectedItem());
        }));

        clear.addActionListener(e -> points.clear());

        // Double-clicking a listed point picks it up again, which is the same gesture the
        // disassembly uses to put one down -- and, unlike the buttons, is always a removal: a point
        // that is already listed is one somebody is pointing at to be rid of.
        var breakList = list(breakpoints, points::removeBreakpoint);
        var watchList = list(watchpoints, points::removeWatchpoint);

        add(new JLabel("Breakpoints"));
        add(new JLabel("Watchpoints"), "wrap");
        add(new JScrollPane(breakList), "grow");
        add(new JScrollPane(watchList), "grow, wrap");
        add(entry, "split 5, growx");
        add(addBreak);
        add(facing);
        add(addWatch);
        add(clear, "span 2, wrap");
        add(complaint, "span 2, growx");
    }

    /**
     * Replaces both lists with what the debugger actually holds, which is the only thing that keeps
     * this window's copy honest after a clear or a load.
     */
    void show(
            final Set<Integer> breaks,
            final Map<Integer, Condition> conditions,
            final Map<Integer, Debugger.Access> watches) {

        breakpoints.clear();
        breaks.forEach(address -> {
            var condition = conditions.get(address);

            breakpoints.addElement(
                    new Point(address, condition == null ? "" : "if " + condition.text()));
        });

        watchpoints.clear();
        watches.forEach((address, on) -> watchpoints.addElement(new Point(address, on.id())));
    }

    private JList<Point> list(
            final DefaultListModel<Point> model, final java.util.function.IntConsumer onDoubleClick) {

        var list = new JList<>(model);

        list.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        list.setCellRenderer((jList, point, index, selected, focused) -> {
            var label = new JLabel(String.format("$%04X  %s", point.address(), point.detail()));

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
                    onDoubleClick.accept(selected.address());
                }
            }
        });

        return list;
    }

    /**
     * What was typed into the one text field: an address, and optionally a condition after
     * {@code if}.
     */
    private record Typed(int address, Condition condition) {
    }

    /**
     * Reads the field, and says what is wrong with it rather than shrugging.
     * <p>
     * A condition that could not be read used to be a silently ignored button press, which on a
     * debugger is the worst possible answer: the point looks set and the machine never stops.
     */
    private void withEntry(final Consumer<Typed> action) {
        var text = entry.getText().trim();

        if (text.isEmpty()) {
            return;
        }

        try {
            action.accept(parse(text));

            entry.setText("");
            complaint.setText(" ");
        } catch (IllegalArgumentException e) {
            complaint.setText(e.getMessage());
            entry.selectAll();
        }
    }

    /**
     * Takes an address and, after {@code if}, a condition.
     * <p>
     * The address is hexadecimal by default, with {@code $} and {@code 0x} accepted, which is the
     * same rule the memory view's address box keeps and for the same reason: every address in this
     * window is written in hex, so one typed into it is meant in hex. The condition after it keeps
     * its own rule -- decimal unless it says otherwise -- because it is a little expression rather
     * than an address, and it is the same one the interactive session keeps.
     */
    private static Typed parse(final String text) {
        var words = text.split("\\s+", 3);
        var address = address(words[0]);

        if (words.length == 1) {
            return new Typed(address, null);
        }

        if (!words[1].equalsIgnoreCase("if") || words.length < 3) {
            throw new IllegalArgumentException("expected \"if\" and a condition after the address.");
        }

        return new Typed(address, Condition.parse(words[2]));
    }

    private static int address(final String word) {
        try {
            if (word.startsWith("$")) {
                return Integer.parseInt(word.substring(1), 16) & 0xFFFF;
            }

            if (word.startsWith("0x") || word.startsWith("0X")) {
                return Integer.parseInt(word.substring(2), 16) & 0xFFFF;
            }

            return Integer.parseInt(word, 16) & 0xFFFF;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + word + "\" is not an address.");
        }
    }
}
