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
 * $4020-$5FFF: Expansion, which nothing on a cartridge this emulator runs answers to
 * $6000-$7FFF: Cartridge RAM (mapper controlled)
 * $8000-$FFFF: PRG ROM (mapper controlled)
 */
public class MMU {
    private final PPU ppu;
    private final APU apu;
    private final Mapper mapper;
    private final Controller controller1;
    private final Controller controller2;

    // Internal RAM: 2KB, mirrored 4 times in $0000-$1FFF
    private final int[] internalRAM = new int[0x0800];

    /**
     * The last byte anything drove onto the CPU's data bus.
     * <p>
     * The eight data pins are a wire, not a memory, but a wire with enough capacitance to hold its
     * last level for far longer than a cycle. So a read of an address nothing answers to leaves the
     * pins alone and the CPU takes back in whatever was last put out -- which for {@code LDA $5501}
     * is $55, the high byte of the operand it fetched one cycle earlier. That is "open bus", and it
     * is not a curiosity: AccuracyCoin's own timing routine executes from it, and a machine that
     * returns zero here hangs in a loop waiting for a value it will never see.
     * <p>
     * Every driven read and <em>every</em> write refreshes it. Two things do not:
     * <ul>
     *   <li>{@link #peek}, which is not a bus cycle at all.</li>
     *   <li>Reading $4015. Everything that register reports is internal to the 2A03 and reaches the
     *       CPU on a bus of its own, so the external pins keep whatever they held -- and bit 5,
     *       which the register has nothing to say about, reads back off them.</li>
     * </ul>
     */
    private int dataBus;

    // --------------------------------------------------------------------- the transfer engine
    //
    // Two units share one bus with the CPU: the OAM transfer a write to $4014 starts, and the DMC's
    // single byte sample fetch. Either one pulls RDY low, and the CPU is held off the bus until it
    // lets go -- but a 6502 only samples RDY on a read, so the halt lands on the first read cycle
    // after the request and a write in the way delays it.
    //
    // Once halted, every cycle belongs to one of the units or to nobody. The phase decides which:
    // the units can only read on a get cycle and only write on a put one, and a cycle with the
    // wrong phase for the work outstanding is spent idling. Those idle cycles are not free -- the
    // CPU is still driving the address it had reached, so the read it was in the middle of happens
    // again, which is where DMA + $2002, DMA + $2007 and DMA + $4016 all come from.

    /**
     * Which half of the CPU's clock this cycle is.
     * <p>
     * The 2A03 divides its clock in two and gives the transfer units alternate halves: reads happen
     * on one and writes on the other. Which parity is which is a convention rather than a
     * measurement -- nothing observable says whether cycle zero was a get or a put -- but it is
     * <em>this</em> convention, because blargg's {@code 4-jitter} and the OAM transfer's 513 versus
     * 514 cycles are both calibrated against it.
     */
    private static boolean isGetCycle(final long cpuCycle) {
        return (cpuCycle & 1) != 0;
    }

    /**
     * True once RDY has gone low and stayed low: the CPU is off the bus.
     * <p>
     * Distinct from "a transfer is in progress", because the halt cycle itself is spent before it
     * and can be delayed by a write.
     */
    private boolean halted;

    /**
     * The DMC's dummy cycle: one halted cycle it always spends before it starts looking for a get
     * to fetch on. Together with the halt cycle and an alignment cycle when the phase is wrong,
     * this is what makes a sample fetch cost four cycles rather than one.
     */
    private boolean dmcDummyDone;

    /**
     * True while the DMC is waiting for its byte and this engine has taken responsibility for
     * fetching it. Latched from {@link APU#isDMCFetchPending()} so that the transfer survives the
     * APU changing its mind part way through -- and so that an abort has something to clear.
     */
    private boolean dmcFetching;

    /**
     * The DMC asked for the bus during the previous cycle.
     * <p>
     * Its sample buffer runs dry part way through a cycle, by which time that cycle's fate is
     * already settled, so the request cannot be acted on until the next one. One cycle sounds like
     * nothing and is not: a fetch that starts a cycle later starts on the other half of the clock,
     * which is the difference between a four cycle transfer and a three cycle one, and
     * AccuracyCoin's timing routines are built on the four.
     */
    private boolean dmcRequested;

    /**
     * Which controller port the previous bus cycle read, or 0 if it read anything else. What the
     * consecutive-read rule in {@link #readPort} is measured against.
     */
    private int lastPortRead;

    /**
     * A strobe level written to $4016 and not yet handed to the controllers, or -1 when there is
     * none waiting.
     * <p>
     * The port latches the value on the transition from a get cycle to a put one rather than at the
     * moment of the write, so a write lands either one cycle later or two depending on which half
     * of the clock it happened in. Nothing a game does can tell the difference; a test ROM counting
     * cycles can.
     */
    private int pendingStrobe = -1;

    // OAM transfer state
    private boolean dmaInProgress = false;
    private int dmaPage = 0;
    private int dmaAddress = 0;
    private int dmaData = 0;

    // The OAM transfer alternates between reading a byte and writing it to OAM, starting with a
    // read. Whichever comes next waits for a cycle of the right phase.
    private boolean dmaReadPhase = true;

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
     * Reads a byte from the specified address, as a bus cycle: whatever is driven onto the pins
     * stays on them.
     */
    public int read(final int address) {
        int addr = address & 0xFFFF;

        if (addr < 0x4016 || addr > 0x4017) {
            lastPortRead = 0;
        }

        // Internal RAM and mirrors ($0000-$1FFF)
        if (addr < 0x2000) {
            return dataBus = internalRAM[addr & 0x07FF];
        }

        // PPU Registers and mirrors ($2000-$3FFF)
        if (addr < 0x4000) {
            return dataBus = ppu.read(addr & 0x0007);
        }

        // APU and I/O Registers ($4000-$401F), most of which are write only
        if (addr < 0x4020) {
            return readIORegister(addr);
        }

        // Expansion ($4020-$5FFF): nothing on the cartridge answers, so the pins float
        if (addr < 0x6000) {
            return dataBus;
        }

        // Cartridge RAM ($6000-$7FFF) - mapper controlled
        if (addr < 0x8000) {
            return dataBus = mapper.prgRAMRead(addr);
        }

        // PRG ROM ($8000-$FFFF) - mapper controlled
        return dataBus = mapper.prgRead(addr);
    }

    /**
     * Reads a byte without the side effects a real read would have.
     * <p>
     * Reading a PPU or controller register is not free -- it advances the controller shift
     * register, clears latches, and so on -- so a debugger or tracer that wants to look at
     * memory has to go around those. Registers read back as zero here rather than as whatever
     * the hardware would have returned.
     * <p>
     * Unmapped space reads back as {@link #dataBus}, which is what the CPU would find there. That
     * is an observation rather than a bus cycle: looking does not refresh it.
     */
    public int peek(final int address) {
        int addr = address & 0xFFFF;

        if (addr < 0x2000) {
            return internalRAM[addr & 0x07FF];
        }

        if (addr < 0x4020) {
            return 0;
        }

        if (addr < 0x6000) {
            return dataBus;
        }

        if (addr < 0x8000) {
            return mapper.prgRAMRead(addr);
        }

        return mapper.prgRead(addr);
    }

    /**
     * Writes a byte to the specified address.
     * <p>
     * A write drives the pins whether or not anything is listening, so the data bus is refreshed
     * before the destination is even decoded -- including for $4015, which has nothing to say when
     * read but is an ordinary write like any other.
     */
    public void write(final int address, final int data) {
        int addr = address & 0xFFFF;
        int value = data & 0xFF;

        dataBus = value;
        lastPortRead = 0;

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

        // APU and I/O Test Mode ($4018-$401F), and the expansion window ($4020-$5FFF): nothing
        // there is listening
        if (addr < 0x6000) {
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
     * Reads from I/O registers ($4000-$401F).
     * <p>
     * Three of the thirty-two answer at all, and each answers differently.
     * <p>
     * $4015 reports the APU's own state over the chip's internal bus and leaves the external pins
     * alone, so it neither refreshes {@link #dataBus} nor supplies bit 5: that bit belongs to no
     * counter and comes straight back off the floating pins.
     * <p>
     * $4016 and $4017 drive only what the port carries. On a front-loader that is one bit -- the
     * serial line from the controller -- with bits 1 to 4 pulled low by the port itself and the top
     * three left floating, which is why {@code LDA $4016} reads $40 plus the button: $40 is the high
     * byte of its own operand, still on the pins from the cycle before.
     * <p>
     * Everything else in the window is write only, and reading one leaves the pins where they were.
     */
    private int readIORegister(int address) {
        return switch (address) {
            case 0x4015 -> (apu.readStatus() & 0xDF) | (dataBus & 0x20);
            case 0x4016 -> dataBus = openBusHighBits() | readPort(controller1, 0x4016);
            case 0x4017 -> dataBus = openBusHighBits() | readPort(controller2, 0x4017);
            default -> dataBus;
        };
    }

    /**
     * One bit out of a controller port, clocking its shift register on the way -- but only if the
     * cycle before this one was not a read of the same port.
     * <p>
     * The port latches on the falling edge of the read strobe, and two reads back to back leave it
     * low throughout, so the second one finds no edge to clock on and sees the bit the first did.
     * Nothing a program writes by hand does that, but a transfer does: a halted CPU re-issues its
     * read every cycle, and a {@code LDA $4016} caught by one would otherwise clock the controller
     * three or four times over for a single instruction.
     */
    private int readPort(final Controller controller, final int address) {
        var again = lastPortRead == address;

        lastPortRead = address;

        if (controller == null) {
            return 0;
        }

        return again ? controller.peek() : controller.read();
    }

    /**
     * The three bits of a controller port read that nothing on the port drives.
     */
    private int openBusHighBits() {
        return dataBus & 0xE0;
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
                dmaReadPhase = true;
                dmaInProgress = true;
            }
            // The strobe is latched on the next get-to-put transition rather than now.
            case 0x4016 -> pendingStrobe = data & 1;
            // Everything else in the window is the APU's, $4017 included: only its read side
            // belongs to controller 2, and the two share nothing but the address.
            default -> apu.write(address, data);
        }
    }

    /**
     * Decides what one CPU cycle is for, and spends it if a transfer can use it.
     * <p>
     * A write to $4014 copies a whole page into OAM, and the DMC fetches one byte of its sample
     * whenever its buffer runs dry. Both borrow the CPU's bus, and both go through the same three
     * stages:
     * <ol>
     *   <li><b>The halt.</b> RDY goes low, but a 6502 only looks at it on a read, so the halt lands
     *       on the first read cycle after the request and a write in the way pushes it back one.
     *       Reported as {@link CPUBus.DMACycle#HALT}, and {@link #endHaltCycle} says which it
     *       turned out to be.</li>
     *   <li><b>The DMC's dummy cycle</b>, if it is the one being served. Always spent, never
     *       useful.</li>
     *   <li><b>The transfer.</b> A unit can only read on a get cycle and only write on a put one,
     *       so a cycle whose phase does not suit the work outstanding is spent idling.</li>
     * </ol>
     * That last rule is the whole of the alignment: an OAM transfer whose first read has to wait a
     * cycle for a get is the 514 cycle case, and one that does not is 513.
     * <p>
     * The DMC wins any get cycle it wants, because it cannot be made to wait -- its sample buffer
     * empties on a schedule the CPU has no say in. An OAM transfer caught by one keeps its put
     * cycles throughout and loses a single get, then pays one idle cycle getting back in phase, so
     * the collision costs two cycles rather than four.
     *
     * @param cpuCycle the current CPU cycle counter, whose parity is the get/put phase.
     * @return what the CPU should do with this cycle.
     */
    public CPUBus.DMACycle beginDMACycle(final long cpuCycle) {
        if (pendingStrobe >= 0 && !isGetCycle(cpuCycle)) {
            if (controller1 != null) {
                controller1.setStrobe(pendingStrobe);
            }
            if (controller2 != null) {
                controller2.setStrobe(pendingStrobe);
            }

            pendingStrobe = -1;
        }

        if (dmcRequested && !dmcFetching) {
            dmcFetching = true;
            dmcRequested = false;
        } else if (!dmcFetching) {
            // Only asked while there is nothing in flight: the DMC goes on saying it wants a byte
            // for the whole of the transfer that is fetching it, and taking that as a fresh request
            // would fetch every sample twice.
            dmcRequested = apu.isDMCFetchPending();
        }

        if (!dmcFetching && !dmaInProgress) {
            halted = false;
            return CPUBus.DMACycle.NONE;
        }

        // Nobody has the bus until the halt has landed, and the halt cannot land on a write.
        if (!halted) {
            return CPUBus.DMACycle.HALT;
        }

        if (dmcFetching) {
            if (!dmcDummyDone) {
                // Spent whatever else is going on: an OAM transfer underneath carries on using it.
                dmcDummyDone = true;
            } else if (isGetCycle(cpuCycle)) {
                apu.finishDMCFetch(read(apu.dmcFetchAddress()));
                dmcFetching = false;
                dmcDummyDone = false;

                return CPUBus.DMACycle.TRANSFER;
            }
        }

        if (dmaInProgress) {
            if (dmaReadPhase && isGetCycle(cpuCycle)) {
                dmaData = read((dmaPage << 8) | dmaAddress);
                dmaReadPhase = false;

                return CPUBus.DMACycle.TRANSFER;
            }

            if (!dmaReadPhase && !isGetCycle(cpuCycle)) {
                ppu.write(0x04, dmaData); // Write to OAMDATA
                dmaReadPhase = true;
                dmaAddress++;

                if (dmaAddress >= 0x100) {
                    dmaInProgress = false;
                    dmaAddress = 0;
                }

                return CPUBus.DMACycle.TRANSFER;
            }
        }

        // Nothing could use the cycle. The CPU is still driving its address, so its read happens
        // again -- which is what the alignment and dummy cycles are observably for.
        return CPUBus.DMACycle.HALT;
    }

    /**
     * Takes the answer to the question {@link CPUBus.DMACycle#HALT} asked.
     *
     * @param cpuWrote true if the CPU spent the cycle writing, which delays the halt by one cycle.
     */
    public void endHaltCycle(final boolean cpuWrote) {
        if (!cpuWrote) {
            halted = true;
        }
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
     * The work RAM, a transfer that may be half done, and what is on the data pins.
     * <p>
     * An OAM DMA takes 513 or 514 cycles and a frame boundary can fall inside one, so the transfer's
     * own state is part of the machine: which page, how far through, whether it is still waiting for
     * the halt cycle or the alignment cycle, and which half of the read-write pair comes next.
     * <p>
     * The hole after the work RAM is the 8KB expansion array this used to keep, back when $4020-$5FFF
     * was writable memory rather than open bus. It is written as zeroes so that a state from before
     * that change still lines up field for field; see {@link StateIO#skip}.
     */
    public void serialize(final StateIO io) {
        io.bytes(internalRAM);
        io.skip(0x1FE0);

        dmaInProgress = io.bool(dmaInProgress);
        dmaPage = io.u8(dmaPage);
        dmaAddress = io.u16(dmaAddress);
        dmaData = io.u8(dmaData);

        // Two more holes: the halt and alignment flags the OAM transfer used to keep, before both
        // became a question about the cycle's phase rather than about a countdown.
        io.skip(2);

        dmaReadPhase = io.bool(dmaReadPhase);

        // And a third, where the DMC's four cycle countdown was.
        io.skip(1);

        dataBus = io.u8(dataBus);
        halted = io.bool(halted);
        dmcFetching = io.bool(dmcFetching);
        dmcDummyDone = io.bool(dmcDummyDone);
        dmcRequested = io.bool(dmcRequested);
        lastPortRead = io.u16(lastPortRead);
        pendingStrobe = io.u8(pendingStrobe + 1) - 1;
    }
}