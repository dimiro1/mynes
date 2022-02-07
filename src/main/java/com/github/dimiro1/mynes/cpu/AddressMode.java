package com.github.dimiro1.mynes.cpu;

/**
 * The instruction address mode.
 */
public enum AddressMode {
    ABS, // Absolute
    ABX, // Absolute X
    ABY, // Absolute Y
    ACC, // Accumulator
    IMD, // Immediate
    IMP, // Implied
    IDX, // Indexed Indirect
    IND, // Indirect
    INX, // indirect Indexed
    REL, // Relative
    ZPG, // Zero Page
    ZPX, // Zero Page X
    ZPY; // Zero Page y
}
