package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;

/**
 * NES Memory Management Unit (MMU).
 * <p>
 * Memory Map:
 * $0000-$07FF: 2KB internal RAM
 * $0800-$1FFF: Mirrors of $0000-$07FF
 * $2000-$2007: PPU registers
 * $2008-$3FFF: Mirrors of $2000-$2007
 * $4000-$4017: APU and I/O registers
 * $4018-$401F: APU and I/O functionality (usually disabled)
 * $4020-$5FFF: Expansion ROM (rarely used)
 * $6000-$7FFF: SRAM (battery-backed save RAM)
 * $8000-$FFFF: PRG ROM (mapper controlled)
 */
public class MMU {
    private final PPU ppu;
    private final Mapper mapper;
    private final Controller controller1;
    private final Controller controller2;

    // Internal RAM: 2KB, mirrored 4 times in $0000-$1FFF
    private final int[] internalRAM = new int[0x0800];

    // APU registers ($4000-$4017)
    private final int[] apuRegisters = new int[0x0018];

    // Expansion ROM area ($4020-$5FFF) - rarely used
    private final int[] expansionROM = new int[0x1FE0];

    // Save RAM ($6000-$7FFF) - battery-backed SRAM
    private final int[] saveRAM = new int[0x2000];

    // DMA state
    private boolean dmaInProgress = false;
    private int dmaPage = 0;
    private int dmaAddress = 0;
    private int dmaData = 0;
    private boolean dmaSync = false;

    public MMU(final PPU ppu, final Mapper mapper, final Controller controller1, final Controller controller2) {
        this.ppu = ppu;
        this.mapper = mapper;
        this.controller1 = controller1;
        this.controller2 = controller2;
    }

    /**
     * Reads a byte from the specified address.
     */
    public int read(final int address) {
        int addr = address & 0xFFFF;

        // Internal RAM and mirrors ($0000-$1FFF)
        if (addr < 0x2000) {
            return internalRAM[addr & 0x07FF];
        }

        // PPU Registers and mirrors ($2000-$3FFF)
        if (addr < 0x4000) {
            return ppu.read(addr & 0x0007);
        }

        // APU and I/O Registers ($4000-$4017)
        if (addr < 0x4018) {
            return readIORegister(addr);
        }

        // APU and I/O Test Mode ($4018-$401F) - usually disabled
        if (addr < 0x4020) {
            return 0; // Open bus
        }

        // Expansion ROM ($4020-$5FFF)
        if (addr < 0x6000) {
            return expansionROM[addr - 0x4020];
        }

        // Save RAM ($6000-$7FFF)
        if (addr < 0x8000) {
            return saveRAM[addr - 0x6000];
        }

        // PRG ROM ($8000-$FFFF) - mapper controlled
        return mapper.prgRead(addr - 0x8000);
    }

    /**
     * Writes a byte to the specified address.
     */
    public void write(final int address, final int data) {
        int addr = address & 0xFFFF;
        int value = data & 0xFF;

        // Internal RAM and mirrors ($0000-$1FFF)
        if (addr < 0x2000) {
            internalRAM[addr & 0x07FF] = value;
            return;
        }

        // PPU Registers and mirrors ($2000-$3FFF)
        if (addr < 0x4000) {
            ppu.write(addr & 0x0007, value);
            return;
        }

        // APU and I/O Registers ($4000-$4017)
        if (addr < 0x4018) {
            writeIORegister(addr, value);
            return;
        }

        // APU and I/O Test Mode ($4018-$401F) - usually disabled
        if (addr < 0x4020) {
            return; // Ignore writes
        }

        // Expansion ROM ($4020-$5FFF)
        if (addr < 0x6000) {
            expansionROM[addr - 0x4020] = value;
            return;
        }

        // Save RAM ($6000-$7FFF)
        if (addr < 0x8000) {
            saveRAM[addr - 0x6000] = value;
            return;
        }

        // PRG ROM ($8000-$FFFF) - mapper controlled
        mapper.prgWrite(addr - 0x8000, value);
    }

    /**
     * Reads from I/O registers ($4000-$4017).
     */
    private int readIORegister(int address) {
        return switch (address) {
            case 0x4016 -> controller1 != null ? controller1.read() : 0; // Controller 1
            case 0x4017 -> controller2 != null ? controller2.read() : 0; // Controller 2
            default -> apuRegisters[address - 0x4000]; // APU registers
        };
    }

    /**
     * Writes to I/O registers ($4000-$4017).
     */
    private void writeIORegister(int address, int data) {
        switch (address) {
            case 0x4014 -> {
                // OAM DMA - triggers sprite DMA transfer
                dmaPage = data & 0xFF;
                dmaAddress = 0;
                dmaSync = true;
                dmaInProgress = true;
            }
            case 0x4016 -> {
                // Controller strobe
                if (controller1 != null) {
                    controller1.setStrobe(data & 1);
                }
                if (controller2 != null) {
                    controller2.setStrobe(data & 1);
                }
            }
            default -> apuRegisters[address - 0x4000] = data & 0xFF; // APU registers
        }
    }

    /**
     * Performs one cycle of DMA transfer if active.
     * DMA takes 513 or 514 CPU cycles (depending on alignment).
     *
     * @return true if DMA is in progress
     */
    public boolean tickDMA() {
        if (!dmaInProgress) {
            return false;
        }

        // DMA requires one dummy cycle for synchronization on odd CPU cycles
        if (dmaSync) {
            dmaSync = false;
            return true;
        }

        // Even cycles: read from CPU memory
        // Odd cycles: write to PPU OAM ($2004)
        if (dmaAddress % 2 == 0) {
            int sourceAddr = (dmaPage << 8) | dmaAddress;
            dmaData = read(sourceAddr);
        } else {
            ppu.write(0x04, dmaData); // Write to OAMDATA
            dmaAddress++;
        }

        // DMA completes after 256 bytes transferred (512 cycles + potential sync cycle)
        if (dmaAddress >= 0x100) {
            dmaInProgress = false;
            dmaAddress = 0;
        }

        return true;
    }

    /**
     * Checks if DMA is currently in progress.
     */
    public boolean isDMAInProgress() {
        return dmaInProgress;
    }

    /**
     * Gets the internal RAM array (for testing/debugging).
     */
    public int[] getInternalRAM() {
        return internalRAM;
    }

    /**
     * Gets the save RAM array (for save state/persistence).
     */
    public int[] getSaveRAM() {
        return saveRAM;
    }
}