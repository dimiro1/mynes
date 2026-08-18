package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Mapper 66, GxROM: {@link Mapper11} with the two halves of the latch the other way round.
 * <p>
 * A write to $8000-$FFFF selects a 32KB PRG bank with bits 4 and 5 and an 8KB CHR bank with bits
 * 0 and 1, so the largest cartridge is 128KB of each. Nintendo used it for the cheap end of the
 * catalogue -- Dragon Power, Gumshoe, Doraemon -- and for the Super Mario Bros. / Duck Hunt
 * cartridge that came in the box, which is a pair of NROM games sharing one board.
 * <p>
 * Bus conflicts are real on GxROM and are not emulated here, for the reason {@link Mapper2}
 * gives.
 *
 * @see <a href="https://www.nesdev.org/wiki/GxROM">NESdev: GxROM</a>
 */
public class Mapper66 implements Mapper {
    private static final int PRG_BANK_SIZE = 0x8000;
    private static final int CHR_BANK_SIZE = 0x2000;
    private static final int CHR_RAM_SIZE = 0x2000;

    private static final int PRG_SELECT = 0x30;
    private static final int CHR_SELECT = 0x03;

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

    public Mapper66(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
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
        // does -- which is exactly what the Duck Hunt half of that pack-in cartridge is.
        if (offset >= prgROM.length) {
            return Byte.toUnsignedInt(prgROM[offset % prgROM.length]);
        }

        return Byte.toUnsignedInt(prgROM[prgBank * PRG_BANK_SIZE + offset]);
    }

    @Override
    public void prgWrite(final int address, final int data) {
        prgBank = ((data & PRG_SELECT) >> 4) & prgBankMask;
        chrBank = (data & CHR_SELECT) & chrBankMask;
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
