package com.github.dimiro1.mynes.memory;

/**
 * A Smart Memory capable of mirror capabilities.
 * It mirrors the given memory every length bytes.
 */
public class MirroredMemory implements Memory {
    private final Memory memory;

    public MirroredMemory(final Memory memory) {
        this.memory = memory;
    }

    /**
     * Reads from the mirrored address.
     *
     * @param address the memory address to read.
     */
    @Override
    public int read(final int address) {
        return this.memory.read(address % this.getLength());
    }

    /**
     * Writes into the mirrored address.
     *
     * @param address The memory address to write the value.
     * @param data    The data to write.
     */
    @Override
    public void write(final int address, final int data) {
        this.memory.write(address % this.getLength(), data);
    }

    /**
     * Returns the capacity of the underlining memory.
     */
    @Override
    public int getLength() {
        return this.memory.getLength();
    }
}
