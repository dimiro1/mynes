package mynes.memory;

import mynes.utils.ByteUtils;

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
        return ByteUtils.ensureByte(this.data[address]);
    }

    /**
     * Writes a single byte into the memory address.
     *
     * @param address The memory address to write the value.
     * @param data    The data to write.
     */
    @Override
    public void write(final int address, final int data) {
        this.data[ByteUtils.ensureWord(address)] = ByteUtils.ensureByte(data);
    }

    /**
     * Returns the capacity of the memory.
     */
    @Override
    public int getLength() {
        return this.data.length;
    }

    @Override
    public String getName() {
        return this.name;
    }
}
