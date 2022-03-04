package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.cart.Cart;
import com.github.dimiro1.mynes.cpu.CPU;
import com.github.dimiro1.mynes.cpu.Memory;
import com.github.dimiro1.mynes.ppu.PPU;

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
