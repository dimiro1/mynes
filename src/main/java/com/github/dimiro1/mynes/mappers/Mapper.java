package com.github.dimiro1.mynes.mappers;

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

    /**
     * How this cartridge wires the console's nametable RAM.
     * <p>
     * Asked afresh on every nametable access rather than cached, because a mapper with mirroring
     * registers can change the answer between one access and the next.
     */
    Mirroring mirroring();
}
