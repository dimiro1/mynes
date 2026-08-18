package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Mapper 7, AxROM: the whole PRG window moves at once, and the cartridge drives CIRAM A10 itself.
 * <p>
 * There is no fixed bank anywhere. A write to $8000-$FFFF loads one latch whose low bits choose a
 * 32KB slice of PRG ROM and whose bit 4 chooses which single kilobyte of nametable RAM the entire
 * screen is made of. The reset vector moves with everything else, so the switching code has to be
 * duplicated at the same offset in every bank -- which is cheaper in ROM than an MMC1 is in
 * silicon, and is why Rare shipped so many games on this board.
 * <p>
 * Single screen mirroring is the point of it rather than a side effect: Battletoads, Marble
 * Madness, Solstice and Cobra Triangle all draw into the kilobyte they are not showing and then
 * flip. The header's mirroring bits describe a board that cannot do that, so, as on
 * {@link Mapper1}, the constructor takes them and drops them.
 * <p>
 * Bus conflicts are not emulated, for the reason {@link Mapper2} gives at more length. AMROM has
 * them and ANROM and AOROM do not, nothing in an iNES header says which board this is, and
 * modelling them would only turn a game that gets it wrong into a game that crashes.
 *
 * @see <a href="https://www.nesdev.org/wiki/AxROM">NESdev: AxROM</a>
 */
public class Mapper7 implements Mapper {
    private static final int PRG_BANK_SIZE = 0x8000;
    private static final int CHR_RAM_SIZE = 0x2000;

    /**
     * Bit 4 of the latch: set holds CIRAM A10 high, clear holds it low.
     */
    private static final int MIRRORING_HIGH = 0x10;

    private final byte[] prgROM;

    /**
     * The pattern table storage, which is RAM on every real AxROM board. A cart carrying CHR ROM
     * anyway is taken as it comes rather than rejected, the same way {@link Mapper2} takes one.
     */
    private final byte[] chr;

    private final boolean chrIsRAM;

    /**
     * How many 32KB banks there are, less one, which doubles as the mask a written bank number is
     * folded through. A real board only decodes as many bits as it has banks.
     */
    private final int bankMask;

    /**
     * Whatever the last write left: the bank number and the mirroring bit in the one byte the
     * board latched them from. Splitting them into two fields would be inventing hardware.
     */
    private int bankSelect;

    @SuppressWarnings("unused")
    public Mapper7(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.bankMask = Math.max(1, prgROM.length / PRG_BANK_SIZE) - 1;
    }

    @Override
    public int prgRead(final int address) {
        var offset = address & 0x7FFF;

        // A chip smaller than the window it sits in folds back on itself, the way a 16KB NROM
        // does. No real AxROM cart is that small, but a synthesised header can be.
        if (offset >= prgROM.length) {
            return Byte.toUnsignedInt(prgROM[offset % prgROM.length]);
        }

        return Byte.toUnsignedInt(prgROM[(bankSelect & bankMask) * PRG_BANK_SIZE + offset]);
    }

    @Override
    public void prgWrite(final int address, final int data) {
        bankSelect = data;
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
        return (bankSelect & MIRRORING_HIGH) != 0
                ? Mirroring.ONE_SCREEN_HIGH
                : Mirroring.ONE_SCREEN_LOW;
    }

    /**
     * One latch, and the pattern tables because they are RAM. The mirroring is not saved
     * separately: it is read back out of the same byte as the bank number, so it travels with it.
     * Nothing answers at $6000 on an AxROM board, so there is no cartridge RAM to carry.
     */
    @Override
    public void serialize(final StateIO io) {
        bankSelect = io.u8(bankSelect);

        if (chrIsRAM) {
            io.bytes(chr);
        }
    }
}
