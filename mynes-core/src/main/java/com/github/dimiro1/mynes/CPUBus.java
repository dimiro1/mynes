package com.github.dimiro1.mynes;

/**
 * The slice of the system bus that the CPU depends on.
 * <p>
 * {@link BUS} is the real implementation; the seam exists so the CPU can be driven by a
 * test double (see the Tom Harte single-step harness) without a PPU, APU or cartridge.
 */
public interface CPUBus {

    /**
     * What a CPU cycle is for, once a transfer has asked for the bus.
     */
    enum DMACycle {
        /**
         * Nobody else wants the bus. The CPU has its cycle to itself.
         */
        NONE,

        /**
         * A transfer drives both the address and the data pins. The CPU is off the bus entirely
         * and does nothing at all.
         */
        TRANSFER,

        /**
         * The CPU is held off the bus but still driving the address it had reached, so the read it
         * was in the middle of happens again -- side effects and all. This is the halt cycle, the
         * DMC's dummy cycle, and any cycle a transfer cannot use because the get/put phase is
         * wrong.
         * <p>
         * A cycle that turns out to be a <em>write</em> is the exception: the 6502 ignores RDY while
         * it is writing, so the write goes through and the halt waits another cycle.
         */
        HALT
    }

    /**
     * Which half of the CPU's clock a cycle is.
     * <p>
     * The 2A03 divides its clock in two and gives the transfer units alternate halves: they read on
     * a get and write on a put. Which parity is which is a convention rather than a measurement --
     * nothing observable says whether cycle zero was a get or a put -- but it is <em>this</em>
     * convention, because blargg's {@code 4-jitter} and the OAM transfer's 513 versus 514 cycles
     * are both calibrated against it.
     * <p>
     * Here rather than in either of the two chips that ask, because a convention with two homes is
     * a convention that can come to disagree with itself, and this one is load bearing in both. The
     * MMU asks it of the cycle {@link #beginDMACycle} was handed; the APU asks it of its own
     * counter, which runs one ahead -- the chip is clocked before the processor is.
     *
     * @param cpuCycle a CPU cycle counter.
     * @return true if that cycle is a get.
     */
    static boolean isGetCycle(final long cpuCycle) {
        return (cpuCycle & 1) != 0;
    }

    /**
     * Asks what this cycle is for before the CPU spends it.
     *
     * @param cpuCycle the cycle counter, whose parity is the get/put phase.
     * @return what the CPU should do with the cycle.
     */
    default DMACycle beginDMACycle(final long cpuCycle) {
        return DMACycle.NONE;
    }

    /**
     * Reports what a {@link DMACycle#HALT} cycle turned out to be, which is the only way the halt
     * can know whether it has landed.
     *
     * @param cpuWrote true if the CPU spent the cycle writing, which delays the halt by one.
     */
    default void endHaltCycle(final boolean cpuWrote) {
    }

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
