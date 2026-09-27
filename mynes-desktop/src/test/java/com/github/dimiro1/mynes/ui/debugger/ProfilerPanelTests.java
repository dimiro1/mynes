package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.ExecutionProfile;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ProfilerPanelTests {
    @Test
    void doubleClickNavigatesToTheSourceLineOrPhysicalPrgOffset() {
        var file = new SourceProgram.SourceFile(1, "src/main.s", Path.of("src/main.s"),
                List.of("reset:", "  sei"), false);
        var range = new SourceProgram.Range(4, 0x8004, 1);
        var line = new SourceProgram.SourceLine(file, 2, "  sei", List.of(range));
        var program = new SourceProgram(Path.of("game.dbg"), List.of(file),
                Map.of(1, Map.of(2, List.of(range))), List.of(line), List.of(), 0x40, List.of());
        var navigatedLine = new AtomicReference<SourceProgram.SourceLine>();
        var navigatedOffset = new AtomicInteger(-1);
        var panel = new ProfilerPanel(() -> {}, () -> {}, () -> {}, (source, offset) -> {
            navigatedLine.set(source);
            navigatedOffset.set(offset);
        });
        panel.setSourceProgram(program);
        panel.show(new ExecutionProfile.Snapshot(List.of(
                new ExecutionProfile.Entry(4, 10, 30),
                new ExecutionProfile.Entry(20, 5, 20)), 15, 50));
        Views.paint(panel);

        var table = Views.find(panel, JTable.class);
        doubleClick(table, 0);
        assertEquals(line, navigatedLine.get());
        assertEquals(4, navigatedOffset.get());

        doubleClick(table, 1);
        assertNull(navigatedLine.get());
        assertEquals(20, navigatedOffset.get());
    }

    private static void doubleClick(final JTable table, final int row) {
        var cell = table.getCellRect(row, 0, true);
        table.dispatchEvent(new MouseEvent(table, MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, cell.x + 3, cell.y + 3,
                2, false, MouseEvent.BUTTON1));
    }
}
