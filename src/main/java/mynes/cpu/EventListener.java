package mynes.cpu;

/**
 * CPUEventListener is an interface for objects that wants to listen to internal CPU events.
 */
public interface EventListener {
    /**
     * Triggered after instruction decoding and just before the execution of the instruction.
     *
     * @param pc          The program counter.
     * @param a           Register a.
     * @param x           Register x.
     * @param y           Register y.
     * @param p           Register p.
     * @param sp          Register sp.
     * @param instruction The opcode of the instruction and its operands.
     * @param cycles      The cycle in which this instruction is being executed.
     */
    void onStep(
            final int pc,
            final int a,
            final int x,
            final int y,
            final int p,
            final int sp,
            final int[] instruction,
            final long cycles
    );
}
