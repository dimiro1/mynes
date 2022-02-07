package com.github.dimiro1.mynes.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class MirroredMemoryTests {

    @Test
    void testBasicMirroredMemory() {
        var bus = new MappedMemory();
        var iRam = new MemoryBlock(0x0800, "iRam");

        bus.map(0x0000, 0x1FFF, new MirroredMemory(iRam));
        bus.write(0x0, 0xAA);

        assertEquals(0xAA, bus.read(0x0));
        assertEquals(0xAA, bus.read(0x0800));
    }

    @Test
    void testComplexMirroredMemory() {
        var bus = new MappedMemory();
        var ppuRegs = new MemoryBlock(0x0008, "ppuRegs");

        bus.map(0x2000, 0x3FFF, new MirroredMemory(ppuRegs));
        bus.write(0x2000, 0x1);
        bus.write(0x2001, 0x2);
        bus.write(0x2002, 0x3);
        bus.write(0x2003, 0x4);
        bus.write(0x2004, 0x5);
        bus.write(0x2005, 0x6);
        bus.write(0x2006, 0x7);
        bus.write(0x2007, 0x8);

        assertEquals(0x1, bus.read(0x2008));
        assertEquals(0x2, bus.read(0x2009));
        assertEquals(0x3, bus.read(0x200A));
        assertEquals(0x4, bus.read(0x200B));
        assertEquals(0x5, bus.read(0x200C));
        assertEquals(0x6, bus.read(0x200D));
        assertEquals(0x7, bus.read(0x200E));
    }
}
