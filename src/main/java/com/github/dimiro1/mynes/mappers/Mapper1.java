package com.github.dimiro1.mynes.mappers;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Mapper 1, MMC1: Nintendo's first big mapper, and the one behind most of the early classics --
 * Zelda, Metroid, Mega Man 2, Final Fantasy, Castlevania II.
 * <p>
 * The chip has four five bit registers, and only one data line to load them with. A write to
 * $8000-$FFFF puts one bit into an internal shift register, least significant bit first; the
 * fifth write commits all five bits at once, and the address of <em>that</em> write picks which
 * register they land in. Bits 13 and 14 do the picking, so $8000, $A000, $C000 and $E000 name
 * control, CHR bank 0, CHR bank 1 and PRG bank respectively. A write with bit 7 set gives up
 * halfway through and resets the shift register, and also forces the PRG mode back to the one
 * the chip powers on in -- which is what makes it a usable reset: whatever bank was switched in,
 * the last one reappears at $C000 with the vectors in it.
 * <p>
 * Two things are deliberately left out. The real chip ignores the second of two writes on
 * consecutive CPU cycles, which matters only to a read-modify-write instruction aimed at the
 * register area -- something no game does on purpose. And SUROM, which steals a CHR bank bit to
 * address a fifth PRG address line, is not decoded, so the handful of 512KB carts are limited to
 * their first 256KB.
 *
 * @see <a href="https://www.nesdev.org/wiki/MMC1">NESdev: MMC1</a>
 */
public class Mapper1 implements Mapper {
    private static final int PRG_BANK_SIZE = 0x4000;
    private static final int CHR_BANK_SIZE = 0x1000;
    private static final int CHR_RAM_SIZE = 0x2000;
    private static final int PRG_RAM_SIZE = 0x2000;

    /**
     * How many writes it takes to fill the shift register.
     */
    private static final int WRITES_PER_REGISTER = 5;

    // Control register ($8000) fields.
    private static final int CONTROL_MIRRORING = 0x03;
    private static final int CONTROL_PRG_MODE = 0x0C;
    private static final int CONTROL_CHR_MODE = 0x10;

    /**
     * $8000-$BFFF switchable, $C000-$FFFF fixed to the last bank. The mode the chip powers on in
     * and the one a reset write returns it to.
     */
    private static final int PRG_MODE_FIXED_LAST = 0x0C;

    /**
     * Bit 4 of the PRG bank register, which is the PRG RAM chip's enable line. Set means
     * disabled, so a register cleared to zero leaves the RAM readable.
     */
    private static final int PRG_RAM_DISABLE = 0x10;

    private final byte[] prgROM;

    /**
     * The pattern table storage, either the cart's CHR ROM or the 8KB of CHR RAM allocated in its
     * place. Half the MMC1 boards are one and half the other.
     */
    private final byte[] chr;

    private final boolean chrIsRAM;

    /**
     * Cartridge RAM at $6000-$7FFF, present whether or not the header claims a battery: the chip
     * has an enable line for it either way, and the boards without a chip fitted simply never
     * have it read.
     */
    private final byte[] prgRAM = new byte[PRG_RAM_SIZE];

    private final int prgBankMask;
    private final int chrBankMask;

    // --- the serial loading port -------------------------------------------------------

    private int shiftRegister;
    private int shiftCount;

    // --- the four registers ------------------------------------------------------------

    private int control = PRG_MODE_FIXED_LAST;
    private int chrBank0;
    private int chrBank1;
    private int prgBank;

    /**
     * @param mirroring the header's mirroring, which MMC1 ignores: the control register drives
     *                  the line itself, and every MMC1 game sets it up before it draws anything
     *                  because the power-on value is not defined.
     */
    public Mapper1(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.prgBankMask = Math.max(1, prgROM.length / PRG_BANK_SIZE) - 1;
        this.chrBankMask = Math.max(1, chr.length / CHR_BANK_SIZE) - 1;
    }

    @Override
    public int prgRead(final int address) {
        var offset = address & 0x7FFF;
        var bank = prgBankFor(offset) & prgBankMask;

        return Byte.toUnsignedInt(prgROM[bank * PRG_BANK_SIZE + (offset & 0x3FFF)]);
    }

    @Override
    public void prgWrite(final int address, final int data) {
        if ((data & 0x80) != 0) {
            resetShiftRegister();
            control |= PRG_MODE_FIXED_LAST;
            return;
        }

        shiftRegister |= (data & 1) << shiftCount;

        if (++shiftCount < WRITES_PER_REGISTER) {
            return;
        }

        // Bits 13 and 14 of the fifth write's address say where the five bits belong.
        switch ((address >> 13) & 3) {
            case 0 -> control = shiftRegister;
            case 1 -> chrBank0 = shiftRegister;
            case 2 -> chrBank1 = shiftRegister;
            default -> prgBank = shiftRegister;
        }

        resetShiftRegister();
    }

    @Override
    public int prgRAMRead(final int address) {
        // A disabled chip is not driving the bus, so this is really open bus rather than zero.
        return isPRGRAMEnabled() ? Byte.toUnsignedInt(prgRAM[address & 0x1FFF]) : 0;
    }

    @Override
    public void prgRAMWrite(final int address, final int data) {
        // A dropped write leaves what is already in the RAM alone, which is the point of the
        // enable line: a game turns the chip off around anything that might crash so that a
        // battery backed save survives.
        if (isPRGRAMEnabled()) {
            prgRAM[address & 0x1FFF] = (byte) data;
        }
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
    public Mirroring mirroring() {
        return switch (control & CONTROL_MIRRORING) {
            case 0 -> Mirroring.ONE_SCREEN_LOW;
            case 1 -> Mirroring.ONE_SCREEN_HIGH;
            case 2 -> Mirroring.VERTICAL;
            default -> Mirroring.HORIZONTAL;
        };
    }

    /**
     * Works out which 16KB bank answers for one half of the PRG window.
     *
     * @param offset the address, less $8000.
     * @return the bank number, before it is folded through {@link #prgBankMask}.
     */
    private int prgBankFor(final int offset) {
        // 0 for $8000-$BFFF, 1 for $C000-$FFFF.
        var half = offset >> 14;
        var bank = prgBank & 0x0F;

        return switch ((control & CONTROL_PRG_MODE) >> 2) {
            // One 32KB bank across the whole window, so the bank number's low bit is ignored and
            // the half of the window supplies it instead.
            case 0, 1 -> (bank & 0x0E) | half;
            // The first bank pinned at $8000, the switchable one above it.
            case 2 -> half == 0 ? 0 : bank;
            // The last bank pinned at $C000, the switchable one below it. Power-on mode.
            default -> half == 0 ? bank : prgBankMask;
        };
    }

    /**
     * @param address an address in $0000-$1FFF.
     * @return where in {@link #chr} it lands, given the banking mode in force.
     */
    private int charIndex(final int address) {
        var bank = (control & CONTROL_CHR_MODE) == 0
                // One 8KB bank: the low bit of the bank number is ignored and A12 supplies it.
                ? (chrBank0 & 0x1E) | (address >> 12)
                // Two 4KB banks, one per pattern table.
                : (address < CHR_BANK_SIZE ? chrBank0 : chrBank1);

        return (bank & chrBankMask) * CHR_BANK_SIZE + (address & 0x0FFF);
    }

    /**
     * The four registers, and the serial port that loads them.
     * <p>
     * {@code shiftRegister} and {@code shiftCount} are half of a five-write sequence, and a frame
     * boundary can fall in the middle of one. Neither the mirroring nor whether the RAM chip is
     * enabled is saved separately: both are read out of {@code control} and {@code prgBank}, so they
     * arrive with them.
     */
    @Override
    public void serialize(final StateIO io) {
        shiftRegister = io.u8(shiftRegister);
        shiftCount = io.u8(shiftCount);

        control = io.u8(control);
        chrBank0 = io.u8(chrBank0);
        chrBank1 = io.u8(chrBank1);
        prgBank = io.u8(prgBank);

        io.bytes(prgRAM);

        if (chrIsRAM) {
            io.bytes(chr);
        }
    }

    private boolean isPRGRAMEnabled() {
        return (prgBank & PRG_RAM_DISABLE) == 0;
    }

    private void resetShiftRegister() {
        shiftRegister = 0;
        shiftCount = 0;
    }
}
