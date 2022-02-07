package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.cart.Cart;
import com.github.dimiro1.mynes.memory.MappedMemory;
import com.github.dimiro1.mynes.memory.MemoryBlock;
import com.github.dimiro1.mynes.memory.MirroredMemory;

public class NES {
    public NES(final Cart cart) {
        var bus = new MappedMemory();
        var iRam = new MemoryBlock(0x0800, "iRam");
        var ppuRegs = new MemoryBlock(0x0008, "ppuRegs");
        var apuRegs = new MemoryBlock(0x0018, "apuRegs");
        var eRom = new MemoryBlock(0x1fe0, "eRom");
        var sRam = new MemoryBlock(0x2000, "sRam");

        bus.map(0x0000, 0x1FFF, new MirroredMemory(iRam)); // 2KB internal RAM (Mirrored $0000-$07FF)
        bus.map(0x2000, 0x3FFF, new MirroredMemory(ppuRegs)); // NES PPU registers (Mirrored every 8 bytes)
        bus.map(0x4000, 0x401F, apuRegs); // NES APU and I/O registers
        bus.map(0x4020, 0x5FFF, eRom);    // Expansion rom
        bus.map(0x6000, 0x7FFF, sRam);    // S-RAM
        bus.map(0x8000, 0xFFFF, cart);    // Cartridge space: PRG ROM, PRG RAM, and mapper registers
    }
}
