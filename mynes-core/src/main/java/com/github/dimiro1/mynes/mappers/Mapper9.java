package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Mapper 9, MMC2: the pattern tables switch themselves, from what the PPU just looked at.
 * <p>
 * Each 4KB half of the pattern table has two banks assigned to it and a one bit latch saying
 * which of them is showing. Nothing in the game writes that latch. It is set by the PPU reading
 * particular addresses -- $0FD8 and $0FE8 for the lower half, $1FD8-$1FDF and $1FE8-$1FEF for the
 * upper -- which are tiles $FD and $FE in each. So a game puts a sentinel tile at the end of a run
 * of sprites and the fetch of that tile banks in the next set, with no code and no cycles spent.
 * Punch-Out!! is the reason the chip exists: it animates two large boxers out of far more tiles
 * than the 8KB window holds.
 * <p>
 * Two consequences shape where the latch lives.
 * <p>
 * <b>It cannot go in {@link #charRead}.</b> That is reached from {@code VRAM.peek} as well as
 * {@code VRAM.read}, and peek is the path the CHR viewer, {@code --dump chr} and the save state
 * walk all take. A latch there would mean opening the pattern table viewer re-banks the game
 * underneath the player. It goes in {@link #ppuAddress}, which exists for exactly this and is
 * where MMC3 counts A12 for the same reason.
 * <p>
 * <b>It has to lag one bus cycle.</b> {@code VRAM.read} announces the address before it fetches
 * the byte, but the chip switches at the end of the cycle, so the fetch that trips a latch still
 * reads through the bank that was already selected. $0FD8 is the high plane of tile $FD and
 * $0FD0 is its low plane: a latch that fired immediately would take the two halves of that one
 * tile from two different banks and tear it. Hence {@code lastAddress} -- each address is decoded
 * when the next one arrives.
 *
 * @see <a href="https://www.nesdev.org/wiki/MMC2">NESdev: MMC2</a>
 */
public class Mapper9 implements Mapper {
    private static final int PRG_BANK_SIZE = 0x2000;
    private static final int CHR_BANK_SIZE = 0x1000;
    private static final int CHR_RAM_SIZE = 0x2000;

    /**
     * Which of a window's two banks is showing. The names are the tile numbers whose fetch
     * selects them, which is how NESdev and the games themselves talk about it.
     */
    private static final int LATCH_FD = 0;
    private static final int LATCH_FE = 1;

    private final byte[] prgROM;

    /**
     * The pattern table storage, which is ROM on every MMC2 board -- there is only the one. A
     * header claiming no CHR banks gets RAM instead of an empty array to index into.
     */
    private final byte[] chr;

    private final boolean chrIsRAM;

    private final int prgBankMask;
    private final int chrBankMask;

    /**
     * The two banks each window can show, indexed {@code window * 2 + latch}: $B000 and $C000 fill
     * the pair for $0000-$0FFF, $D000 and $E000 the pair for $1000-$1FFF.
     */
    private final int[] chrBanks = new int[4];

    /**
     * Which bank of its pair each window is showing.
     * <p>
     * NESdev does not say what these power up as, and it does not matter: a game that relies on
     * the latch sets both within its first frame, because it has to name the banks before it can
     * name which one is showing.
     */
    private final int[] latch = new int[2];

    /**
     * The last address the PPU put on the bus, waiting to be decoded. See the class comment: the
     * fetch that trips a latch is not the one that sees it move.
     */
    private int lastAddress;

    private int prgBank;
    private boolean horizontalMirroring;

    public Mapper9(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.prgBankMask = Math.max(1, prgROM.length / PRG_BANK_SIZE) - 1;
        this.chrBankMask = Math.max(1, this.chr.length / CHR_BANK_SIZE) - 1;
        this.horizontalMirroring = mirroring == Mirroring.HORIZONTAL;
    }

    @Override
    public int prgRead(final int address) {
        var offset = address & 0x7FFF;
        var page = offset >> 13;

        // Only $8000-$9FFF moves. The three pages above it are the last three banks of the chip,
        // folded in case a header names one with fewer than four.
        var bank = page == 0 ? prgBank : prgBankMask - 3 + page;

        return Byte.toUnsignedInt(
                prgROM[(bank & prgBankMask) * PRG_BANK_SIZE + (offset & 0x1FFF)]);
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
     * Both windows' banks, both latches, and the address still waiting to be decoded -- dropping
     * that last one would put one fetch through the wrong bank on every load. The mirroring is a
     * register rather than a solder pad here, so it travels too.
     */
    @Override
    public void serialize(final StateIO io) {
        prgBank = io.u8(prgBank);
        io.bytes(chrBanks);
        io.bytes(latch);
        lastAddress = io.u16(lastAddress);
        horizontalMirroring = io.bool(horizontalMirroring);

        if (chrIsRAM) {
            io.bytes(chr);
        }
    }

    /**
     * Decodes one address the PPU has finished with into the latches.
     * <p>
     * The lower window answers to two exact addresses and the upper to two eight byte ranges,
     * which is asymmetric because the chip is: MMC2 decodes every line of the lower half and only
     * the top eleven of the upper. {@link Mapper10} widened both to ranges.
     */
    private void applyLatch(final int address) {
        if (address == 0x0FD8) {
            latch[0] = LATCH_FD;
        } else if (address == 0x0FE8) {
            latch[0] = LATCH_FE;
        } else if ((address & 0xFFF8) == 0x1FD8) {
            latch[1] = LATCH_FD;
        } else if ((address & 0xFFF8) == 0x1FE8) {
            latch[1] = LATCH_FE;
        }
    }

    private int charIndex(final int address) {
        var window = address >> 12;
        var bank = chrBanks[window * 2 + latch[window]] & chrBankMask;

        return bank * CHR_BANK_SIZE + (address & 0x0FFF);
    }
}
