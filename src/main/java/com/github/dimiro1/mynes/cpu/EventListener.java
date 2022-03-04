package com.github.dimiro1.mynes.cpu;

/**
 * CPUEventListener is an interface for objects that wants to listen to internal CPU events.
 */
public interface EventListener {
    /**
     * Triggered after instruction decoding and just before the execution of the instruction.
     *
     * @param pc           The program counter.
     * @param a            Register a.
     * @param x            Register x.
     * @param y            Register y.
     * @param p            Register p.
     * @param sp           Register sp.
     * @param opcode       The opcode of the instruction and its operands.
     * @param operand1     The first operand of the instruction.
     * @param operand2     The second operand of the instruction.
     * @param opcodeLength The length of the instruction.
     * @param cycles       The cycle in which this instruction is being executed.
     */
    void onStep(
            final int pc,
            final int a,
            final int x,
            final int y,
            final int p,
            final int sp,
            final int opcode,
            final int operand1,
            final int operand2,
            final int opcodeLength,
            final long cycles
    );
}
