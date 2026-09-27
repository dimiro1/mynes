package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.Test;

import javax.swing.JList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisassemblyProfileNavigationTests {
    @Test
    void opensAnUnmappedPhysicalPrgOffsetFromTheProfile() {
        var panel = new DisassemblyPanel(new DisassemblyPanel.Actions() {
            @Override public void toggleBreakpoint(final int address) {}
            @Override public void runTo(final int address) {}
            @Override public void showInMemory(final int address) {}
        });
        var rom = new byte[0x4000];
        rom[0x123] = (byte) 0xA9;
        rom[0x124] = 0x42;
        rom[0x125] = (byte) 0x60;

        panel.goToPRG(0x123, rom);
        Views.paint(panel);

        var list = Views.find(panel, JList.class);
        var first = (DisassemblyPanel.Row) list.getModel().getElementAt(0);
        assertEquals(0x123, first.prgOffset());
        assertTrue(first.text().contains("LDA"));
        assertEquals(-1, panel.selectedAddress());
    }
}
