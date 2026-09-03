package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The arithmetic between a cell and an address, which is the part of a hex view that is wrong
 * quietly: a byte shown one column over still looks like a byte.
 */
class MemoryModelTests {
    @Test
    void itCoversTheWholeAddressSpaceSixteenToARow() {
        var model = new MemoryModel();

        assertEquals(4096, model.getRowCount());
        assertEquals("$0000", model.getValueAt(0, MemoryModel.ADDRESS_COLUMN));
        assertEquals("$FFF0", model.getValueAt(4095, MemoryModel.ADDRESS_COLUMN));
    }

    @Test
    void theGapColumnHoldsNoByteAndTheOthersHoldOneEach() {
        assertEquals(-1, MemoryModel.byteOf(MemoryModel.ADDRESS_COLUMN));
        assertEquals(-1, MemoryModel.byteOf(MemoryModel.SPACER_COLUMN));
        assertEquals(-1, MemoryModel.byteOf(MemoryModel.ASCII_COLUMN));

        assertEquals(0, MemoryModel.byteOf(1));
        assertEquals(7, MemoryModel.byteOf(8));
        assertEquals(8, MemoryModel.byteOf(10));
        assertEquals(15, MemoryModel.byteOf(17));
    }

    @Test
    void anAddressAndACellRoundTrip() {
        for (var address : new int[]{0x0000, 0x0007, 0x0008, 0x000F, 0x0300, 0x8082, 0xFFFF}) {
            var row = MemoryModel.rowOf(address);
            var column = MemoryModel.columnOf(address);

            assertEquals(address, MemoryModel.addressAt(row, column), Integer.toHexString(address));
        }
    }

    @Test
    void withoutASnapshotEveryByteIsADash() {
        var model = new MemoryModel();

        assertEquals("--", model.getValueAt(0, 1));
        assertEquals("", model.getValueAt(0, MemoryModel.ASCII_COLUMN));
        assertEquals("", model.getValueAt(0, MemoryModel.SPACER_COLUMN));
    }

    @Test
    void bytesAndTheirTextComeOutOfTheSnapshot() {
        var nes = new NES(Cart.load(rom(), "memory-model.nes"));
        var debugger = new Debugger();
        debugger.attach(nes);

        var memory = nes.getMemory();
        memory.write(0x0300, 'H');
        memory.write(0x0301, 'i');
        memory.write(0x030F, 0x7F);

        var model = new MemoryModel();
        model.setSnapshot(MachineSnapshot.of(nes, debugger));

        var row = MemoryModel.rowOf(0x0300);

        assertEquals("48", model.getValueAt(row, 1));
        assertEquals("69", model.getValueAt(row, 2));
        assertEquals("Hi..............", model.getValueAt(row, MemoryModel.ASCII_COLUMN));
    }

    @Test
    void theHeaderCountsTheBytesInHex() {
        var model = new MemoryModel();

        assertEquals("", model.getColumnName(MemoryModel.ADDRESS_COLUMN));
        assertEquals("0", model.getColumnName(1));
        assertEquals("7", model.getColumnName(8));
        assertEquals("", model.getColumnName(MemoryModel.SPACER_COLUMN));
        assertEquals("8", model.getColumnName(10));
        assertEquals("F", model.getColumnName(17));
        assertEquals("ASCII", model.getColumnName(MemoryModel.ASCII_COLUMN));
    }

    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        image[16] = 0x4C;
        image[17] = 0x00;
        image[18] = (byte) 0x80;

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
