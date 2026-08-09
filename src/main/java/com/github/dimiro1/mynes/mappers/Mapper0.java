package com.github.dimiro1.mynes.mappers;

/**
 * Mapper 0, NROM: no bank switching at all.
 * <p>
 * The PRG ROM is either one 16KB bank mirrored into both halves of $8000-$FFFF or two banks
 * filling it, and the pattern tables are whatever the cart carries. Mirroring is hard wired by
 * a solder pad, so it is fixed for the life of the cartridge.
 */
public class Mapper0 implements Mapper {
    /**
     * How much character RAM a cart that shipped without any CHR ROM gets. Every such board
     * carries a single 8KB chip, which is exactly the window the PPU can address.
     */
    private static final int CHR_RAM_SIZE = 0x2000;

    private final byte[] prgROM;

    /**
     * The pattern table storage, either the cart's CHR ROM or the CHR RAM allocated in its place.
     * A cart with no CHR ROM banks in its header is a CHR RAM cart -- several of blargg's PPU
     * test ROMs are -- and the PPU has to be able to both read and write it.
     */
    private final byte[] chr;

    private final boolean chrIsRAM;
    private final Mirroring mirroring;

    public Mapper0(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.mirroring = mirroring;
    }

    @Override
    public int prgRead(final int address) {
        if (prgROM.length == 0x4000 && address >= 0x4000) {
            return Byte.toUnsignedInt(prgROM[address % 0x4000]);
        }

        return Byte.toUnsignedInt(prgROM[address]);
    }

    @Override
    public void prgWrite(final int address, final int data) { /* Not used on mapper 0 */ }

    @Override
    public int charRead(final int address) {
        return Byte.toUnsignedInt(chr[address & 0x1FFF]);
    }

    @Override
    public void charWrite(final int address, final int data) {
        // A cart with real CHR ROM simply ignores the write; only CHR RAM carts take it.
        if (chrIsRAM) {
            chr[address & 0x1FFF] = (byte) data;
        }
    }

    @Override
    public Mirroring mirroring() {
        return mirroring;
    }
}
