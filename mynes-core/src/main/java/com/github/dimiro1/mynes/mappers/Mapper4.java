package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Mapper 4, MMC3: the workhorse of the later library -- Super Mario Bros. 3, Mega Man 3 to 6,
 * Kirby's Adventure, Startropics.
 * <p>
 * Two things make it worth the trouble. It banks finely: PRG in 8KB pages, CHR in 1KB and 2KB
 * ones, so a game can change a handful of tiles without redrawing anything. And it counts
 * scanlines in hardware and interrupts the CPU on a chosen one, which is what a split screen is
 * made of: the status bar at the top of Super Mario Bros. 3 stays where it is while the playfield
 * under it scrolls, because the game changes the scroll position inside an interrupt that fires
 * partway down the picture.
 * <p>
 * The counter has no idea what a scanline is. It watches PPU address line A12 -- the line that
 * picks between the two pattern tables -- and counts the times it rises. With the background
 * fetched from one table and the sprites from the other, which is exactly how a game using this
 * arranges its tiles, A12 rises once per scanline in the middle of the sprite fetches. Everything
 * awkward about this chip follows from that: see {@link #A12_FILTER_DOTS}.
 *
 * @see <a href="https://www.nesdev.org/wiki/MMC3">NESdev: MMC3</a>
 */
public class Mapper4 implements Mapper {
    private static final int PRG_PAGE_SIZE = 0x2000;
    private static final int CHR_PAGE_SIZE = 0x0400;
    private static final int CHR_RAM_SIZE = 0x2000;
    private static final int PRG_RAM_SIZE = 0x2000;

    /**
     * How many dots A12 has to have been low for before a rise is taken as a real one.
     * <p>
     * The line is not a clean square wave. Within one scanline the PPU flips it every few dots as
     * the fetch pipeline alternates between nametables, attributes and patterns, and counting all
     * of those would clock the counter eight or more times a line instead of once. The chip
     * filters them out with a low pass on the line -- three rising edges of M2, so around nine
     * dots -- and only the rise that follows a long quiet period gets through.
     * <p>
     * Ten dots is the value that works for this PPU's fetch schedule, and the band it can sit in
     * is narrow at both ends:
     * <ul>
     *   <li>Background at $0000 and sprites at $1000, which is Super Mario Bros. 3: the only
     *       rises are the sprite pattern fetches, four dots apart, and the first of them follows
     *       the whole 256 dot visible portion of the line with A12 low. Anything above four
     *       filters the rest and leaves one clock a line, around dot 260, where the hardware
     *       has it.</li>
     *   <li>Background at $1000 and sprites at $0000: the pattern fetches make A12 rise twice a
     *       tile with four dot gaps, and the run from the last one of a line to the first of the
     *       next is nine dots. So the threshold has to be above nine, or the counter clocks twice
     *       a line. The one long gap is the sprite fetch phase, and the rise that follows it --
     *       the first background prefetch, around dot 325 -- is the one clock a line the hardware
     *       counts.</li>
     *   <li>A game with rendering switched off toggles A12 by hand through $2006, and the
     *       shortest thing it can do that with is two four cycle stores: twelve dots. So the
     *       threshold has to be twelve or less, or those stop counting.</li>
     * </ul>
     */
    private static final int A12_FILTER_DOTS = 10;

    /**
     * Where {@link #dotsLow} stops counting. Well past the filter, and only there because the
     * count would otherwise overflow after a few minutes of a line held low -- which a game that
     * leaves rendering off for a while really does.
     */
    private static final int DOTS_LOW_CAP = 1000;

    // Bank select ($8000) fields.
    private static final int SELECT_TARGET = 0x07;
    private static final int SELECT_PRG_MODE = 0x40;
    private static final int SELECT_CHR_INVERSION = 0x80;

    // PRG RAM protect ($A001) fields.
    private static final int RAM_ENABLED = 0x80;
    private static final int RAM_WRITE_PROTECTED = 0x40;

    private final byte[] prgROM;
    private final byte[] chr;
    private final boolean chrIsRAM;
    private final byte[] prgRAM = new byte[PRG_RAM_SIZE];

    /**
     * What the cartridge header said. Only {@link Mirroring#FOUR_SCREEN} matters: a board with
     * its own nametable RAM on it -- Rad Racer II, Gauntlet -- has nothing for the mirroring
     * register to switch, so the register is ignored there.
     */
    private final Mirroring headerMirroring;

    private final int prgBankMask;
    private final int chrBankMask;

    // --- banking -----------------------------------------------------------------------

    private int bankSelect;

    /**
     * R0 to R7: the two 2KB CHR banks, the four 1KB ones, and the two switchable PRG pages.
     */
    private final int[] banks = new int[8];

    private boolean horizontalMirroring;

    /**
     * $A001. Powers on with the RAM enabled and writable, which is the forgiving choice: a game
     * that expects its save RAM to be there before it has configured anything finds it.
     */
    private int prgRAMProtect = RAM_ENABLED;

    // --- the scanline counter ----------------------------------------------------------

    private int irqLatch;
    private int irqCounter;
    private boolean irqReloadPending;
    private boolean irqEnabled;

    /**
     * Defaults to a handler that does nothing, so the chip can be driven by a test with no
     * console around it. {@link com.github.dimiro1.mynes.BUS} replaces it once the CPU exists.
     */
    private IRQHandler irqHandler = asserted -> { };

    /**
     * The level A12 was last seen at, and how many dots it has been low for.
     */
    private boolean a12;
    private int dotsLow;

    public Mapper4(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.headerMirroring = mirroring;
        this.prgBankMask = Math.max(1, prgROM.length / PRG_PAGE_SIZE) - 1;
        this.chrBankMask = Math.max(1, chr.length / CHR_PAGE_SIZE) - 1;
    }

    @Override
    public int prgRead(final int address) {
        var bank = prgBankFor((address & 0x7FFF) >> 13) & prgBankMask;

        return Byte.toUnsignedInt(prgROM[bank * PRG_PAGE_SIZE + (address & 0x1FFF)]);
    }

    /**
     * Writes one of the eight registers.
     * <p>
     * There are only two address lines to name them with: A13 and A14 pick a pair, and A0 -- so,
     * an even or an odd address -- picks which of the pair. Everything else on the bus is
     * ignored, which is why {@code address & 0xE001} is the whole decode.
     */
    @Override
    public void prgWrite(final int address, final int data) {
        switch (address & 0xE001) {
            case 0x8000 -> bankSelect = data;
            case 0x8001 -> banks[bankSelect & SELECT_TARGET] = data;
            case 0xA000 -> horizontalMirroring = (data & 1) != 0;
            case 0xA001 -> prgRAMProtect = data;
            case 0xC000 -> irqLatch = data;
            case 0xC001 -> {
                // Not a reload: the counter is emptied, and the next rise refills it.
                irqCounter = 0;
                irqReloadPending = true;
            }
            case 0xE000 -> {
                irqEnabled = false;
                // Acknowledges as well as disables, which is how a handler clears the line.
                irqHandler.setIRQLine(false);
            }
            // $E001, the only case left: enabling does not raise anything by itself, even if the
            // counter is sitting at zero already.
            default -> irqEnabled = true;
        }
    }

    @Override
    public int prgRAMRead(final int address) {
        if ((prgRAMProtect & RAM_ENABLED) == 0) {
            return 0;
        }

        return Byte.toUnsignedInt(prgRAM[address & 0x1FFF]);
    }

    @Override
    public void prgRAMWrite(final int address, final int data) {
        if ((prgRAMProtect & RAM_ENABLED) == 0 || (prgRAMProtect & RAM_WRITE_PROTECTED) != 0) {
            return;
        }

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
        var high = (address & 0x1000) != 0;

        if (high && !a12) {
            if (dotsLow >= A12_FILTER_DOTS) {
                clockScanlineCounter();
            }
        } else if (!high && a12) {
            dotsLow = 0;
        }

        a12 = high;
    }

    @Override
    public void ppuTick() {
        if (!a12 && dotsLow < DOTS_LOW_CAP) {
            dotsLow++;
        }
    }

    @Override
    public void setIRQHandler(final IRQHandler handler) {
        this.irqHandler = handler;
    }

    @Override
    public Mirroring mirroring() {
        if (headerMirroring == Mirroring.FOUR_SCREEN) {
            return Mirroring.FOUR_SCREEN;
        }

        return horizontalMirroring ? Mirroring.HORIZONTAL : Mirroring.VERTICAL;
    }

    /**
     * One scanline, as far as the chip is concerned.
     * <p>
     * An empty counter reloads instead of counting down, so the latch is a period rather than a
     * one-shot: leave it alone and the interrupt comes back every latch+1 scanlines. This is the
     * behaviour of the MMC3B and MMC3C, the revisions almost every game shipped on; the earlier
     * MMC3A reloads a beat differently, and a couple of games depend on that.
     */
    private void clockScanlineCounter() {
        if (irqCounter == 0 || irqReloadPending) {
            irqCounter = irqLatch;
            irqReloadPending = false;
        } else {
            irqCounter--;
        }

        if (irqCounter == 0 && irqEnabled) {
            irqHandler.setIRQLine(true);
        }
    }

    /**
     * @param page which 8KB page of $8000-$FFFF is being read, 0 to 3.
     * @return the bank number that answers for it, before masking.
     */
    private int prgBankFor(final int page) {
        // Bit 6 of the bank select swaps which end of the window is fixed. The second to last
        // bank is nailed to whichever of $8000 and $C000 the switchable page is not using, and
        // the last bank is always at $E000 -- that is where the vectors are.
        var swapped = (bankSelect & SELECT_PRG_MODE) != 0;
        var last = prgBankMask;

        return switch (page) {
            case 0 -> swapped ? last - 1 : banks[6];
            case 1 -> banks[7];
            case 2 -> swapped ? banks[6] : last - 1;
            default -> last;
        };
    }

    /**
     * @param address an address in $0000-$1FFF.
     * @return where in {@link #chr} it lands.
     */
    private int charIndex(final int address) {
        // Bit 7 of the bank select swaps the two halves of the pattern table window, so that the
        // pair of 2KB banks can sit at either $0000 or $1000.
        var a = (bankSelect & SELECT_CHR_INVERSION) != 0 ? address ^ 0x1000 : address;

        var bank = switch (a >> 10) {
            // R0 and R1 cover 2KB each, so their lowest bit is not theirs to set.
            case 0 -> banks[0] & ~1;
            case 1 -> banks[0] | 1;
            case 2 -> banks[1] & ~1;
            case 3 -> banks[1] | 1;
            // R2 to R5, one kilobyte each.
            default -> banks[(a >> 10) - 2];
        };

        return ((bank & chrBankMask) * CHR_PAGE_SIZE) | (a & 0x3FF);
    }

    /**
     * The banking, the scanline counter, and where the A12 filter has got to.
     * <p>
     * The filter is the part that is easy to leave out and expensive to get wrong. Saving in the
     * middle of a scanline and restoring without {@code a12} and {@code dotsLow} gives back a chip
     * that has forgotten how long the line has been quiet, so the next rise either counts when it
     * should not or fails to when it should. That is one scanline of one split screen, once, which
     * is exactly the sort of thing that gets mistaken for a mapper bug.
     * <p>
     * What is <em>not</em> here is whether the chip is currently pulling /IRQ low. Nothing on the
     * board remembers it -- $E000 drops the line without emptying the counter, $E001 arms the
     * counter without raising the line -- so {@link com.github.dimiro1.mynes.BUS} keeps that bit.
     * The handler itself is wiring rather than state, and survives because a state is loaded into
     * the machine that is already running rather than into a rebuilt one.
     */
    @Override
    public void serialize(final StateIO io) {
        bankSelect = io.u8(bankSelect);
        io.bytes(banks);
        horizontalMirroring = io.bool(horizontalMirroring);
        prgRAMProtect = io.u8(prgRAMProtect);

        irqLatch = io.u8(irqLatch);
        irqCounter = io.u8(irqCounter);
        irqReloadPending = io.bool(irqReloadPending);
        irqEnabled = io.bool(irqEnabled);

        a12 = io.bool(a12);
        dotsLow = io.u16(dotsLow);

        io.bytes(prgRAM);

        if (chrIsRAM) {
            io.bytes(chr);
        }
    }
}
