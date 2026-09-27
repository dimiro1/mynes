package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PointsPanelTests {
    @Test
    void oneListShowsEveryKindAndRemovesTheSelectedKind() {
        var actions = new Recording();
        var panel = new PointsPanel(actions);
        panel.show(Set.of(0x8000), Map.of(), Set.of(0x123), null,
                Map.of(0x0300, Debugger.Access.WRITE));

        var table = Views.find(panel, JTable.class);
        assertEquals(3, table.getRowCount());
        assertEquals("Breakpoint", table.getValueAt(0, 0));
        assertEquals("Source", table.getValueAt(1, 0));
        assertEquals("Watchpoint", table.getValueAt(2, 0));

        for (var row = 0; row < table.getRowCount(); row++) {
            table.setRowSelectionInterval(row, row);
            table.getActionMap().get("remove").actionPerformed(
                    new ActionEvent(table, ActionEvent.ACTION_PERFORMED, "remove"));
        }
        assertEquals(List.of("break $8000", "source $00123", "watch $0300"), actions.removed);
    }

    private static final class Recording implements PointsPanel.Points {
        final List<String> removed = new ArrayList<>();

        @Override public void breakAt(final int address, final Condition condition) { }
        @Override public void watchAt(final int address, final Debugger.Access on) { }
        @Override public void removeBreakpoint(final int address) {
            removed.add(String.format("break $%04X", address));
        }
        @Override public void removePRGBreakpoint(final int offset) {
            removed.add(String.format("source $%05X", offset));
        }
        @Override public void removeWatchpoint(final int address) {
            removed.add(String.format("watch $%04X", address));
        }
        @Override public void clear() { }
    }
}
