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
public class BUS implements CPUBus, PPUBus {
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
    @Override
    public int read(final int address) {
        return mmu.read(address);
    }

    /**
     * Writes a byte to memory at the specified address.
     *
     * @param address the memory address to write to
     * @param data    the byte value to write
     */
    @Override
    public void write(final int address, final int data) {
        mmu.write(address, data);
    }

    /**
     * Reads a byte without the side effects a real read would have.
     *
     * @param address the memory address to read from
     * @return the byte value at that address
     */
    @Override
    public int peek(final int address) {
        return mmu.peek(address);
    }

    /**
     * Drives the /NMI line the PPU shares with the CPU.
     * <p>
     * Null guarded because {@link #initialize()} builds the PPU first, and the PPU settles the
     * line as part of its own construction; there is no CPU to tell yet, and a CPU that has just
     * been built sees the line released anyway.
     *
     * @param level true while the line is asserted.
     */
    @Override
    public void setNMILine(final boolean level) {
        if (cpu != null) {
            cpu.setNMILine(level);
        }
    }

    /**
     * Asserts the shared Interrupt Request (IRQ) line.
     * Can be called by mappers or other hardware. The line stays low until
     * {@link #releaseIRQ()} is called, so the CPU keeps seeing the request even while the
     * interrupt disable flag is masking it.
     */
    public void triggerIRQ() {
        cpu.setIRQLine(true);
    }

    /**
     * Releases the shared Interrupt Request (IRQ) line.
     */
    public void releaseIRQ() {
        cpu.setIRQLine(false);
    }

    /**
     * Triggers a Reset (RST) interrupt on the CPU.
     * Used during system initialization or reset button press.
     * <p>
     * The reset line goes to the PPU as well, which re-arms the window it ignores $2000, $2001,
     * $2005 and $2006 in. The two come out of reset together, which is the assumption behind the
     * "around 29658 CPU cycles" that window is normally quoted as.
     */
    public void triggerRST() {
        cpu.requestRST();
        ppu.reset();
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
     * @param cpuCycle the current CPU cycle counter; OAM DMA alignment depends on its parity
     * @return true if DMA is in progress
     */
    @Override
    public boolean tickDMA(final long cpuCycle) {
        return mmu.tickDMA(cpuCycle);
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
