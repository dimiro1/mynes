package mynes;

import mynes.cart.Cart;
import mynes.cpu.CPU;
import mynes.cpu.Memory;
import mynes.ppu.PPU;

public class NES {
    private final CPU cpu;
    private final PPU ppu;
    private final Memory memory;

    public NES(final Cart cart) {
        ppu = new PPU(cart.mapper());
        memory = new Memory(ppu, cart.mapper());
        cpu = new CPU(memory);
    }

    public CPU getCPU() {
        return cpu;
    }

    public Memory getMemory() {
        return memory;
    }

    public void tick() {
        cpu.tick();
        ppu.tick();
        ppu.tick();
    }

    public void step() {
        this.cpu.step();
    }
}
