package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.Test;

import javax.swing.JTable;
import javax.swing.JTextField;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WatchesPanelTests {
    @Test
    void resolvesASymbolAndEditsALittleEndianWord() {
        var nes = new NES(Cart.load(rom(), "watches.nes"));
        var debugger = new Debugger();
        debugger.attach(nes);
        nes.getMemory().write(0x20, 0x34);
        nes.getMemory().write(0x21, 0x12);

        var writtenAddress = new AtomicInteger(-1);
        var writtenValue = new AtomicInteger(-1);
        var writtenWidth = new AtomicInteger(-1);
        var panel = new WatchesPanel((address, value, width) -> {
            writtenAddress.set(address);
            writtenValue.set(value);
            writtenWidth.set(width);
        });
        panel.setSourceProgram(new SourceProgram(Path.of("game.dbg"), List.of(), Map.of(),
                List.of(), List.of(new SourceProgram.Symbol(
                        "player_x", "lab", 0x20, null, null, List.of())), 0x4000, List.of()));
        panel.show(MachineSnapshot.of(nes, debugger));

        var entry = Views.find(panel, JTextField.class);
        entry.setText("player_x:16");
        entry.postActionEvent();

        var table = Views.find(panel, JTable.class);
        assertEquals(1, table.getRowCount());
        assertEquals("$0020", table.getValueAt(0, 1));
        assertEquals("$1234", table.getValueAt(0, 2));
        table.getModel().setValueAt("$ABCD", 0, 2);
        assertEquals(0x20, writtenAddress.get());
        assertEquals(0xABCD, writtenValue.get());
        assertEquals(2, writtenWidth.get());
    }

    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];
        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;
        image[16 + 0x3FFC] = 0;
        image[16 + 0x3FFD] = (byte) 0x80;
        return image;
    }
}
