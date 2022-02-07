package com.github.dimiro1.mynes.memory;

public interface AddressSpace {
    /**
     * Reads a single byte from the memory address.
     *
     * @param address the memory address to read.
     * @return the read byte.
     */
    int read(int address);

    /**
     * Writes a single byte into the given address.
     *
     * @param address The memory address to write the value.
     * @param data    The data to write.
     */
    void write(int address, int data);
}
