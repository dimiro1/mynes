package com.github.dimiro1.mynes.ui.debugger;

import com.formdev.flatlaf.FlatClientProperties;
import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Disassembler;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Map;
import java.util.Set;

/**
 * A listing around the program counter, with a gutter showing where the breakpoints are.
 * <p>
 * The lines above the current one are the addresses that <em>really ran</em>, taken from the
 * debugger's trail, rather than the result of disassembling backwards. Backwards is not solvable on
 * a variable length instruction set: from any address there are several ways the bytes before it
 * could have been divided up and no way to tell which one the CPU took. Guessing produces a listing
 * that looks authoritative and is wrong, which is worse than showing nothing.
 * <p>
 * Typing an address into the box at the top lists from there instead, with no history, since the
 * trail says nothing about code that has not run. The box empty means "follow the PC", which is
 * what a step wants.
 * <p>
 * The rows are painted rather than set as text, because a listing is read by shape: a branch is
 * one colour, a store another, a value is warm and a place is cool, and the eye finds the JSR in a
 * page of loads without reading a single mnemonic. {@link Syntax} decides what each piece is and
 * {@link Theme} what colour that is; this class only puts them where they go.
 */
final class DisassemblyPanel extends JPanel {
    /**
     * What a row can be asked to do, all of it posted onto the emulation thread by the window.
     */
    interface Actions {
        void toggleBreakpoint(int address);

        void runTo(int address);

        void showInMemory(int address);
    }

    /**
     * How much history to show above the PC, how far ahead to disassemble when following it, and
     * how many lines to list from an address typed in.
     */
    private static final int BEHIND = 12;
    private static final int AHEAD = 40;
    private static final int FROM_ADDRESS = 64;

    /**
     * One line of the listing.
     *
     * @param current whether this is the instruction about to run.
     * @param ran     whether it is one that already has, which is what greys the history out.
     */
    record Row(int address, String bytes, String text, boolean current, boolean ran) {
    }

    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new Listing();
    private final JTextField from = new JTextField(10);
    private final Actions actions;

    private Set<Integer> breakpoints = Set.of();
    private Map<Integer, Condition> conditions = Map.of();

    private MachineSnapshot snapshot;

    /**
     * Where to list from, or -1 to follow the PC.
     */
    private int origin = -1;

    /**
     * The row of the instruction about to run, or -1, and whether the view still has to be brought
     * to it -- which cannot be done inside {@link #show} because the window may not have been laid
     * out yet, and a scroll asked of a viewport with no size goes nowhere.
     */
    private int currentIndex = -1;
    private boolean scrollPending;

    DisassemblyPanel(final Actions actions) {
        super(new MigLayout("insets 4 8 8 8, fill", "[][grow][][][]", "[][grow,fill]"));

        this.actions = actions;

        list.setFont(Theme.MONOSPACED);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setFixedCellHeight(list.getFontMetrics(Theme.MONOSPACED).getHeight() + 4);
        list.addMouseListener(new Mouse());

        from.setFont(Theme.MONOSPACED);
        from.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "address");
        from.setToolTipText("List from an address instead of the PC, in hex. Enter or Go.");
        from.addActionListener(e -> listFromTyped());

        // Two buttons rather than a hidden Enter: one to go where the box says, one to come back to
        // the PC, which is the gesture wanted after every excursion and should not need the box to
        // be emptied by hand.
        var go = new JButton("Go");
        go.setToolTipText("List from that address");
        go.addActionListener(e -> listFromTyped());

        var toPC = new JButton("PC");
        toPC.setToolTipText("Follow the PC again");
        toPC.addActionListener(e -> {
            from.setText("");
            listFromTyped();
        });

        var scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.dim()));
        scroll.getViewport().addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(final ComponentEvent e) {
                if (scrollPending) {
                    scrollToCurrent();
                }
            }
        });

        add(Theme.heading("Disassembly"));
        add(new JLabel(""), "growx");
        add(from, "gapright 4");
        add(go, "gapright 4");
        add(toPC, "wrap");
        add(scroll, "span 5, grow");
    }

    /**
     * The address the selection is on -- or, with nothing selected, the one the machine is standing
     * on, so that F9 straight after a stop puts the point where the arrow is. -1 with neither.
     */
    int selectedAddress() {
        var row = list.getSelectedValue();

        if (row != null) {
            return row.address();
        }

        return currentIndex >= 0 && currentIndex < model.getSize()
                ? model.get(currentIndex).address()
                : -1;
    }

    /**
     * Redraws the listing around wherever the machine has stopped.
     */
    void show(
            final MachineSnapshot snapshot,
            final Set<Integer> breakpoints,
            final Map<Integer, Condition> conditions) {

        this.snapshot = snapshot;
        this.breakpoints = breakpoints;
        this.conditions = conditions;

        rebuild();
    }

    void clear() {
        snapshot = null;
        currentIndex = -1;

        model.clear();
    }

    /**
     * Redraws the gutter after a point has been put down or picked up, without disturbing the
     * listing itself -- which still describes wherever the machine stopped.
     */
    void setBreakpoints(final Set<Integer> breakpoints, final Map<Integer, Condition> conditions) {
        this.breakpoints = breakpoints;
        this.conditions = conditions;

        list.repaint();
    }

    // ================================================================================== internals

    private void rebuild() {
        model.clear();
        currentIndex = -1;

        if (snapshot == null) {
            return;
        }

        var pc = snapshot.cpu().pc();

        if (origin < 0) {
            var trail = snapshot.trail();

            for (var i = Math.max(0, trail.length - BEHIND); i < trail.length; i++) {
                // The last entry of the trail is the instruction that just ran, which is not the
                // one about to run: showing it twice would put a second arrow on the listing.
                if (trail[i] != pc) {
                    model.addElement(row(trail[i], false, true));
                }
            }
        }

        var at = origin < 0 ? pc : origin;
        var count = origin < 0 ? AHEAD : FROM_ADDRESS;

        for (var i = 0; i < count; i++) {
            var current = at == pc;

            if (current) {
                currentIndex = model.getSize();
            }

            var line = Disassembler.at(snapshot::read, at);

            model.addElement(new Row(at, line.hex(), line.text(), current, false));
            at = (at + line.bytes().length) & 0xFFFF;
        }

        // Nothing selected on a fresh listing: the current row already has its arrow and its tint,
        // and a selection over it would paint out the very colours the listing exists to show.
        list.clearSelection();

        // After the pending events rather than now: on the first stop the window has not been laid
        // out yet, and asking a viewport with no height to show a row does nothing at all.
        scrollPending = true;
        SwingUtilities.invokeLater(this::scrollToCurrent);
    }

    /**
     * Puts the current row a third of the way down the view, so that some history is above it and
     * most of the view is what comes next.
     */
    private void scrollToCurrent() {
        var index = currentIndex >= 0 ? currentIndex : 0;
        var visible = list.getVisibleRect();

        if (visible.height <= 0 || model.isEmpty()) {
            return;
        }

        var cell = list.getCellBounds(index, index);

        if (cell == null) {
            return;
        }

        cell.y = Math.max(0, cell.y - visible.height / 3);
        cell.height = visible.height;
        list.scrollRectToVisible(cell);

        scrollPending = false;
    }

    private void listFromTyped() {
        var text = from.getText().trim();

        try {
            origin = text.isEmpty() ? -1 : Addresses.parse(text);

            if (snapshot != null) {
                rebuild();
            }
        } catch (IllegalArgumentException e) {
            from.selectAll();
        }
    }

    private Row row(final int address, final boolean current, final boolean ran) {
        var line = Disassembler.at(snapshot::read, address);

        return new Row(address, line.hex(), line.text(), current, ran);
    }

    /**
     * The address an operand names, or -1 when it names none -- an immediate, a register, or
     * nothing at all. What "show in memory" jumps to.
     */
    private static int operandAddress(final Row row) {
        for (var token : Syntax.tokens(row.text())) {
            if (token.kind() == Syntax.Kind.ADDRESS) {
                return Addresses.parse(token.text());
            }
        }

        return -1;
    }

    private void popup(final MouseEvent e) {
        var index = list.locationToIndex(e.getPoint());

        if (index < 0) {
            return;
        }

        list.setSelectedIndex(index);

        var row = model.get(index);
        var menu = new JPopupMenu();

        var toggle = new JMenuItem(breakpoints.contains(row.address())
                ? "Remove Breakpoint"
                : "Set Breakpoint");
        toggle.setAccelerator(KeyStroke.getKeyStroke("F9"));
        toggle.addActionListener(a -> actions.toggleBreakpoint(row.address()));

        var runTo = new JMenuItem("Run to Here");
        runTo.setEnabled(!row.ran());
        runTo.addActionListener(a -> actions.runTo(row.address()));

        var target = operandAddress(row);
        var inMemory = new JMenuItem(target < 0
                ? "Show Operand in Memory"
                : String.format("Show $%04X in Memory", target));
        inMemory.setEnabled(target >= 0);
        inMemory.addActionListener(a -> actions.showInMemory(target));

        menu.add(toggle);
        menu.add(runTo);
        menu.addSeparator();
        menu.add(inMemory);
        menu.show(list, e.getX(), e.getY());
    }

    private final class Mouse extends MouseAdapter {
        @Override
        public void mousePressed(final MouseEvent e) {
            if (e.isPopupTrigger()) {
                popup(e);
            }
        }

        @Override
        public void mouseReleased(final MouseEvent e) {
            if (e.isPopupTrigger()) {
                popup(e);
            }
        }

        @Override
        public void mouseClicked(final MouseEvent e) {
            if (SwingUtilities.isLeftMouseButton(e)) {
                var index = list.locationToIndex(e.getPoint());

                // A click in the gutter is the breakpoint gesture every debugger has; a double
                // click anywhere on the row is the same thing for people who do not know that.
                if (index >= 0 && (e.getClickCount() == 2 || e.getX() < Renderer.GUTTER)) {
                    actions.toggleBreakpoint(model.get(index).address());
                }
            }
        }
    }

    /**
     * A list whose tooltip is the condition on the breakpoint under the pointer, since a hollow
     * circle says "conditional" and nothing about what the condition is.
     */
    private final class Listing extends JList<Row> {
        private Listing() {
            super(model);
        }

        @Override
        public String getToolTipText(final MouseEvent e) {
            var index = locationToIndex(e.getPoint());

            if (index < 0 || e.getX() >= Renderer.GUTTER) {
                return null;
            }

            var condition = conditions.get(model.get(index).address());

            return condition == null ? null : "if " + condition.text();
        }
    }

    /**
     * One row, painted. Left to right: the gutter, the address, the bytes, then the instruction
     * one token at a time in its own colour.
     */
    private final class Renderer extends JComponent implements ListCellRenderer<Row> {
        static final int GUTTER = 28;

        private Row row;
        private boolean selected;

        private Renderer() {
            setFont(Theme.MONOSPACED);
            setToolTipText(null);
        }

        @Override
        public Component getListCellRendererComponent(
                final JList<? extends Row> list,
                final Row row,
                final int index,
                final boolean selected,
                final boolean focused) {

            this.row = row;
            this.selected = selected;

            return this;
        }

        @Override
        public Dimension getPreferredSize() {
            var metrics = getFontMetrics(getFont());

            return new Dimension(
                    GUTTER + metrics.charWidth('0') * 40, metrics.getHeight() + 4);
        }

        @Override
        protected void paintComponent(final Graphics g) {
            var g2 = (Graphics2D) g.create();

            try {
                g2.setRenderingHint(
                        RenderingHints.KEY_TEXT_ANTIALIASING,
                        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setRenderingHint(
                        RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                g2.setColor(selected
                        ? Theme.selectionBackground()
                        : row.current() ? Theme.currentRow() : Theme.background());
                g2.fillRect(0, 0, getWidth(), getHeight());

                paintGutter(g2);
                paintText(g2);
            } finally {
                g2.dispose();
            }
        }

        private void paintGutter(final Graphics2D g2) {
            var middle = getHeight() / 2;

            if (breakpoints.contains(row.address())) {
                g2.setColor(Theme.breakpoint());

                // Hollow when there is a condition: a point that only sometimes stops.
                if (conditions.containsKey(row.address())) {
                    g2.setStroke(new BasicStroke(1.5f));
                    g2.drawOval(4, middle - 4, 8, 8);
                } else {
                    g2.fillOval(4, middle - 4, 9, 9);
                }
            }

            if (row.current()) {
                g2.setColor(selected ? Theme.selectionForeground() : Theme.accent());

                var x = 16;
                var xs = new int[]{x, x + 8, x};
                var ys = new int[]{middle - 5, middle, middle + 5};

                g2.fillPolygon(xs, ys, 3);
            }
        }

        private void paintText(final Graphics2D g2) {
            var metrics = g2.getFontMetrics();
            var baseline = (getHeight() + metrics.getAscent() - metrics.getDescent()) / 2;
            var column = metrics.charWidth('0');
            var x = GUTTER;

            g2.setColor(colour(Theme.muted()));
            g2.drawString(String.format("$%04X", row.address()), x, baseline);
            x += column * 7;

            g2.setColor(colour(Theme.dim()));
            g2.drawString(row.bytes(), x, baseline);
            x += column * 10;

            for (var token : Syntax.tokens(row.text())) {
                g2.setColor(colour(Theme.colourFor(token.kind())));
                g2.drawString(token.text(), x, baseline);
                x += metrics.stringWidth(token.text());
            }
        }

        /**
         * The colour a piece would have, overridden by the two states that flatten everything:
         * a selected row is all in the selection's colour, and a row that already ran is all faded.
         */
        private Color colour(final Color wanted) {
            if (selected) {
                return Theme.selectionForeground();
            }

            return row.ran() ? Theme.muted() : wanted;
        }
    }
}
