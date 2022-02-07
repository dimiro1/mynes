package com.github.dimiro1.mynes.memory;

/**
 * A simple memory block.
 */
public class MemoryBlock implements Memory {
    private final String name;
    private final int[] data;

    public MemoryBlock(int length, String name) {
        this.data = new int[length];
        this.name = name;
    }

    /**
     * Read a single byte from memory.
     *
     * @param address the memory address to read.
     */
    @Override
    public int read(final int address) {
        if (address < 0 || address >= this.getLength()) {
            throw new InvalidAddress(address, this.name);
        }
        return this.data[address] & 0xFF;
    }

    /**
     * Writes a single byte into the memory address.
     *
     * @param address The memory address to write the value.
     * @param data    The data to write.
     */
    @Override
    public void write(final int address, final int data) {
        if (address < 0 || address >= this.getLength()) {
            throw new InvalidAddress(address, this.name);
        }
        this.data[address] = data & 0xFF;
    }

    /**
     * Returns the capacity of the memory.
     */
    @Override
    public int getLength() {
        return this.data.length;
    }
}
