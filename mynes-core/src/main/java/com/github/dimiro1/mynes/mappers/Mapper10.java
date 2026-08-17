package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Mapper 10, MMC4: {@link Mapper9} with a bigger PRG window, a RAM chip, and the address decoding
 * tidied up.
 * <p>
 * The self-switching pattern tables are the same idea and the same four registers, so the class
 * comment on {@link Mapper9} covers the whole of why the latch lives in {@link #ppuAddress} and
 * why it lags one bus cycle. Three things differ:
 * <ul>
 *   <li>PRG is a 16KB window at $8000-$BFFF with the last 16KB fixed above it, rather than 8KB
 *       switchable and three fixed. The vectors sit in the fixed half either way.</li>
 *   <li>Both latches answer to eight byte ranges. MMC2 decoded every address line of the lower
 *       half, so $0FD8 and $0FE8 had to be exact; MMC4 stops at the same eleven lines for both.
 *       Nothing is known to depend on the difference, but it is one line of code to be right
 *       about.</li>
 *   <li>There is 8KB of RAM at $6000, battery backed, and no enable line to gate it with. Fire
 *       Emblem and Famicom Wars are the reason: both are long games that save.</li>
 * </ul>
 * <p>
 * Written out rather than extending {@link Mapper9}, which is the same call {@link Mapper3} makes
 * by repeating {@link Mapper0}'s PRG decoding word for word. The two chips are cousins, not one
 * chip with a flag, and a shared base would have to be told which it was in three places.
 *
 * @see <a href="https://www.nesdev.org/wiki/MMC4">NESdev: MMC4</a>
 */
public class Mapper10 implements Mapper {
    private static final int PRG_BANK_SIZE = 0x4000;
    private static final int CHR_BANK_SIZE = 0x1000;
    private static final int CHR_RAM_SIZE = 0x2000;
    private static final int PRG_RAM_SIZE = 0x2000;

    private static final int LATCH_FD = 0;
    private static final int LATCH_FE = 1;

    private final byte[] prgROM;

    /**
     * The pattern table storage, which is ROM on every MMC4 board. A header claiming no CHR banks
     * gets RAM instead of an empty array to index into.
     */
    private final byte[] chr;

    private final byte[] prgRAM = new byte[PRG_RAM_SIZE];

    private final boolean chrIsRAM;

    private final int prgBankMask;
    private final int chrBankMask;

    /**
     * Where the last bank starts, which is what $C000-$FFFF always reads.
     */
    private final int lastBankBase;

    /**
     * The two banks each window can show, indexed {@code window * 2 + latch}, as on
     * {@link Mapper9}.
     */
    private final int[] chrBanks = new int[4];

    private final int[] latch = new int[2];

    /**
     * The last address the PPU put on the bus, waiting to be decoded one access late.
     */
    private int lastAddress;

    private int prgBank;
    private boolean horizontalMirroring;

    public Mapper10(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.prgBankMask = Math.max(1, prgROM.length / PRG_BANK_SIZE) - 1;
        this.chrBankMask = Math.max(1, this.chr.length / CHR_BANK_SIZE) - 1;
        this.lastBankBase = Math.max(0, prgROM.length - PRG_BANK_SIZE);
        this.horizontalMirroring = mirroring == Mirroring.HORIZONTAL;
    }

    @Override
    public int prgRead(final int address) {
        var offset = address & 0x7FFF;

        if (offset < PRG_BANK_SIZE) {
            return Byte.toUnsignedInt(
                    prgROM[(prgBank & prgBankMask) * PRG_BANK_SIZE + offset]);
        }

        return Byte.toUnsignedInt(prgROM[lastBankBase + (offset & 0x3FFF)]);
    }

    @Override
    public void prgWrite(final int address, final int data) {
        switch (address & 0xF000) {
            case 0xA000 -> prgBank = data & 0x0F;
            case 0xB000 -> chrBanks[0] = data & 0x1F;
            case 0xC000 -> chrBanks[1] = data & 0x1F;
            case 0xD000 -> chrBanks[2] = data & 0x1F;
            case 0xE000 -> chrBanks[3] = data & 0x1F;
            case 0xF000 -> horizontalMirroring = (data & 1) != 0;
            // $8000-$9FFF, which nothing on the board answers to.
            default -> { }
        }
    }

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
        return Byte.toUnsignedInt(chr[charIndex(address & 0x1FFF)]);
    }

    @Override
    public void charWrite(final int address, final int data) {
        if (chrIsRAM) {
            chr[charIndex(address & 0x1FFF)] = (byte) data;
        }
    }

    @Override
    public void ppuAddress(final int address) {
        applyLatch(lastAddress);
        lastAddress = address;
    }

    @Override
    public Mirroring mirroring() {
        return horizontalMirroring ? Mirroring.HORIZONTAL : Mirroring.VERTICAL;
    }

    /**
     * Everything {@link Mapper9} carries, and the battery RAM as well.
     */
    @Override
    public void serialize(final StateIO io) {
        prgBank = io.u8(prgBank);
        io.bytes(chrBanks);
        io.bytes(latch);
        lastAddress = io.u16(lastAddress);
        horizontalMirroring = io.bool(horizontalMirroring);

        io.bytes(prgRAM);

        if (chrIsRAM) {
            io.bytes(chr);
        }
    }

    /**
     * Decodes one address the PPU has finished with into the latches. Both windows answer to
     * eight byte ranges here, where {@link Mapper9} wanted the lower two exact.
     */
    private void applyLatch(final int address) {
        switch (address & 0xFFF8) {
            case 0x0FD8 -> latch[0] = LATCH_FD;
            case 0x0FE8 -> latch[0] = LATCH_FE;
            case 0x1FD8 -> latch[1] = LATCH_FD;
            case 0x1FE8 -> latch[1] = LATCH_FE;
            default -> { }
        }
    }

    private int charIndex(final int address) {
        var window = address >> 12;
        var bank = chrBanks[window * 2 + latch[window]] & chrBankMask;

        return bank * CHR_BANK_SIZE + (address & 0x0FFF);
    }
}
