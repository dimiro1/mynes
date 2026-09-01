package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.cheat.GameGenie;
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
     * The last byte anything drove onto the 2A03's data <em>pins</em>.
     * <p>
     * The eight data pins are a wire, not a memory, but a wire with enough capacitance to hold its
     * last level for far longer than a cycle. So a read of an address nothing answers to leaves the
     * pins alone and the CPU takes back in whatever was last put out -- which for {@code LDA $5501}
     * is $55, the high byte of the operand it fetched one cycle earlier. That is "open bus", and it
     * is not a curiosity: AccuracyCoin's own timing routine executes from it, and a machine that
     * returns zero here hangs in a loop waiting for a value it will never see.
     * <p>
     * Every driven read and <em>every</em> write refreshes it, whoever made it -- a transfer's read
     * counts, which is what {@code DMA + Open Bus} measures. Two things do not:
     * <ul>
     *   <li>{@link #peek}, which is not a bus cycle at all.</li>
     *   <li>Reading $4015, which never reaches these pins. See {@link #internalDataBus}.</li>
     * </ul>
     */
    private int externalDataBus;

    /**
     * The last byte on the bus the 6502 core sits on, inside the 2A03.
     * <p>
     * There are two of these buses and a buffer between them, and the difference is measurable in
     * both directions -- AccuracyCoin's {@code Internal Data Bus} takes one measurement each way:
     * <ul>
     *   <li><b>Outwards.</b> $4015 reports over this bus and never opens the buffer, so a read of
     *       it leaves the pins holding whatever they held. Read $4015 and then read open bus, and
     *       what comes back is the byte from before the $4015 read, not the status.</li>
     *   <li><b>Inwards.</b> A transfer's read drives the pins but not this bus, because the core is
     *       off it. So bit 5 of a $4015 read -- the one bit the register has nothing to say about,
     *       and so the one that comes off the floating lines -- shows what the <em>CPU</em> last
     *       saw, and a DMC sample fetched a cycle earlier cannot put it there.</li>
     * </ul>
     * Every other read and every write refreshes both, so on a machine that never touches $4015 the
     * two hold the same byte and only one of them need have existed.
     */
    private int internalDataBus;

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
     * True once RDY has gone low and stayed low: the CPU is off the bus.
     * <p>
     * Distinct from "a transfer is in progress", because the halt cycle itself is spent before it
     * and can be delayed by a write.
     */
    private boolean halted;

    /**
     * How many of its own two cycles the DMC has spent before it starts looking for a get to fetch
     * on: its halt cycle and its dummy cycle. With an alignment cycle when the phase is wrong,
     * that is what makes a sample fetch cost four cycles rather than one.
     * <p>
     * A count rather than the single flag it was, because <em>both</em> have to be spent even when
     * an OAM transfer already has the bus. The halt looks like nothing then -- RDY is low already,
     * and the transfer underneath carries on using the cycle -- but the DMC is still counting it,
     * and collapsing the two put its fetch a cycle early against the answer key AccuracyCoin
     * measures the two transfers' interleaving with.
     */
    private int dmcPrepared;

    /**
     * How many cycles that is.
     */
    private static final int DMC_PREPARATION_CYCLES = 2;

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
     * Cycles until an aborted DMA tries to halt the CPU, or 0 when none is owed. See
     * {@link #scheduleAbortedDMA}.
     */
    private int dmcAbortDelay;

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

    /**
     * Whoever is watching the bus the other way round, or null when nobody is -- which is nearly
     * always, and is why this is a reference to check rather than a do-nothing listener always
     * installed. The same trade as {@link #writeListener} against a much hotter line: every
     * instruction the CPU fetches comes past here, so a call through an interface would be made
     * around one and a half million times a second where a null check on a field that has been null
     * since power on costs nothing a branch predictor cannot see coming.
     * <p>
     * Not in {@link #serialize}, and named in {@code NOT_IN_THE_STATE}, for the reason the write
     * listener is not.
     */
    private MemoryReadListener readListener;

    /**
     * The Game Genie plugged in between the cartridge and the console, or null when there is none --
     * which, as with {@link #writeListener}, is nearly always, and is why this is a reference to check
     * rather than a do-nothing device always installed. Every instruction the CPU fetches comes
     * through the branch that reads it.
     * <p>
     * <strong>Null rather than always present, for a second reason worth knowing.</strong>
     * {@code SaveStateCompletenessTests} walks the console reflectively and vandalises every primitive
     * array it can reach; a field holding null is recorded and stepped over, where one holding a
     * device would be walked into and every table inside it scrambled. So a nullable field is what
     * keeps a cheat device out of the save state without owing the exclusion list an entry per field.
     * <p>
     * Not in {@link #serialize}, and named in {@code NOT_IN_THE_STATE}: a code belongs to whoever is
     * playing rather than to the machine, the same as the PPU's layer switches.
     */
    private GameGenie genie;

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
     * <p>
     * The byte the processor ends up with is by definition the one on the bus it sits on, so this
     * is the one place {@link #internalDataBus} is refreshed by a read. A transfer's read goes
     * through {@link #transferRead} instead and leaves it alone, which is the whole difference
     * between the two buses.
     */
    public int read(final int address) {
        cpuAddress = address & 0xFFFF;
        internalDataBus = busRead(cpuAddress);

        // Afterwards rather than before, unlike the write hook: there is nothing to report until
        // the read has happened. See MemoryReadListener, which is where the consequences of that
        // are written down.
        if (readListener != null) {
            readListener.onRead(cpuAddress, internalDataBus);
        }

        return internalDataBus;
    }

    /**
     * The decode itself, without the note of where the processor's address bus is. Everything the
     * CPU reads comes through {@link #read}; a transfer's read comes through
     * {@link #transferRead}, which needs the decode without the note because it is not the
     * processor doing the reading.
     */
    private int busRead(final int address) {
        int addr = address & 0xFFFF;

        if (addr < 0x4016 || addr > 0x4017) {
            lastPortRead = 0;
        }

        // Internal RAM and mirrors ($0000-$1FFF)
        if (addr < 0x2000) {
            return externalDataBus = internalRAM[addr & 0x07FF];
        }

        // PPU Registers and mirrors ($2000-$3FFF)
        if (addr < 0x4000) {
            return externalDataBus = ppu.read(addr & 0x0007);
        }

        // APU and I/O Registers ($4000-$401F), most of which are write only
        if (addr < 0x4020) {
            return readIORegister(addr);
        }

        // Expansion ($4020-$5FFF): nothing on the cartridge answers, so the pins float
        if (addr < 0x6000) {
            return externalDataBus;
        }

        // Cartridge RAM ($6000-$7FFF) - mapper controlled
        if (addr < 0x8000) {
            return externalDataBus = mapper.prgRAMRead(addr);
        }

        // PRG ROM ($8000-$FFFF) - mapper controlled, and the one window a Game Genie can reach.
        //
        // The substituted byte is what lands on the pins, because it is the Genie driving them and not
        // the cartridge: open bus keeps what was last put out, and AccuracyCoin executes from it.
        // Here rather than in read(), so that an OAM transfer out of a PRG page and a DMC sample fetch
        // both see it -- the device sits on /ROMSEL and has no idea which unit is driving the address.
        var fromTheCartridge = mapper.prgRead(addr);

        return externalDataBus = genie == null
                ? fromTheCartridge
                : genie.substitute(addr, fromTheCartridge);
    }

    /**
     * Reads a byte without the side effects a real read would have.
     * <p>
     * Reading a PPU or controller register is not free -- it advances the controller shift
     * register, clears latches, and so on -- so a debugger or tracer that wants to look at
     * memory has to go around those. Registers read back as zero here rather than as whatever
     * the hardware would have returned.
     * <p>
     * Unmapped space reads back as {@link #externalDataBus}, which is what the CPU would find
     * there. That is an observation rather than a bus cycle: looking does not refresh it.
     * <p>
     * A Game Genie's substitutions <em>are</em> shown, because the device is on the console side of
     * the cartridge and its byte is the one the CPU executes -- a disassembly that showed the
     * cartridge's would be a listing of instructions that never run. The consequence is that there is
     * no way to read the cartridge underneath through this: {@code Cart.prgROM} is where it is.
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
            return externalDataBus;
        }

        if (addr < 0x8000) {
            return mapper.prgRAMRead(addr);
        }

        // Spelled out rather than shared with busRead, which drives the pins on its way past. Looking
        // is not a bus cycle and must not refresh them.
        var fromTheCartridge = mapper.prgRead(addr);

        return genie == null ? fromTheCartridge : genie.substitute(addr, fromTheCartridge);
    }

    /**
     * Writes a byte to the specified address.
     * <p>
     * A write drives both buses whether or not anything is listening, so they are refreshed before
     * the destination is even decoded -- including for $4015, which has nothing to say when read
     * but is an ordinary write like any other, and drives the pins like one.
     */
    public void write(final int address, final int data) {
        int addr = address & 0xFFFF;
        int value = data & 0xFF;

        cpuAddress = addr;
        externalDataBus = value;
        internalDataBus = value;
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
     * alone, so it neither refreshes {@link #externalDataBus} nor supplies bit 5: that bit belongs
     * to no counter and comes back off the floating lines of {@link #internalDataBus}, which is the
     * bus it is on.
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
            case 0x4015 -> (apu.readStatus() & 0xDF) | (internalDataBus & 0x20);
            case 0x4016 -> externalDataBus = openBusHighBits() | readPort(controller1, 0x4016);
            case 0x4017 -> externalDataBus = openBusHighBits() | readPort(controller2, 0x4017);
            default -> externalDataBus;
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
        return externalDataBus & 0xE0;
    }

    // ------------------------------------------------------------------ whose address bus it is
    //
    // There are three address buses inside the 2A03 -- the processor's, the DMC's and the OAM
    // transfer's -- and only one of them reaches the pins on any given cycle. The APU's registers
    // are not decoded from the pins, though. They are decoded from *the processor's* bus, and they
    // answer only while it is somewhere in $4000-$401F.
    //
    // Two things follow, and AccuracyCoin measures both. An OAM transfer copying page $40 reads
    // open bus rather than the registers, because the processor that asked for it is off fetching
    // in the cartridge. And when the processor *is* in the window -- halted on a read of one of
    // them, say -- the registers answer at every address, because the decode is only five bits
    // wide: they are mirrored every $20 bytes across the whole map, on top of whatever memory the
    // full address selects.

    /**
     * The address the 6502 last put on its own address bus, which is where it still is: a halted
     * processor holds its address until RDY comes back.
     */
    private int cpuAddress;

    private boolean apuRegistersActive() {
        return cpuAddress >= 0x4000 && cpuAddress < 0x4020;
    }

    /**
     * A read made by one of the transfer units rather than by the processor.
     *
     * @param address what the unit put on the pins.
     * @return the byte that came back, which is not always the byte at that address.
     */
    private int transferRead(final int address) {
        final var addr = address & 0xFFFF;
        final var inWindow = addr >= 0x4000 && addr < 0x4020;

        if (!apuRegistersActive()) {
            // The registers are switched off, so the window has nothing behind it at all and the
            // pins keep what they had.
            return inWindow ? externalDataBus : busRead(addr);
        }

        if (inWindow) {
            return busRead(addr);
        }

        final var register = 0x4000 | (addr & 0x1F);

        // Whether a port is selected is settled by this address's bottom five bits, not by the
        // whole of it -- so a fetch whose mirror lands on the same port a halted CPU is already
        // reading does not break the run of reads, and the port is clocked once for the whole
        // instruction rather than twice. One whose mirror lands anywhere else deselects it, and
        // the instruction's own read afterwards is a fresh one. The read of the memory underneath
        // knows nothing about either and must not be allowed to decide.
        final var selected = lastPortRead;
        final var memory = busRead(addr);
        lastPortRead = register == 0x4016 || register == 0x4017 ? selected : 0;

        return conflict(addr, memory, register);
    }

    /**
     * Whether anything at this address drives the data lines at all. The window between the
     * registers and the cartridge is the one stretch of the map where nothing does, which is what
     * makes it read back as open bus.
     */
    private static boolean drivesTheBus(final int addr) {
        return addr < 0x4020 || addr >= 0x6000;
    }

    /**
     * What the unit takes away when a mirrored register and the memory at the full address answer
     * the same read.
     * <p>
     * Not a collision so much as a sharing out, because none of the three readable registers
     * drives all eight lines. A controller port drives the bottom five and leaves the top three to
     * whatever else is on the bus, and it wins them outright -- a sample byte of $FF read from a
     * $4016 mirror comes back as $E1: the cartridge's top three bits, the port's four low zeroes,
     * and the button.
     * <p>
     * $4015 wins its seven bits the same way, with bit 5 -- the one it has nothing to say about --
     * coming off whatever else is on the lines. What is different is where the answer goes. The
     * register is on the chip's own bus, so it is the <em>transfer</em> that always gets the
     * composite, while the pins only take it where nothing else was driving them. Read a $4015
     * mirror out of memory and the transfer is handed the status byte while the pins keep the
     * memory's: AccuracyCoin measures both ends of that on the same cycle, finding $24 in OAM
     * where page 2 held $FF, and the sample byte still on the pins a cycle after a DMC fetch.
     * <p>
     * Either way the read happens, and acknowledges the frame counter's interrupt on the way past.
     *
     * @param addr     what the unit put on the pins.
     * @param memory   what that address selected.
     * @param register which of $4000-$401F its bottom five bits also selected.
     */
    private int conflict(final int addr, final int memory, final int register) {
        return switch (register) {
            case 0x4015 -> {
                final var status = (apu.readStatus() & 0xDF) | (memory & 0x20);

                yield drivesTheBus(addr) ? status : (externalDataBus = status);
            }
            case 0x4016 -> externalDataBus = (memory & 0xE0) | readPort(controller1, 0x4016);
            case 0x4017 -> externalDataBus = (memory & 0xE0) | readPort(controller2, 0x4017);
            default -> memory;
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
                dmaReadPhase = true;
                dmaInProgress = true;
            }
            // The strobe is latched on the next get-to-put transition rather than now.
            case 0x4016 -> pendingStrobe = data & 1;
            case 0x4015 -> {
                // Asked before the write, because the write is what takes the sample away.
                var imminent = apu.isDMCReloadImminent();

                apu.write(address, data);

                if (!apu.isDMCSampleActive() && imminent) {
                    scheduleAbortedDMA();
                }
            }
            // Everything else in the window is the APU's, $4017 included: only its read side
            // belongs to controller 2, and the two share nothing but the address.
            default -> apu.write(address, data);
        }
    }

    /**
     * Hands the controllers a strobe written a cycle or two ago, if this is the cycle it lands on.
     * <p>
     * Nothing to do with the transfer engine, and here only because {@link #beginDMACycle} is the
     * one thing on the CPU's path that is told the cycle counter -- and so the one place that knows
     * which half of the clock this is. See {@link #pendingStrobe}.
     */
    private void settleControllerStrobe(final long cpuCycle) {
        if (pendingStrobe < 0 || CPUBus.isGetCycle(cpuCycle)) {
            return;
        }

        if (controller1 != null) {
            controller1.setStrobe(pendingStrobe);
        }

        if (controller2 != null) {
            controller2.setStrobe(pendingStrobe);
        }

        pendingStrobe = -1;
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
        settleControllerStrobe(cpuCycle);

        var aborting = dmcAbortDelay > 0 && --dmcAbortDelay == 0;

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

            // An aborted DMA starts and then stops: it spends its halt cycle and nothing else,
            // with no dummy, no alignment and no read. See scheduleAbortedDMA.
            return aborting ? CPUBus.DMACycle.HALT : CPUBus.DMACycle.NONE;
        }

        // Nobody has the bus until the halt has landed, and the halt cannot land on a write.
        if (!halted) {
            return CPUBus.DMACycle.HALT;
        }

        if (dmcFetching) {
            if (dmcPrepared < DMC_PREPARATION_CYCLES) {
                // Spent whatever else is going on: an OAM transfer underneath carries on using it.
                dmcPrepared++;
            } else if (CPUBus.isGetCycle(cpuCycle)) {
                var stopped = apu.finishDMCFetch(transferRead(apu.dmcFetchAddress()));
                dmcFetching = false;
                dmcPrepared = 0;

                if (stopped) {
                    scheduleAbortedDMA();
                }

                return CPUBus.DMACycle.TRANSFER;
            }
        }

        if (dmaInProgress) {
            if (dmaReadPhase && CPUBus.isGetCycle(cpuCycle)) {
                dmaData = transferRead((dmaPage << 8) | dmaAddress);
                dmaReadPhase = false;

                return CPUBus.DMACycle.TRANSFER;
            }

            if (!dmaReadPhase && !CPUBus.isGetCycle(cpuCycle)) {
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
     * Arranges the one cycle a fetch costs when it is called off before it can begin.
     * <p>
     * Playback stopping in the APU cycle before the DMC would have asked for its next byte is too
     * late to stop the transfer being scheduled and too early for it to happen: it starts, spends
     * its halt cycle, and gives up -- no dummy cycle, no alignment, no read. AccuracyCoin's
     * Explicit DMA Abort walks a $4015 write across that window a cycle at a time and records what
     * each position costs the CPU: 4, 4, 4, 4, 4, 4, 3, 4, 1, 1, 0, 0, 0, 0, 0, 0. Reading it from
     * the end backwards, which is the write landing later and later,
     * <ul>
     *   <li>too early, and nothing happens at all;</li>
     *   <li>inside the window, and the fetch is called off for the price of one cycle -- the 1s;</li>
     *   <li>too late, and the fetch runs in full -- the 4s. Once the DMC has asked, nothing stops
     *       it, so the byte is read and thrown away.</li>
     * </ul>
     * The 3 in that list is not an abort. It is a write landing on the halt cycle of a fetch
     * already under way, which a 6502 does not sample RDY on, so the halt is one cycle later and
     * the transfer that follows no longer needs its alignment cycle.
     * <p>
     * The same aborted DMA is scheduled however the sample stopped -- by hand, or by a one byte
     * sample running out, which is what Implicit DMA Abort measures.
     * <p>
     * A transfer the emptying buffer asks for halts on a put cycle, so the halt attempt is the
     * first put two or more cycles after the stop: three cycles when the stop was on a get, two
     * when it was on a put. If the CPU turns out to be writing then, the abort does not happen at
     * all -- the halt is not retried, because there is nothing left to halt for.
     *
     * @see <a href="https://www.nesdev.org/wiki/DMA#Bugs">DMC DMA bugs</a>
     */
    private void scheduleAbortedDMA() {
        dmcAbortDelay = apu.isGetCycleNow() ? 3 : 2;
    }

    /**
     * Takes the answer to the question {@link CPUBus.DMACycle#HALT} asked.
     *
     * @param cpuWrote true if the CPU spent the cycle writing, which delays the halt by one cycle.
     */
    public void endHaltCycle(final boolean cpuWrote) {
        if (cpuWrote) {
            return;
        }

        // The cycle RDY goes low is the first of the DMC's own two, when the DMC is what pulled it
        // down. It is the same cycle for both units if they halt together, which is what a sample
        // fetch requested on an OAM transfer's halt cycle does.
        if (!halted && dmcFetching && dmcPrepared < DMC_PREPARATION_CYCLES) {
            dmcPrepared++;
        }

        halted = true;
    }

    public boolean isDMAInProgress() {
        return dmaInProgress;
    }

    /**
     * The console's own 2KB, as the live array rather than a copy -- what {@code --dump ram} writes
     * out, and the one memory a front end may read without going through the bus at all.
     */
    public int[] getInternalRAM() {
        return internalRAM;
    }

    /**
     * Plugs a Game Genie in between the cartridge and the console, or unplugs one when given null.
     * <p>
     * Null when it holds no codes rather than merely empty, which is what keeps every read of PRG ROM
     * on a machine nobody is cheating on down to a null check. {@link GameGenie} does that itself as
     * its first code arrives and its last one goes, so nothing outside has to remember to.
     *
     * @see GameGenie
     */
    public void setGameGenie(final GameGenie genie) {
        this.genie = genie;
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
     * Watches every byte the CPU reads, or stops watching when given null.
     * <p>
     * One listener rather than a list, for the reason the write side keeps one -- and here the hot
     * path is hotter still.
     *
     * @see MemoryReadListener
     */
    public void setReadListener(final MemoryReadListener listener) {
        this.readListener = listener;
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

        externalDataBus = io.u8(externalDataBus);
        halted = io.bool(halted);
        dmcFetching = io.bool(dmcFetching);
        dmcPrepared = io.u8(dmcPrepared);
        dmcRequested = io.bool(dmcRequested);
        dmcAbortDelay = io.u8(dmcAbortDelay);
        lastPortRead = io.u16(lastPortRead);
        pendingStrobe = io.u8(pendingStrobe + 1) - 1;
        cpuAddress = io.u16(cpuAddress);
        internalDataBus = io.u8(internalDataBus);
    }
}