package com.github.dimiro1.mynes;

/**
 * Told about every byte the CPU reads, which is what a read watchpoint is built out of.
 * <p>
 * The mirror of {@link MemoryWriteListener}, with two differences worth knowing before setting one.
 * <p>
 * <b>It is told afterwards rather than before.</b> A write is handed its byte because the address
 * still holds the old one; a read has no byte to hand over until the read has happened, so this is
 * called with the value the CPU actually got -- open bus and all, and after $2002's flag has been
 * cleared and the controller's shift register clocked. There is no arrangement that both says what
 * came back and leaves the machine untouched, and saying what came back is the whole point.
 * <p>
 * <b>Every instruction fetch is a read.</b> The CPU takes its opcode and its operands off the same
 * bus as everything else, so a watch on an address inside a routine fires on every pass through it
 * and reports the fetch. That is what the hardware does rather than an accident of this seam -- and
 * it is why {@code break} exists: "stop when the machine reaches here" and "stop when the machine
 * reads here" are different questions, and only one of them is answered by watching the bus.
 * <p>
 * The same hole as the write side, for the same reason: this sees what {@link MMU#read} carries,
 * which is the processor's own reads. An OAM transfer reads through {@link MMU#beginDMACycle} and
 * a DMC sample fetch through the same path, neither of which is the CPU driving the address.
 *
 * @see MemoryWriteListener
 */
@FunctionalInterface
public interface MemoryReadListener {
    /**
     * Called once the byte is in hand.
     *
     * @param address where it came from, $0000-$FFFF.
     * @param value   the byte the CPU got.
     */
    void onRead(int address, int value);
}
