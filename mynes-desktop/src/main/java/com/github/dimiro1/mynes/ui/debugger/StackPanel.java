package com.github.dimiro1.mynes.ui.debugger;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;

/**
 * What is on the stack, top first.
 * <p>
 * A window of its own is too much for one page of memory, and the hex view is the wrong shape for
 * it: the stack grows downwards, the interesting end is wherever SP happens to be, and the question
 * is nearly always "what will RTS return to", which is the two bytes on top read as a little endian
 * word. So they are listed the way they will come off, with the return address a JSR pushed
 * decoded beside the pair that holds it.
 */
final class StackPanel extends JPanel {
    /**
     * One byte of stack.
     *
     * @param word the sixteen bit value this byte and the one above it make, or -1 for the topmost
     *             byte, which has nothing above it to pair with.
     */
    private record Entry(int address, int value, int word) {
    }

    private final JLabel heading = Theme.heading("Stack");
    private final DefaultListModel<Entry> model = new DefaultListModel<>();
    private final JList<Entry> list = new JList<>(model);

    private boolean stale;

    StackPanel() {
        super(new BorderLayout());

        list.setFont(Theme.MONOSPACED);
        list.setCellRenderer(new Renderer());
        list.setFocusable(false);

        var scroll = new JScrollPane(list);
        scroll.setBorder(null);

        setBorder(BorderFactory.createEmptyBorder(4, 8, 8, 8));
        add(heading, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    void show(final MachineSnapshot snapshot) {
        var bytes = snapshot.stack();
        var top = snapshot.stackTop();

        stale = false;
        model.clear();

        for (var i = 0; i < bytes.length; i++) {
            // A word is this byte as the low half and the next one up as the high half, which is
            // how a pushed address reads once the pointer stands under its low byte.
            var word = i + 1 < bytes.length ? bytes[i] | (bytes[i + 1] << 8) : -1;

            model.addElement(new Entry(top + i, bytes[i], word));
        }

        heading.setText(bytes.length == 0 ? "STACK  EMPTY" : "STACK  " + bytes.length + " BYTES");
    }

    void clear() {
        model.clear();
        heading.setText("STACK");
    }

    /**
     * Fades the listing, because the machine has moved on and the stack with it.
     */
    void stale() {
        stale = true;
        repaint();
    }

    private final class Renderer extends JLabel implements ListCellRenderer<Entry> {
        private Renderer() {
            setOpaque(true);
            setFont(Theme.MONOSPACED);
            setBorder(BorderFactory.createEmptyBorder(1, 4, 1, 4));
        }

        @Override
        public Component getListCellRendererComponent(
                final JList<? extends Entry> list,
                final Entry entry,
                final int index,
                final boolean selected,
                final boolean focused) {

            var word = entry.word() < 0 ? "" : String.format("  → $%04X", entry.word());

            setText(String.format("$%04X  $%02X%s", entry.address(), entry.value(), word));
            setBackground(selected ? Theme.selectionBackground() : Theme.background());
            setForeground(selected
                    ? Theme.selectionForeground()
                    : stale ? Theme.muted() : index == 0 ? Theme.stackPointer() : Theme.foreground());

            return this;
        }
    }
}
