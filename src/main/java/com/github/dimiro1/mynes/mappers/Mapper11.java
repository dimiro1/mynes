package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Mapper 11, Color Dreams: one latch holding both bank numbers, and no fixed window anywhere.
 * <p>
 * A write to $8000-$FFFF puts its low two bits into a 32KB PRG selector and its high four into an
 * 8KB CHR selector. That is the entire board. Colour Dreams built it to undercut the licensed
 * alternatives and it shows -- there is no shift register, no interrupt, and no way to keep the
 * vectors still, so the switching stub has to be repeated at the same offset in every bank.
 * <p>
 * Nothing here depends on the cartridges having been unlicensed: the lockout chip is not modelled
 * anywhere in this emulator, so a board built to defeat it looks like any other. It is worth
 * having because homebrew still reaches for this mapper when NROM runs out of room and an MMC1
 * looks like work.
 *
 * @see <a href="https://www.nesdev.org/wiki/Color_Dreams">NESdev: Color Dreams</a>
 */
public class Mapper11 implements Mapper {
    private static final int PRG_BANK_SIZE = 0x8000;
    private static final int CHR_BANK_SIZE = 0x2000;
    private static final int CHR_RAM_SIZE = 0x2000;

    /**
     * What the board decodes out of a written byte: two bits of PRG at the bottom, four of CHR at
     * the top. {@link Mapper66} shares the shape and swaps the two ends.
     */
    private static final int PRG_SELECT = 0x03;
    private static final int CHR_SELECT = 0xF0;

    private final byte[] prgROM;

    /**
     * The pattern table storage, which is ROM on every board that shipped. A header claiming no
     * CHR banks gets RAM instead of an empty array to index into.
     */
    private final byte[] chr;

    private final boolean chrIsRAM;
    private final Mirroring mirroring;

    /**
     * How many banks each chip holds, less one, which doubles as the mask a written bank number is
     * folded through. A real board only decodes as many bits as it has banks.
     */
    private final int prgBankMask;
    private final int chrBankMask;

    private int prgBank;
    private int chrBank;

    public Mapper11(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.mirroring = mirroring;
        this.prgBankMask = Math.max(1, prgROM.length / PRG_BANK_SIZE) - 1;
        this.chrBankMask = Math.max(1, this.chr.length / CHR_BANK_SIZE) - 1;
    }

    @Override
    public int prgRead(final int address) {
        var offset = address & 0x7FFF;

        // A chip smaller than the window it sits in folds back on itself, the way a 16KB NROM
        // does.
        if (offset >= prgROM.length) {
            return Byte.toUnsignedInt(prgROM[offset % prgROM.length]);
        }

        return Byte.toUnsignedInt(prgROM[prgBank * PRG_BANK_SIZE + offset]);
    }

    @Override
    public void prgWrite(final int address, final int data) {
        prgBank = (data & PRG_SELECT) & prgBankMask;
        chrBank = ((data & CHR_SELECT) >> 4) & chrBankMask;
    }

    @Override
    public int charRead(final int address) {
        return Byte.toUnsignedInt(chr[chrBank * CHR_BANK_SIZE + (address & 0x1FFF)]);
    }

    @Override
    public void charWrite(final int address, final int data) {
        if (chrIsRAM) {
            chr[chrBank * CHR_BANK_SIZE + (address & 0x1FFF)] = (byte) data;
        }
    }

    @Override
    public Mirroring mirroring() {
        return mirroring;
    }

    /**
     * Two bank numbers out of the one latch that holds them, and nothing else: mirroring is
     * soldered, and nothing answers at $6000 on these boards.
     */
    @Override
    public void serialize(final StateIO io) {
        prgBank = io.u8(prgBank);
        chrBank = io.u8(chrBank);

        if (chrIsRAM) {
            io.bytes(chr);
        }
    }
}
