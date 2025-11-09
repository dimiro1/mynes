package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;

/**
 * The system bus that connects all NES components.
 * <p>
 * The Bus acts as the central communication hub between the CPU, PPU, and memory.
 * It handles:
 * <ul>
 *   <li>Memory access routing through the MMU</li>
 *   <li>Interrupt signaling from PPU/Mapper to CPU</li>
 *   <li>Component lifecycle coordination</li>
 * </ul>
 * <p>
 */
public class BUS {
    private CPU cpu;
    private PPU ppu;
    private MMU mmu;
    private final Mapper mapper;
    private final Controller controller1;
    private final Controller controller2;

    /**
     * Creates a new Bus with the specified components.
     *
     * @param mapper      the cartridge mapper
     * @param controller1 the first controller
     * @param controller2 the second controller
     */
    public BUS(final Mapper mapper, final Controller controller1, final Controller controller2) {
        this.mapper = mapper;
        this.controller1 = controller1;
        this.controller2 = controller2;
    }

    /**
     * Initializes all components connected to the bus.
     * This must be called after construction to wire up all dependencies.
     */
    public void initialize() {
        this.ppu = new PPU(this, mapper);
        this.mmu = new MMU(ppu, mapper, controller1, controller2);
        this.cpu = new CPU(this);
    }

    /**
     * Reads a byte from memory at the specified address.
     *
     * @param address the memory address to read from
     * @return the byte value at that address
     */
    public int read(final int address) {
        return mmu.read(address);
    }

    /**
     * Writes a byte to memory at the specified address.
     *
     * @param address the memory address to write to
     * @param data    the byte value to write
     */
    public void write(final int address, final int data) {
        mmu.write(address, data);
    }

    /**
     * Triggers a Non-Maskable Interrupt (NMI) on the CPU.
     * Typically called by the PPU at the start of VBlank.
     */
    public void triggerNMI() {
        cpu.requestNMI();
    }

    /**
     * Triggers an Interrupt Request (IRQ) on the CPU.
     * Can be called by mappers or other hardware.
     */
    public void triggerIRQ() {
        cpu.requestIRQ();
    }

    /**
     * Checks if DMA transfer is currently in progress.
     *
     * @return true if DMA is active
     */
    public boolean isDMAInProgress() {
        return mmu.isDMAInProgress();
    }

    /**
     * Performs one cycle of DMA transfer if active.
     *
     * @return true if DMA is in progress
     */
    public boolean tickDMA() {
        return mmu.tickDMA();
    }

    /**
     * Gets the CPU component.
     *
     * @return the CPU
     */
    public CPU getCPU() {
        return cpu;
    }

    /**
     * Gets the PPU component.
     *
     * @return the PPU
     */
    public PPU getPPU() {
        return ppu;
    }

    /**
     * Gets the MMU component.
     *
     * @return the MMU
     */
    public MMU getMMU() {
        return mmu;
    }

    /**
     * Gets the mapper.
     *
     * @return the mapper
     */
    public Mapper getMapper() {
        return mapper;
    }

    /**
     * Gets the first controller.
     *
     * @return controller 1
     */
    public Controller getController1() {
        return controller1;
    }

    /**
     * Gets the second controller.
     *
     * @return controller 2
     */
    public Controller getController2() {
        return controller2;
    }
}
