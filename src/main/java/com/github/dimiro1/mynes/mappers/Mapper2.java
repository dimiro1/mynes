package com.github.dimiro1.mynes.mappers;

/**
 * Mapper 2, UxROM: one switchable PRG bank and one that never moves.
 * <p>
 * $8000-$BFFF is whichever 16KB bank the latch was last written with, and $C000-$FFFF is always
 * the last bank in the ROM. That fixed half is the whole trick: the reset and interrupt vectors
 * live at the top of it, so they are reachable no matter which bank is switched in, and the code
 * that does the switching can sit up there with them. Mega Man, Castlevania, Contra and Metal
 * Gear are all UxROM.
 * <p>
 * There is no CHR ROM on these boards. The pattern tables are 8KB of RAM the game fills itself,
 * and mirroring is soldered.
 * <p>
 * Bus conflicts are not emulated. On the plain UNROM board the ROM is still driving the data bus
 * when the write lands, so the value written has to match the byte already at that address --
 * games do that by keeping a table of bank numbers and storing each one over itself. Emulating
 * the conflict would only turn a game that gets this wrong into a game that crashes.
 *
 * @see <a href="https://www.nesdev.org/wiki/UxROM">NESdev: UxROM</a>
 */
public class Mapper2 implements Mapper {
    private static final int PRG_BANK_SIZE = 0x4000;
    private static final int CHR_RAM_SIZE = 0x2000;

    private final byte[] prgROM;

    /**
     * The pattern table storage: CHR RAM on a real UxROM board, but a cart that carries CHR ROM
     * is handled the same way {@link Mapper0} handles it rather than being rejected.
     */
    private final byte[] chr;

    private final boolean chrIsRAM;
    private final Mirroring mirroring;

    /**
     * How many 16KB banks there are, less one, which doubles as the mask a written bank number
     * is folded through. A real board only decodes as many bits as it has banks.
     */
    private final int bankMask;

    /**
     * Where the last bank starts, which is what $C000-$FFFF always reads.
     */
    private final int lastBankBase;

    private int prgBank;

    public Mapper2(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.mirroring = mirroring;
        this.bankMask = Math.max(1, prgROM.length / PRG_BANK_SIZE) - 1;
        this.lastBankBase = Math.max(0, prgROM.length - PRG_BANK_SIZE);
    }

    @Override
    public int prgRead(final int address) {
        var offset = address & 0x7FFF;

        if (offset < PRG_BANK_SIZE) {
            return Byte.toUnsignedInt(prgROM[prgBank * PRG_BANK_SIZE + offset]);
        }

        return Byte.toUnsignedInt(prgROM[lastBankBase + (offset & 0x3FFF)]);
    }

    @Override
    public void prgWrite(final int address, final int data) {
        prgBank = data & bankMask;
    }

    @Override
    public int charRead(final int address) {
        return Byte.toUnsignedInt(chr[address & 0x1FFF]);
    }

    @Override
    public void charWrite(final int address, final int data) {
        if (chrIsRAM) {
            chr[address & 0x1FFF] = (byte) data;
        }
    }

    @Override
    public Mirroring mirroring() {
        return mirroring;
    }
}
