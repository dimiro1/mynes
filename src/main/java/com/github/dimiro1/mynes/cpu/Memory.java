package com.github.dimiro1.mynes.cpu;

import com.github.dimiro1.mynes.cart.mappers.Mapper;
import com.github.dimiro1.mynes.ppu.PPU;

public class Memory {
    private final PPU ppu;
    private final Mapper mapper;
    private final int[] internalRAM = new int[0x0800];
    private final int[] apuRegisters = new int[0x0018];
    private final int[] expansionROM = new int[0x1fe0];
    private final int[] saveRAM = new int[0x2000];

    public Memory(final PPU ppu, final Mapper mapper) {
        this.ppu = ppu;
        this.mapper = mapper;
    }

    public int read(final int address) {
        return switch (address & 0xF000) {
            case 0x0000, 0x1000 -> internalRAM[address % 0x2000];
            case 0x2000, 0x3000 -> ppu.read(address % 8);
            case 0x4000 -> (address < 0x4020)
                    ? apuRegisters[address - 0x4000]
                    : expansionROM[address - 0x4020];
            case 0x5000 -> saveRAM[address - 0x4020];
            case 0x6000, 0x7000 -> saveRAM[address - 0x6000];
            default -> mapper.prgRead(address - 0x8000);
        };
    }

    public void write(final int address, final int data) {
        switch (address & 0xF000) {
            case 0x0000, 0x1000 -> internalRAM[address % 0x2000] = data;
            case 0x2000, 0x3000 -> ppu.write(address % 8, data);
            case 0x4000 -> {
                if ((address < 0x4020)) {
                    apuRegisters[address - 0x4000] = data;
                } else {
                    expansionROM[address - 0x4020] = data;
                }
            }
            case 0x5000 -> saveRAM[address - 0x4020] = data;
            case 0x6000, 0x7000 -> saveRAM[address - 0x6000] = data;
            default -> mapper.prgWrite(address - 0x8000, data);
        }
    }
}
