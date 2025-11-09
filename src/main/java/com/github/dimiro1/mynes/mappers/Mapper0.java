package com.github.dimiro1.mynes.mappers;

public class Mapper0 implements Mapper {
    private final byte[] prgROM;
    private final byte[] chrROM;

    public Mapper0(final byte[] prgROM, final byte[] chrROM) {
        this.prgROM = prgROM;
        this.chrROM = chrROM;
    }

    @Override
    public int prgRead(final int address) {
        if (prgROM.length == 0x4000 && address >= 0x4000) {
            return prgROM[address % 0x4000];
        }

        return prgROM[address];
    }

    @Override
    public void prgWrite(final int address, final int data) { /* Not used on mapper 0 */ }

    @Override
    public int charRead(final int address) {
        return chrROM[address];
    }

    @Override
    public void charWrite(final int address, final int data) { /* Not used on mapper 0 */}
}
