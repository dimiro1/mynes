package com.github.dimiro1.mynes;

/**
 * The slice of the system bus that the CPU depends on.
 * <p>
 * {@link BUS} is the real implementation; the seam exists so the CPU can be driven by a
 * test double (see the Tom Harte single-step harness) without a PPU, APU or cartridge.
 */
public interface CPUBus {
    /**
     * Reads a byte from memory at the specified address.
     *
     * @param address the memory address to read from
     * @return the byte value at that address
     */
    int read(final int address);

    /**
     * Writes a byte to memory at the specified address.
     *
     * @param address the memory address to write to
     * @param data    the byte value to write
     */
    void write(final int address, final int data);

    /**
     * Performs one cycle of DMA transfer if active.
     *
     * @param cpuCycle the current CPU cycle counter; OAM DMA alignment depends on its parity
     * @return true if DMA is in progress and the CPU must stall this cycle
     */
    boolean tickDMA(final long cpuCycle);

    /**
     * Reads a byte without any of the side effects a real read would have.
     * <p>
     * Used by debugging facilities (disassembly, tracing) that must not perturb emulation.
     *
     * @param address the memory address to read from
     * @return the byte value at that address
     */
    default int peek(final int address) {
        return read(address);
    }
}
