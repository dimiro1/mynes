package com.github.dimiro1.mynes.ui.debugger;

import com.formdev.flatlaf.FlatClientProperties;
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
import javax.swing.SwingConstants;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * A hex view of the CPU's address space.
 * <p>
 * The whole 64K in one scrolling table -- see {@link MemoryModel} for why -- with a box to jump to
 * an address and a list of the places worth jumping to, since "the stack", "cartridge RAM" and
 * "wherever the PC is" are what somebody actually wants and nobody remembers that the APU starts at
 * $4000 while they are thinking about something else.
 * <p>
 * The colours are the point of the renderer. A page of hex is unreadable because every byte has
 * the same weight; fading the zeros makes the shape of the data show through, and the three bytes
 * somebody is most likely looking for -- the one at the PC, the top of the stack, and the one a
 * watchpoint just caught -- are the three that are coloured.
 */
final class MemoryPanel extends JPanel {
    /**
     * The window the PPU and the controllers answer on, which {@code peek} deliberately reads as
     * zero rather than for real. Faded, so that a row of zeros there is not mistaken for a machine
     * that has cleared its registers.
     */
    private static final int REGISTERS_FROM = 0x2000;
    private static final int REGISTERS_TO = 0x4020;

    /**
     * Somewhere in the address space worth a name. The two with no fixed address are resolved
     * against the snapshot when they are chosen.
     */
    private enum Landmark {
        PROMPT("Go to…", -1),
        ZERO_PAGE("Zero page  $0000", 0x0000),
        STACK("Stack  $0100", 0x0100),
        RAM("RAM  $0200", 0x0200),
        PPU("PPU registers  $2000", 0x2000),
        APU("APU and I/O  $4000", 0x4000),
        CART_RAM("Cartridge RAM  $6000", 0x6000),
        PRG("PRG ROM  $8000", 0x8000),
        VECTORS("Vectors  $FFFA", 0xFFFA),
        PC("PC", -1),
        SP("Stack top", -1);

        private final String label;
        private final int address;

        Landmark(final String label, final int address) {
            this.label = label;
            this.address = address;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private final MemoryModel model = new MemoryModel();
    private final JTable table = new JTable(model);
    private final JTextField address = new JTextField(7);
    private final JComboBox<Landmark> landmarks = new JComboBox<>(Landmark.values());
    private final JLabel selected = new JLabel(" ");

    private MachineSnapshot snapshot;

    /**
     * The address a watchpoint caught on the last stop, or -1.
     */
    private int hit = -1;

    MemoryPanel() {
        super(new MigLayout("insets 4 8 8 8, fill", "[][grow][][][]", "[][grow,fill]"));

        table.setFont(Theme.MONOSPACED);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setCellSelectionEnabled(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(Object.class, new Renderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setResizingAllowed(false);
        table.setShowGrid(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setToolTipText("Registers at $2000-$401F read as zero on purpose: looking at them"
                + " for real would clear latches the game is using.");
        table.getSelectionModel().addListSelectionListener(e -> describeSelection());
        table.getColumnModel().getSelectionModel().addListSelectionListener(e -> describeSelection());

        sizeColumns();

        selected.setFont(Theme.MONOSPACED);
        selected.setForeground(Theme.muted());

        address.setFont(Theme.MONOSPACED);
        address.putClientProperty(FlatClientProperties.PLACEHOLDER_TEXT, "$0000");
        address.setToolTipText("An address, in hex. Enter or Go jumps there.");
        address.addActionListener(e -> goToTyped());

        var go = new JButton("Go");
        go.setToolTipText("Jump to that address");
        go.addActionListener(e -> goToTyped());

        landmarks.setToolTipText("Somewhere worth looking");
        landmarks.addActionListener(e -> goToLandmark());

        var scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.dim()));

        add(Theme.heading("Memory"));
        add(selected, "gapleft 12");
        add(address, "gapleft 8, gapright 4");
        add(go, "gapright 8");
        add(landmarks, "wrap");
        add(scroll, "span 5, grow");

        bindGoTo();
    }

    /**
     * Redraws from a fresh snapshot, keeping the scroll position: the user was looking at
     * something, and a step should show them how it changed rather than send them back to $0000.
     */
    void show(final MachineSnapshot snapshot, final Debugger.Stop stop) {
        this.snapshot = snapshot;
        this.hit = stop != null && stop.reason() == Debugger.Reason.WATCHPOINT ? stop.address() : -1;

        model.setSnapshot(snapshot);
        describeSelection();
    }

    void clear() {
        snapshot = null;
        hit = -1;

        model.setSnapshot(null);
        selected.setText(" ");
    }

    /**
     * Scrolls the row holding this address to the top of the view and selects the byte.
     */
    void goTo(final int address) {
        var row = MemoryModel.rowOf(address);
        var column = MemoryModel.columnOf(address);
        var cell = table.getCellRect(row, column, true);

        // The rect asked for is a whole viewport tall, starting at the row, which is what puts the
        // row at the top rather than merely somewhere on screen.
        cell.height = Math.max(cell.height, table.getVisibleRect().height);
        cell.x = 0;
        table.scrollRectToVisible(cell);

        table.changeSelection(row, column, false, false);
    }

    // ================================================================================== internals

    /**
     * Widths from the font rather than from pixel counts, so that a different font size on a
     * different machine still shows whole addresses. This is the bug the old window had: eighteen
     * columns sharing the width equally, and "$..." where the address should have been.
     */
    private void sizeColumns() {
        var metrics = table.getFontMetrics(table.getFont());
        var pad = metrics.charWidth('0');
        var columns = table.getColumnModel();

        table.setRowHeight(metrics.getHeight() + 4);

        for (var column = 0; column < MemoryModel.COLUMNS; column++) {
            var width = switch (column) {
                case MemoryModel.ADDRESS_COLUMN -> metrics.stringWidth("$0000") + pad * 2;
                case MemoryModel.SPACER_COLUMN -> pad;
                case MemoryModel.ASCII_COLUMN -> metrics.stringWidth("W".repeat(16)) + pad * 2;
                default -> metrics.stringWidth("00") + pad;
            };

            var model = columns.getColumn(column);

            model.setMinWidth(width);
            model.setMaxWidth(width);
            model.setPreferredWidth(width);
        }
    }

    /**
     * The platform's shortcut key and G, which is where every editor puts "go to". Bound on the
     * window rather than the field, so that it works while the table has focus.
     */
    private void bindGoTo() {
        var shortcut = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        var key = KeyStroke.getKeyStroke(KeyEvent.VK_G, shortcut);

        getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(key, "goTo");
        getActionMap().put("goTo", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                address.requestFocusInWindow();
                address.selectAll();
            }
        });
    }

    /**
     * A word that is not an address leaves the view where it was and the text selected, rather
     * than jumping somewhere arbitrary.
     */
    private void goToTyped() {
        try {
            goTo(Addresses.parse(address.getText()));
            address.setText("");
            table.requestFocusInWindow();
        } catch (IllegalArgumentException e) {
            address.selectAll();
        }
    }

    private void goToLandmark() {
        var landmark = (Landmark) landmarks.getSelectedItem();

        if (landmark == null || landmark == Landmark.PROMPT) {
            return;
        }

        var target = switch (landmark) {
            case PC -> snapshot == null ? -1 : snapshot.cpu().pc();
            case SP -> snapshot == null ? -1 : Math.min(0x01FF, snapshot.stackTop());
            default -> landmark.address;
        };

        if (target >= 0) {
            goTo(target);
        }

        // Back to the prompt, so the box reads as a menu of places rather than as a setting that
        // is still "PC" three steps later when the view is somewhere else entirely.
        landmarks.setSelectedItem(Landmark.PROMPT);
    }

    private void describeSelection() {
        var row = table.getSelectedRow();
        var column = table.getSelectedColumn();
        var at = row < 0 || column < 0 ? -1 : MemoryModel.addressAt(row, column);

        if (at < 0 || snapshot == null) {
            selected.setText(" ");

            return;
        }

        var value = snapshot.read(at);

        selected.setText(String.format("$%04X = $%02X  %d  %%%8s", at, value, value,
                Integer.toBinaryString(value)).replace(' ', '0').replace("=0$", "= $"));
    }

    private final class Renderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(
                final JTable table,
                final Object value,
                final boolean isSelected,
                final boolean focused,
                final int row,
                final int column) {

            super.getTableCellRendererComponent(table, value, isSelected, false, row, column);

            var at = MemoryModel.addressAt(row, column);
            var text = column == MemoryModel.ASCII_COLUMN || column == MemoryModel.ADDRESS_COLUMN;

            setHorizontalAlignment(text ? SwingConstants.LEFT : SwingConstants.CENTER);
            setFont(Theme.MONOSPACED);

            if (isSelected) {
                return this;
            }

            setBackground(Theme.background());

            if (column == MemoryModel.ADDRESS_COLUMN) {
                setForeground(Theme.muted());
            } else if (at < 0 || snapshot == null) {
                setForeground(Theme.dim());
            } else if (at == hit) {
                setForeground(Theme.breakpoint());
                setFont(Theme.MONOSPACED.deriveFont(Font.BOLD));
            } else if (at == snapshot.cpu().pc()) {
                setForeground(Theme.accent());
                setBackground(Theme.currentRow());
            } else if (at == snapshot.stackTop() && at <= 0x01FF) {
                setForeground(Theme.stackPointer());
                setFont(Theme.MONOSPACED.deriveFont(Font.BOLD));
            } else if (at >= REGISTERS_FROM && at < REGISTERS_TO) {
                setForeground(Theme.dim());
            } else if (snapshot.read(at) == 0) {
                setForeground(Theme.dim());
            } else {
                setForeground(Theme.foreground());
            }

            return this;
        }
    }
}
