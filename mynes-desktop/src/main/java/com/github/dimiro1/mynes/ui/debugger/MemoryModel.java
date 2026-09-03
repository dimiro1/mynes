package com.github.dimiro1.mynes.ui.debugger;

import javax.swing.table.AbstractTableModel;

/**
 * The whole address space, sixteen bytes to a row, as a table.
 * <p>
 * All 4096 rows rather than a page of sixteen with a box to move it, because a scroll bar is the
 * control everybody already knows for "somewhere else in a long thing", and because a table asks
 * its model only for the cells it is painting -- so a model over 64K costs exactly what a model over
 * 256 bytes does until somebody scrolls.
 * <p>
 * Every byte comes out of the snapshot rather than out of the machine, which is what makes it safe
 * to have a table model at all: Swing asks a model for cells whenever it repaints, and a model that
 * answered by reading the machine would be reading a running one. With no snapshot every cell is
 * {@code --}, which is what a machine that has not stopped yet looks like.
 * <p>
 * The columns are an address, eight bytes, a gap, eight more and the text. The gap is a real column
 * rather than a wider border because a table cannot widen one border: it is how a hex editor lets
 * the eye count to eight, and a row of sixteen identical cells is where an off-by-one gets read.
 */
final class MemoryModel extends AbstractTableModel {
    static final int BYTES_PER_ROW = 16;
    static final int ROWS = 0x10000 / BYTES_PER_ROW;

    static final int ADDRESS_COLUMN = 0;
    static final int SPACER_COLUMN = 9;
    static final int ASCII_COLUMN = 18;
    static final int COLUMNS = 19;

    private MachineSnapshot snapshot;

    void setSnapshot(final MachineSnapshot snapshot) {
        this.snapshot = snapshot;

        fireTableDataChanged();
    }

    static int rowOf(final int address) {
        return (address & 0xFFFF) / BYTES_PER_ROW;
    }

    static int addressOfRow(final int row) {
        return row * BYTES_PER_ROW;
    }

    /**
     * Which byte a cell shows, or -1 for the address, the gap and the text.
     */
    static int addressAt(final int row, final int column) {
        var offset = byteOf(column);

        return offset < 0 ? -1 : addressOfRow(row) + offset;
    }

    /**
     * Which of the sixteen a column holds, or -1 for the columns that hold none.
     */
    static int byteOf(final int column) {
        if (column > ADDRESS_COLUMN && column < SPACER_COLUMN) {
            return column - 1;
        }

        if (column > SPACER_COLUMN && column < ASCII_COLUMN) {
            return column - 2;
        }

        return -1;
    }

    /**
     * The cell holding this address, on the table's own axes.
     */
    static int columnOf(final int address) {
        var offset = address & (BYTES_PER_ROW - 1);

        return offset < 8 ? offset + 1 : offset + 2;
    }

    @Override
    public int getRowCount() {
        return ROWS;
    }

    @Override
    public int getColumnCount() {
        return COLUMNS;
    }

    @Override
    public String getColumnName(final int column) {
        if (column == ASCII_COLUMN) {
            return "ASCII";
        }

        var offset = byteOf(column);

        return offset < 0 ? "" : String.format("%X", offset);
    }

    @Override
    public Object getValueAt(final int row, final int column) {
        if (column == ADDRESS_COLUMN) {
            return String.format("$%04X", addressOfRow(row));
        }

        if (column == SPACER_COLUMN) {
            return "";
        }

        if (column == ASCII_COLUMN) {
            return snapshot == null ? "" : ascii(addressOfRow(row));
        }

        if (snapshot == null) {
            return "--";
        }

        return String.format("%02X", snapshot.read(addressAt(row, column)));
    }

    private String ascii(final int start) {
        var text = new StringBuilder(BYTES_PER_ROW);

        for (var i = 0; i < BYTES_PER_ROW; i++) {
            var value = snapshot.read(start + i);

            text.append(value >= 0x20 && value < 0x7F ? (char) value : '.');
        }

        return text.toString();
    }
}
