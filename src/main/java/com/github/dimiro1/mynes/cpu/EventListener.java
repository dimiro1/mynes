package com.github.dimiro1.mynes.cpu;

/**
 * CPUEventListener is an interface for objects that wants to listen to internal CPU events.
 */
public interface EventListener {
    /**
     * Triggered after a change on a CPU register.
     *
     * @param register      the register that changed.
     * @param previousValue the previous value of the register.
     * @param newValue      the new value of the register.
     */
    void onRegisterChange(final Register register, final int previousValue, final int newValue);

    /**
     * Triggered after instruction decoding and just before the execution of the instruction.
     *
     * @param instruction The opcode of the instruction and its operands.
     * @param address     The address used by the instruction.
     * @param data        The resolved data used by the instruction.
     * @param mode        The address mode used to resolve the address.
     * @param cycles      The cycle in which this instruction is being executed.
     */
    void onStep(final int[] instruction, final int address, final int data, final AddressMode mode, final int cycles);

    /**
     * Triggered after a CPU reset.
     *
     * @param cycles The cycle in which the reset was performed.
     */
    void onReset(final int cycles);
}
