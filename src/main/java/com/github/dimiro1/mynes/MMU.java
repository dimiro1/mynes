package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.state.StateIO;

/**
 * NES Memory Management Unit (MMU).
 * <p>
 * Memory Map:
 * $0000-$07FF: 2KB internal RAM
 * $0800-$1FFF: Mirrors of $0000-$07FF
 * $2000-$2007: PPU registers
 * $2008-$3FFF: Mirrors of $2000-$2007
 * $4000-$4017: APU and I/O registers
 * $4018-$401F: APU and I/O functionality (usually disabled)
 * $4020-$5FFF: Expansion ROM (rarely used)
 * $6000-$7FFF: Cartridge RAM (mapper controlled)
 * $8000-$FFFF: PRG ROM (mapper controlled)
 */
public class MMU {
    /**
     * How long the DMC's sample fetch holds the CPU off the bus.
     * <p>
     * Four cycles is the usual figure. The hardware has three other answers depending on what the
     * CPU was in the middle of -- three when the fetch was armed by a $4015 write, two when it
     * lands on a write cycle, and so on -- and none of them are audible.
     */
    private static final int DMC_FETCH_CYCLES = 4;

    private final PPU ppu;
    private final APU apu;
    private final Mapper mapper;
    private final Controller controller1;
    private final Controller controller2;

    // Internal RAM: 2KB, mirrored 4 times in $0000-$1FFF
    private final int[] internalRAM = new int[0x0800];

    // Expansion ROM area ($4020-$5FFF) - rarely used
    private final int[] expansionROM = new int[0x1FE0];

    // OAM DMA state
    private boolean dmaInProgress = false;
    private int dmaPage = 0;
    private int dmaAddress = 0;
    private int dmaData = 0;

    // The cycle that halts the CPU. Always spent, and always before anything is transferred.
    private boolean dmaHaltPending = false;

    // An extra idle cycle, spent only when the halt landed on an odd cycle, so that the 256
    // reads all happen on the same phase. This is where the 513 vs 514 cycle difference comes from.
    private boolean dmaAlignPending = false;

    // DMA alternates between reading a byte and writing it to OAM, starting with a read.
    private boolean dmaReadPhase = true;

    /**
     * How many cycles are left of the DMC's own DMA, or zero when there is none in flight.
     * <p>
     * The DMC reads its samples over the same bus, and the CPU is held off it the same way, so the
     * two transfers share the stall seam. This one is four cycles long and does its read on the
     * last of them.
     */
    private int dmcFetchCycles;

    /**
     * Whoever is watching the bus, or null when nobody is -- which is nearly always, and is why this
     * is a reference to check rather than a do-nothing listener always installed. A write happens a
     * couple of hundred thousand times a second; a null check on a field that has been null since
     * power on costs nothing a branch predictor cannot see coming, where a call through an
     * interface would have to be made every time.
     * <p>
     * Not in {@link #serialize}, and named in {@code NOT_IN_THE_STATE}: it belongs to whoever is
     * watching the machine rather than to the machine, the same as the PPU's layer switches.
     */
    private MemoryWriteListener writeListener;

    public MMU(
            final PPU ppu,
            final APU apu,
            final Mapper mapper,
            final Controller controller1,
            final Controller controller2) {
        this.ppu = ppu;
        this.apu = apu;
        this.mapper = mapper;
        this.controller1 = controller1;
        this.controller2 = controller2;
    }

    /**
     * Reads a byte from the specified address.
     */
    public int read(final int address) {
        int addr = address & 0xFFFF;

        // Internal RAM and mirrors ($0000-$1FFF)
        if (addr < 0x2000) {
            return internalRAM[addr & 0x07FF];
        }

        // PPU Registers and mirrors ($2000-$3FFF)
        if (addr < 0x4000) {
            return ppu.read(addr & 0x0007);
        }

        // APU and I/O Registers ($4000-$4017)
        if (addr < 0x4018) {
            return readIORegister(addr);
        }

        // APU and I/O Test Mode ($4018-$401F) - usually disabled
        if (addr < 0x4020) {
            return 0; // Open bus
        }

        // Expansion ROM ($4020-$5FFF)
        if (addr < 0x6000) {
            return expansionROM[addr - 0x4020];
        }

        // Cartridge RAM ($6000-$7FFF) - mapper controlled
        if (addr < 0x8000) {
            return mapper.prgRAMRead(addr);
        }

        // PRG ROM ($8000-$FFFF) - mapper controlled
        return mapper.prgRead(addr);
    }

    /**
     * Reads a byte without the side effects a real read would have.
     * <p>
     * Reading a PPU or controller register is not free -- it advances the controller shift
     * register, clears latches, and so on -- so a debugger or tracer that wants to look at
     * memory has to go around those. Registers read back as zero here rather than as whatever
     * the hardware would have returned.
     */
    public int peek(final int address) {
        int addr = address & 0xFFFF;

        if (addr >= 0x2000 && addr < 0x4020) {
            return 0;
        }

        return read(addr);
    }

    /**
     * Writes a byte to the specified address.
     */
    public void write(final int address, final int data) {
        int addr = address & 0xFFFF;
        int value = data & 0xFF;

        if (writeListener != null) {
            writeListener.onWrite(addr, value);
        }

        // Internal RAM and mirrors ($0000-$1FFF)
        if (addr < 0x2000) {
            internalRAM[addr & 0x07FF] = value;
            return;
        }

        // PPU Registers and mirrors ($2000-$3FFF)
        if (addr < 0x4000) {
            ppu.write(addr & 0x0007, value);
            return;
        }

        // APU and I/O Registers ($4000-$4017)
        if (addr < 0x4018) {
            writeIORegister(addr, value);
            return;
        }

        // APU and I/O Test Mode ($4018-$401F) - usually disabled
        if (addr < 0x4020) {
            return; // Ignore writes
        }

        // Expansion ROM ($4020-$5FFF)
        if (addr < 0x6000) {
            expansionROM[addr - 0x4020] = value;
            return;
        }

        // Cartridge RAM ($6000-$7FFF) - mapper controlled
        if (addr < 0x8000) {
            mapper.prgRAMWrite(addr, value);
            return;
        }

        // PRG ROM ($8000-$FFFF) - mapper controlled
        mapper.prgWrite(addr, value);
    }

    /**
     * Reads from I/O registers ($4000-$4017).
     * <p>
     * $4015 is the only one of the APU's registers that answers a read at all. The rest of the
     * window is write only and reads back as open bus on real hardware, which is approximated
     * here as zero -- nothing this emulator runs depends on the difference, and the ROM that does
     * is a documented non-goal.
     */
    private int readIORegister(int address) {
        return switch (address) {
            case 0x4015 -> apu.readStatus();                             // APU status
            case 0x4016 -> controller1 != null ? controller1.read() : 0; // Controller 1
            case 0x4017 -> controller2 != null ? controller2.read() : 0; // Controller 2
            default -> 0;                                                // Open bus
        };
    }

    /**
     * Writes to I/O registers ($4000-$4017).
     */
    private void writeIORegister(int address, int data) {
        switch (address) {
            case 0x4014 -> {
                // OAM DMA - triggers sprite DMA transfer
                dmaPage = data & 0xFF;
                dmaAddress = 0;
                dmaData = 0;
                dmaHaltPending = true;
                dmaAlignPending = false;
                dmaReadPhase = true;
                dmaInProgress = true;
            }
            case 0x4016 -> {
                // Controller strobe
                if (controller1 != null) {
                    controller1.setStrobe(data & 1);
                }
                if (controller2 != null) {
                    controller2.setStrobe(data & 1);
                }
            }
            // Everything else in the window is the APU's, $4017 included: only its read side
            // belongs to controller 2, and the two share nothing but the address.
            default -> apu.write(address, data);
        }
    }

    /**
     * Performs one cycle of DMA if either transfer is active.
     * <p>
     * A write to $4014 copies a whole 256 byte page into OAM, one byte at a time, with the CPU
     * held off the bus throughout. The cost is one halt cycle, then an alignment cycle if the
     * halt landed on an odd cycle, then 256 read/write pairs: 513 cycles when aligned and 514
     * when not.
     * <p>
     * The DMC's single byte fetch shares the seam and takes priority over it. An OAM transfer
     * caught in the middle simply freezes for the four cycles, which is an approximation: on the
     * hardware the two interleave, and the collision costs about two cycles rather than four. No
     * ROM this project runs measures the difference, and the ones that do -- the
     * {@code sprdma_and_dmc_dma} family -- are not among the goals.
     *
     * @param cpuCycle the current CPU cycle counter; OAM alignment depends on its parity
     * @return true if the CPU must stall this cycle
     */
    public boolean tickDMA(final long cpuCycle) {
        if (dmcFetchCycles > 0 || apu.isDMCFetchPending()) {
            return tickDMCFetch();
        }

        if (!dmaInProgress) {
            return false;
        }

        if (dmaHaltPending) {
            dmaHaltPending = false;
            dmaAlignPending = (cpuCycle & 1) != 0;
            return true;
        }

        if (dmaAlignPending) {
            dmaAlignPending = false;
            return true;
        }

        if (dmaReadPhase) {
            dmaData = read((dmaPage << 8) | dmaAddress);
            dmaReadPhase = false;
            return true;
        }

        ppu.write(0x04, dmaData); // Write to OAMDATA
        dmaReadPhase = true;
        dmaAddress++;

        if (dmaAddress >= 0x100) {
            dmaInProgress = false;
            dmaAddress = 0;
        }

        return true;
    }

    /**
     * One cycle of the DMC's sample fetch, starting it if it is not already going.
     * <p>
     * The read is done here rather than in the APU so that the APU needs no idea of what memory
     * is: it goes through the ordinary read path, mapper and all, so a sample in a switched bank
     * comes from whichever bank is in at that moment.
     *
     * @return true, always: every one of the four cycles is one the CPU is held off the bus.
     */
    private boolean tickDMCFetch() {
        if (dmcFetchCycles == 0) {
            dmcFetchCycles = DMC_FETCH_CYCLES;
        }

        dmcFetchCycles--;

        if (dmcFetchCycles == 0) {
            apu.finishDMCFetch(read(apu.dmcFetchAddress()));
        }

        return true;
    }

    /**
     * Checks if DMA is currently in progress.
     */
    public boolean isDMAInProgress() {
        return dmaInProgress;
    }

    /**
     * Gets the internal RAM array (for testing/debugging).
     */
    public int[] getInternalRAM() {
        return internalRAM;
    }

    /**
     * Watches every byte the CPU writes, or stops watching when given null.
     * <p>
     * One listener rather than a list, because there is one thing that wants this and a list would
     * mean iterating something on the hot path to find it empty.
     *
     * @see MemoryWriteListener
     */
    public void setWriteListener(final MemoryWriteListener listener) {
        this.writeListener = listener;
    }

    /**
     * The work RAM, the expansion window, and a transfer that may be half done.
     * <p>
     * An OAM DMA takes 513 or 514 cycles and a frame boundary can fall inside one, so the transfer's
     * own state is part of the machine: which page, how far through, whether it is still waiting for
     * the halt cycle or the alignment cycle, and which half of the read-write pair comes next.
     */
    public void serialize(final StateIO io) {
        io.bytes(internalRAM);
        io.bytes(expansionROM);

        dmaInProgress = io.bool(dmaInProgress);
        dmaPage = io.u8(dmaPage);
        dmaAddress = io.u16(dmaAddress);
        dmaData = io.u8(dmaData);
        dmaHaltPending = io.bool(dmaHaltPending);
        dmaAlignPending = io.bool(dmaAlignPending);
        dmaReadPhase = io.bool(dmaReadPhase);
        dmcFetchCycles = io.u8(dmcFetchCycles);
    }
}