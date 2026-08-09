package com.github.dimiro1.mynes.mappers;

/**
 * Mapper 3, CNROM: NROM with a switchable character bank.
 * <p>
 * The PRG ROM is fixed exactly as on {@link Mapper0}. The only extra hardware is a two bit latch
 * that a write anywhere in $8000-$FFFF loads, choosing which 8KB slice of CHR ROM the PPU sees.
 * <p>
 * Supported because {@code test_ppu_read_buffer.nes} -- the most thorough test there is of the
 * $2007 read buffer -- is a CNROM cart, and nothing else about it needs a mapper.
 *
 * @see <a href="https://www.nesdev.org/wiki/CNROM">NESdev: CNROM</a>
 */
public class Mapper3 implements Mapper {
    private static final int CHR_BANK_SIZE = 0x2000;

    private final byte[] prgROM;
    private final byte[] chrROM;
    private final Mirroring mirroring;

    /**
     * How many banks there are, less one, which doubles as the mask a written bank number is
     * folded through. A real board only decodes as many bits as it has banks.
     */
    private final int bankMask;

    private int chrBank;

    public Mapper3(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrROM = chrROM;
        this.mirroring = mirroring;
        this.bankMask = Math.max(1, chrROM.length / CHR_BANK_SIZE) - 1;
    }

    @Override
    public int prgRead(final int address) {
        if (prgROM.length == 0x4000 && address >= 0x4000) {
            return Byte.toUnsignedInt(prgROM[address % 0x4000]);
        }

        return Byte.toUnsignedInt(prgROM[address]);
    }

    @Override
    public void prgWrite(final int address, final int data) {
        chrBank = data & bankMask;
    }

    @Override
    public int charRead(final int address) {
        return Byte.toUnsignedInt(chrROM[chrBank * CHR_BANK_SIZE + (address & 0x1FFF)]);
    }

    @Override
    public void charWrite(final int address, final int data) { /* CHR ROM, so writes go nowhere */ }

    @Override
    public Mirroring mirroring() {
        return mirroring;
    }
}
