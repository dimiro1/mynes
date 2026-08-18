package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

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

    /**
     * The window at $6000-$7FFF, filled in whether or not the board really had a RAM chip on it.
     * Plain NROM mostly did not, but blargg's test ROMs report their results through $6000 and
     * several of them are NROM, so the console would have nothing to read back otherwise.
     */
    private static final int PRG_RAM_SIZE = 0x2000;

    private final byte[] prgROM;
    private final byte[] prgRAM = new byte[PRG_RAM_SIZE];

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
        var offset = address & 0x7FFF;

        if (prgROM.length == 0x4000 && offset >= 0x4000) {
            return Byte.toUnsignedInt(prgROM[offset % 0x4000]);
        }

        return Byte.toUnsignedInt(prgROM[offset]);
    }

    @Override
    public void prgWrite(final int address, final int data) { /* Not used on mapper 0 */ }

    @Override
    public int prgRAMRead(final int address) {
        return Byte.toUnsignedInt(prgRAM[address & 0x1FFF]);
    }

    @Override
    public void prgRAMWrite(final int address, final int data) {
        prgRAM[address & 0x1FFF] = (byte) data;
    }

    @Override
    public byte[] prgRAM() {
        return prgRAM;
    }

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

    /**
     * No registers to save: the board has no bank switching, and the solder pad that sets its
     * mirroring is not something a game can change. Only the two memories, and the pattern tables
     * only when they are RAM -- the same branch on both sides, because the header hash matched, so
     * the cartridge cannot have been ROM when this was written and RAM now.
     */
    @Override
    public void serialize(final StateIO io) {
        io.bytes(prgRAM);

        if (chrIsRAM) {
            io.bytes(chr);
        }
    }
}
