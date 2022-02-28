package mynes;

import mynes.cart.Cart;
import mynes.cpu.CPU;
import mynes.memory.MappedMemory;
import mynes.memory.Memory;
import mynes.memory.MemoryBlock;
import mynes.memory.MirroredMemory;

public class NES {
    private final CPU cpu;
    private final Memory memory;

    public NES(final Cart cart) {
        var mBus = new MappedMemory();
        var iRam = new MemoryBlock(0x0800, "iRam");
        var ppuR = new MemoryBlock(0x0008, "ppuRegs");
        var apuR = new MemoryBlock(0x0018, "apuRegs");
        var eRom = new MemoryBlock(0x1fe0, "eRom");
        var sRam = new MemoryBlock(0x2000, "sRam");

        mBus.map(0x0000, 0x1FFF, new MirroredMemory(iRam)); // 2KB internal RAM (Mirrored $0000-$07FF)
        mBus.map(0x2000, 0x3FFF, new MirroredMemory(ppuR)); // NES PPU registers (Mirrored every 8 bytes)
        mBus.map(0x4000, 0x401F, apuR); // NES APU and I/O registers
        mBus.map(0x4020, 0x5FFF, eRom); // Expansion rom
        mBus.map(0x6000, 0x7FFF, sRam); // S-RAM
        mBus.map(0x8000, 0xFFFF, cart); // Cartridge space: PRG ROM, PRG RAM, and mapper registers

        this.cpu = new CPU(mBus);
        this.memory = mBus;
    }

    public CPU getCPU() {
        return cpu;
    }

    public Memory getMemory() {
        return memory;
    }

    public void step() {
        this.cpu.step();
    }
}
