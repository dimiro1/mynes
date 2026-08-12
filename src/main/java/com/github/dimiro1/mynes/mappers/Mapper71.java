package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Mapper 71, Camerica BF909x: {@link Mapper2} with the latch moved out of the way of a second one.
 * <p>
 * $8000-$BFFF is whichever 16KB bank was last written and $C000-$FFFF is always the last bank,
 * exactly as on UxROM. What is different is that the write which selects it has to land in
 * $C000-$FFFF, because $9000-$9FFF belongs to a second register: bit 4 there drives CIRAM A10 and
 * gives the board single screen mirroring. Camerica's unlicensed catalogue is nearly all of this
 * -- Micro Machines, Bee 52, Big Nose the Caveman -- and Fire Hawk is the one that needs the
 * mirroring.
 * <p>
 * Only the BF9097 board wires that second register up; on a BF9093 the write goes nowhere. An
 * iNES header does not say which board this is, so the register is honoured and the header's
 * mirroring is kept until something actually writes $9000. That runs Fire Hawk and costs the
 * others nothing, because they never write there -- getting it the other way round leaves Fire
 * Hawk's status bar scrolling with the level, which is the bug this arrangement exists to avoid.
 *
 * @see <a href="https://www.nesdev.org/wiki/INES_Mapper_071">NESdev: iNES mapper 071</a>
 */
public class Mapper71 implements Mapper {
    private static final int PRG_BANK_SIZE = 0x4000;
    private static final int CHR_RAM_SIZE = 0x2000;

    /**
     * The 4KB page the mirroring register answers in, and the bit of it that matters.
     */
    private static final int MIRRORING_PAGE = 0x9000;
    private static final int MIRRORING_HIGH = 0x10;

    private final byte[] prgROM;

    /**
     * The pattern table storage: CHR RAM on every Camerica board, but a cart carrying CHR ROM is
     * taken as it comes, the same way {@link Mapper2} takes one.
     */
    private final byte[] chr;

    private final boolean chrIsRAM;

    /**
     * How many 16KB banks there are, less one, which doubles as the mask a written bank number is
     * folded through. A real board only decodes as many bits as it has banks.
     */
    private final int bankMask;

    /**
     * Where the last bank starts, which is what $C000-$FFFF always reads.
     */
    private final int lastBankBase;

    private int prgBank;

    /**
     * How the nametables are wired, which starts as whatever the header said and stops being that
     * the first time the game writes $9000. Saved as it stands rather than derived from a register
     * bit, because "nothing has written it yet" is part of the state.
     */
    private Mirroring mirroring;

    public Mapper71(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
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
        if ((address & 0xF000) == MIRRORING_PAGE) {
            mirroring = (data & MIRRORING_HIGH) != 0
                    ? Mirroring.ONE_SCREEN_HIGH
                    : Mirroring.ONE_SCREEN_LOW;
            return;
        }

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

    /**
     * The bank latch, the mirroring, and the pattern tables when they are RAM. Nothing answers at
     * $6000 on a Camerica board, so there is no cartridge RAM to carry.
     */
    @Override
    public void serialize(final StateIO io) {
        prgBank = io.u8(prgBank);
        mirroring = io.enumeration(mirroring, Mirroring.class);

        if (chrIsRAM) {
            io.bytes(chr);
        }
    }
}
