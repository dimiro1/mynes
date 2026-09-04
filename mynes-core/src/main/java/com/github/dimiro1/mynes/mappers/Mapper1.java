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
 * The chip has five CHR bank outputs and most of the boards it went on only wire one of them to
 * anything, since 8KB of CHR RAM needs a single line. The other four are what the big cartridges
 * were built out of: SUROM runs one to a fifth PRG address line for 512KB of ROM, SOROM and
 * SXROM run one or two to the PRG RAM chips for 16KB and 32KB of save, and SNROM runs one to the
 * RAM's enable pin. Which board this is comes out of the sizes -- how much ROM and how much RAM
 * the header says there is -- and not out of a name, because NES 2.0 retired the submappers that
 * named them for exactly that reason. See {@link #prgBankFor} and {@link #prgRAMIndex}.
 * <p>
 * One thing is deliberately left out. The real chip ignores the second of two writes on
 * consecutive CPU cycles, which matters only to a read-modify-write instruction aimed at the
 * register area -- something no game does on purpose.
 *
 * @see <a href="https://www.nesdev.org/wiki/MMC1">NESdev: MMC1</a>
 * @see <a href="https://www.nesdev.org/wiki/NES_2.0_submappers#001:_MMC1">NESdev: mapper 1 submappers</a>
 */
public class Mapper1 implements Mapper {
    /**
     * NES 2.0 submapper 5: SEROM, SHROM and SH1ROM, which wire 32KB of PRG ROM straight to the
     * bus with none of the chip's PRG outputs connected. The PRG bank register still loads, and
     * nothing listens to it.
     */
    public static final int SUBMAPPER_FIXED_PRG = 5;

    /**
     * The MMC1A, the first revision of the chip, which has no PRG RAM enable: bit 4 of the PRG
     * bank register does something else there, and the RAM is always on. NES 2.0 named it
     * submapper 3 and then retired that in favour of mapper 155, so both arrive here as this.
     */
    public static final int SUBMAPPER_MMC1A = 3;

    private static final int PRG_BANK_SIZE = 0x4000;
    private static final int CHR_BANK_SIZE = 0x1000;
    private static final int CHR_RAM_SIZE = 0x2000;

    /**
     * The window at $6000-$7FFF, which is also the size of one PRG RAM bank: a bigger board is
     * more of these, switched by the CHR outputs, rather than a bigger window.
     */
    private static final int PRG_RAM_BANK_SIZE = 0x2000;

    /**
     * Where the four PRG bank bits run out. A cartridge with more ROM than this has taken a CHR
     * output for the fifth line, which is what tells SUROM apart from SNROM: the two boards use
     * the same bit of the same register for different things.
     */
    private static final int PRG_ROM_ON_FOUR_BITS = 16 * PRG_BANK_SIZE;

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
     * Bit 4 of the PRG bank register, which is the PRG RAM chip's enable line on an MMC1B. Set
     * means disabled, so a register cleared to zero leaves the RAM readable.
     */
    private static final int PRG_RAM_DISABLE = 0x10;

    /**
     * The four PRG bank bits the chip has of its own.
     */
    private static final int PRG_BANK_BITS = 0x0F;

    // The CHR bank outputs the big boards borrowed, as bits of a CHR bank register.

    /**
     * Bit 4: the 256KB half of a 512KB ROM on SUROM and SXROM, and the PRG RAM's enable pin on
     * SNROM -- the one line two boards read two ways, told apart by how much ROM there is.
     */
    private static final int CHR_OUT_PRG_ROM_HIGH = 0x10;

    /**
     * Bits 2 and 3: which 8KB of PRG RAM is in the window. SXROM wires both, SOROM only bit 3.
     */
    private static final int CHR_OUT_PRG_RAM_BANK = 0x0C;

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
     * have it read. One 8KB bank on most boards, two on SOROM, four on SXROM.
     */
    private final byte[] prgRAM;

    private final int prgBankMask;
    private final int chrBankMask;

    /**
     * How the two borrowed CHR bits turn into a PRG RAM bank number, as a right shift of the
     * field: the boards wire the chips from the top bit down, so SOROM's single bank line is bit
     * 3 rather than bit 2.
     */
    private final int prgRAMBankShift;
    private final int prgRAMBankMask;

    /**
     * Whether bit 4 of a CHR bank register is the RAM's enable pin, which it is on SNROM and only
     * there: a board with 8KB of CHR RAM, 8KB of PRG RAM and no more than 256KB of PRG ROM. More
     * ROM makes the bit an address line, and more RAM makes it a board that left the pin alone.
     */
    private final boolean chrBitDisablesPRGRAM;

    private final boolean fixedPRG;
    private final boolean mmc1a;

    // --- the serial loading port -------------------------------------------------------

    private int shiftRegister;
    private int shiftCount;

    // --- the four registers ------------------------------------------------------------

    private int control = PRG_MODE_FIXED_LAST;
    private int chrBank0;
    private int chrBank1;
    private int prgBank;

    /**
     * The ordinary board: 8KB of PRG RAM and no submapper, which is every MMC1 cartridge whose
     * header says nothing more.
     */
    public Mapper1(final byte[] prgROM, final byte[] chrROM, final Mirroring mirroring) {
        this(prgROM, chrROM, mirroring, PRG_RAM_BANK_SIZE, 0);
    }

    /**
     * @param mirroring the header's mirroring, which MMC1 ignores: the control register drives
     *                  the line itself, and every MMC1 game sets it up before it draws anything
     *                  because the power-on value is not defined.
     * @param prgRAMSize how much RAM the header says sits behind $6000, in bytes. Anything up to
     *                   8KB gets the 8KB every board here has; 16KB and 32KB are the two bigger
     *                   boards, and the CHR outputs start switching between their banks.
     * @param submapper the NES 2.0 submapper, of which two mean something here:
     *                  {@link #SUBMAPPER_FIXED_PRG} and {@link #SUBMAPPER_MMC1A}. The retired ones
     *                  that named SUROM, SOROM and SXROM are ignored, since the sizes say the same
     *                  thing and are the only thing a plain iNES header can say.
     */
    @SuppressWarnings("unused")
    public Mapper1(
            final byte[] prgROM,
            final byte[] chrROM,
            final Mirroring mirroring,
            final int prgRAMSize,
            final int submapper) {
        this.prgROM = prgROM;
        this.chrIsRAM = chrROM.length == 0;
        this.chr = chrIsRAM ? new byte[CHR_RAM_SIZE] : chrROM;
        this.prgBankMask = Math.max(1, prgROM.length / PRG_BANK_SIZE) - 1;
        this.chrBankMask = Math.max(1, chr.length / CHR_BANK_SIZE) - 1;

        // One, two or four banks: rounded up to a power of two so that the bank bits can be
        // masked, since a header can say any number and the chips only came in those three.
        var banks = Integer.highestOneBit(
                Math.max(1, Math.min(4, (prgRAMSize + PRG_RAM_BANK_SIZE - 1) / PRG_RAM_BANK_SIZE)) * 2 - 1);
        this.prgRAM = new byte[banks * PRG_RAM_BANK_SIZE];
        this.prgRAMBankMask = banks - 1;
        this.prgRAMBankShift = banks == 4 ? 2 : 3;

        this.chrBitDisablesPRGRAM = chrIsRAM && banks == 1 && prgROM.length <= PRG_ROM_ON_FOUR_BITS;
        this.fixedPRG = submapper == SUBMAPPER_FIXED_PRG;
        this.mmc1a = submapper == SUBMAPPER_MMC1A;
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
        return isPRGRAMEnabled() ? Byte.toUnsignedInt(prgRAM[prgRAMIndex(address)]) : 0;
    }

    @Override
    public void prgRAMWrite(final int address, final int data) {
        // A dropped write leaves what is already in the RAM alone, which is the point of the
        // enable line: a game turns the chip off around anything that might crash so that a
        // battery backed save survives.
        if (isPRGRAMEnabled()) {
            prgRAM[prgRAMIndex(address)] = (byte) data;
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
     * <p>
     * The chip's own four bits pick a bank within 256KB, and "the last bank" means the last of
     * those sixteen. A 512KB board's fifth line comes from a CHR output and sits above all of
     * that, so it moves the fixed bank too: Dragon Warrior IV's vectors are at the top of
     * whichever half is switched in, and the game switches halves with them.
     *
     * @param offset the address, less $8000.
     * @return the bank number, before it is folded through {@link #prgBankMask}.
     */
    private int prgBankFor(final int offset) {
        // 0 for $8000-$BFFF, 1 for $C000-$FFFF.
        var half = offset >> 14;

        if (fixedPRG) {
            return half;
        }

        var bank = prgBank & PRG_BANK_BITS;
        var high = chrOutputs() & CHR_OUT_PRG_ROM_HIGH;

        return high | switch ((control & CONTROL_PRG_MODE) >> 2) {
            // One 32KB bank across the whole window, so the bank number's low bit is ignored and
            // the half of the window supplies it instead.
            case 0, 1 -> (bank & 0x0E) | half;
            // The first bank pinned at $8000, the switchable one above it.
            case 2 -> half == 0 ? 0 : bank;
            // The last bank pinned at $C000, the switchable one below it. Power-on mode.
            default -> half == 0 ? bank : PRG_BANK_BITS;
        };
    }

    /**
     * @param address an address in $6000-$7FFF.
     * @return where in {@link #prgRAM} it lands, given which bank the CHR outputs have selected.
     */
    private int prgRAMIndex(final int address) {
        var bank = ((chrOutputs() & CHR_OUT_PRG_RAM_BANK) >> prgRAMBankShift) & prgRAMBankMask;

        return bank * PRG_RAM_BANK_SIZE + (address & 0x1FFF);
    }

    /**
     * The CHR bank register whose upper bits are on the chip's outputs, and so on the PRG side of
     * the board.
     * <p>
     * In 8KB mode that is CHR bank 0, the only register the chip consults. In 4KB mode the outputs
     * follow whichever register the PPU is fetching through at that instant, which is nothing the
     * CPU can predict, so every game on one of these boards writes the same upper bits into both --
     * NESdev's word is "must" -- and CHR bank 0 is as good an answer as the hardware gives.
     */
    private int chrOutputs() {
        return chrBank0;
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
     * enabled nor which of its banks is in the window is saved separately: all three are read out
     * of {@code control}, {@code prgBank} and {@code chrBank0}, so they arrive with them.
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

    /**
     * Two pins can turn the RAM off, on two different boards. An MMC1B has an enable in the PRG
     * bank register, which an MMC1A has not; SNROM also runs a CHR output to the chip's enable,
     * and a game that sets that bit to bank CHR on a board it thinks is bigger turns its own save
     * RAM off, which is why the bit is only read as an enable where the board is really SNROM.
     */
    private boolean isPRGRAMEnabled() {
        if (!mmc1a && (prgBank & PRG_RAM_DISABLE) != 0) {
            return false;
        }

        return !chrBitDisablesPRGRAM || (chrOutputs() & CHR_OUT_PRG_ROM_HIGH) == 0;
    }

    private void resetShiftRegister() {
        shiftRegister = 0;
        shiftCount = 0;
    }
}
