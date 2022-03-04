package com.github.dimiro1.mynes.cart.mappers;

public interface Mapper {

    /**
     * Reads a single byte from the PRG ROM.
     */
    int prgRead(int address);

    /**
     * Writes a single byte into the given address on PRG ROM.
     */
    void prgWrite(int address, int data);

    /**
     * Reads a single byte from the CHAR ROM/RAM.
     */
    int charRead(int address);

    /**
     * Writes a single byte into the given address on CHAR ROM/RAM.
     */
    void charWrite(int address, int data);
}
