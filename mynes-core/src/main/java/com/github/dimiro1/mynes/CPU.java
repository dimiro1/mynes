package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.state.StateIO;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU implements the R2A07 CPU found in the NES video game console.
 *
 * <ul>
 *   <li><a href="http://nesdev.com/6502_cpu.txt">http://nesdev.com/6502_cpu.txt</a>
 *   <li><a href="http://www.oxyron.de/html/opcodes02.html">http://www.oxyron.de/html/opcodes02.html</a>
 * </ul>
 */
public class CPU {

    /**
     * The constant the accumulator is OR'd with by the unstable immediate opcodes XAA ($8B) and
     * LXA ($AB). Real hardware has no single answer here -- the value depends on the chip and
     * even on its temperature -- so this matches the reference implementation the Tom Harte test
     * set was generated from.
     */
    private static final int UNSTABLE_MAGIC = 0xEE;

    private static final int NMI_VECTOR = 0xFFFA;
    private static final int RST_VECTOR = 0xFFFC;
    private static final int IRQ_VECTOR = 0xFFFE;

    private int a, x, y, sp, pc, p;
    private long cycles;
    private int tick, intTick, tickValue, tickBaseAddress, tickUnfixedAddress, tickAddress, tickLow, tickHigh;
    private int opcode;

    private final CPUBus bus;
    private final List<CPUEventListener> listeners = new ArrayList<>();

    /**
     * Reset is a one-shot request: there is no line for a device to keep holding, so the flag is
     * cleared as soon as the reset sequence starts. Set at construction so a fresh CPU boots
     * through the reset vector.
     */
    private boolean rstPending = true;

    /**
     * NMI is edge triggered: the falling edge arms this latch and only the vector fetch of the
     * sequence that services it disarms the latch again. That is what stops one edge from being
     * serviced twice, and what lets an NMI raised during an IRQ or BRK sequence take over its
     * vector fetch.
     */
    private boolean nmiPending;

    /**
     * The current level of the /NMI line, as the last device to drive it left it.
     * <p>
     * This is the line itself, not a request: {@link #nmiPending} is the latch, and it is armed
     * by the edge between two consecutive samples of this field, never by the level.
     */
    private boolean nmiLine;

    /**
     * What {@link #nmiLine} read the last time the CPU looked at it, which is what an edge is
     * measured against.
     */
    private boolean nmiLineLastSample;

    /**
     * IRQ is level triggered: the device holds the line and the CPU keeps seeing it on every
     * poll until the device lets go. Nothing here latches it, so an IRQ that is masked when it
     * is polled is still there to be serviced once the I flag clears.
     */
    private boolean irqLine;

    /**
     * Set by a poll that saw a serviceable interrupt, cleared by the instruction boundary that
     * acts on it. Being sticky is what makes the timing work: the poll happens on the last cycle
     * of an instruction but the sequence cannot start until the instruction has finished.
     */
    private boolean interruptPending;

    /**
     * The interrupt sequence currently executing, if any.
     */
    private Sequence sequence = Sequence.NONE;

    /**
     * The vector an interrupt sequence (or BRK) settled on. Chosen at the cycle the low byte is
     * fetched, not when the sequence starts.
     */
    private int interruptVector;

    /**
     * True when the last cycle was spent held off the bus by a DMA transfer rather than doing
     * any work of the CPU's own.
     */
    private boolean stalled;

    /**
     * True while a cycle is being run only to find out what it does.
     * <p>
     * A halted CPU keeps driving the address it had reached and reads it again every cycle, so the
     * simplest way to model the halt is to let the cycle run and then take back everything but the
     * read. See {@link #haltedCycle()}.
     */
    private boolean speculating;

    /**
     * Whether the cycle now running has written to the bus. Read by {@link #haltedCycle()}, which
     * is the only thing that cares.
     */
    private boolean wroteThisCycle;

    /**
     * The last three cycles of the RDY line, newest in bit 0.
     * <p>
     * Only {@link #storeHigh} looks, and only at bit 2. The unstable stores work by ANDing the
     * register with the high byte of the address while that byte is still on its way to the address
     * pins, and a transfer that pulled RDY low two cycles before the write holds the byte still for
     * long enough that the AND never happens -- so the store lands where the program asked, with
     * the register uncorrupted.
     */
    private int rdyHistory;

    /**
     * Everything a cycle can change that a halted CPU must not have changed.
     * <p>
     * Allocated per halted cycle rather than kept in a field, which costs a short lived object a
     * few times per transfer and buys two things: nothing can leak from one cycle into the next,
     * and {@code SaveStateCompletenessTests} does not have to be told about twenty-one fields that
     * are only meaningful in the middle of a call.
     * <p>
     * Deliberately <em>not</em> the three interrupt lines: those are driven from outside
     * {@link #tick()}, and {@link #sampleNMI()} keeps watching /NMI while the CPU is held off the
     * bus -- which is the whole reason an NMI can arrive during a transfer at all.
     */
    private static final class Registers {
        private int a, x, y, sp, pc, p, opcode, tick, intTick;
        private int tickValue, tickBaseAddress, tickUnfixedAddress, tickAddress, tickLow, tickHigh;
        private long cycles;
        private boolean rstPending, nmiPending, interruptPending;
        private Sequence sequence;
        private int interruptVector;
    }

    /**
     * How many bytes each opcode takes, which is all this class needs to know about the shape of an
     * instruction: enough to hand a tracer the operands that went with it.
     * <p>
     * Static, like {@link Region}'s tables and for the same reason: {@code SaveStateCompletenessTests}
     * vandalises every primitive array it can reach through the console, and an instance field here
     * would be one of them.
     * <p>
     * {@link com.github.dimiro1.mynes.debug.Disassembler} has a table of its own rather than reading
     * this one. Two tables written from different sources and asserted to agree is a test; one table
     * read twice is not.
     */
    private static final int[] LENGTH_PER_OPCODE = {
            /*      0  1  2  3  4  5  6  7  8  9  A  B  C  D  E  F */
            /* 0 */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 2, 1, 2, 3, 3, 3, 3,
            /* 1 */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 1, 3, 3, 3, 3, 3,
            /* 2 */ 3, 2, 1, 2, 2, 2, 2, 2, 1, 2, 1, 2, 3, 3, 3, 3,
            /* 3 */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 1, 3, 3, 3, 3, 3,
            /* 4 */ 1, 2, 1, 2, 2, 2, 2, 2, 1, 2, 1, 2, 3, 3, 3, 3,
            /* 5 */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 1, 3, 3, 3, 3, 3,
            /* 6 */ 1, 2, 1, 2, 2, 2, 2, 2, 1, 2, 1, 2, 3, 3, 3, 3,
            /* 7 */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 1, 3, 3, 3, 3, 3,
            /* 8 */ 2, 2, 2, 2, 2, 2, 2, 2, 1, 2, 1, 2, 3, 3, 3, 3,
            /* 9 */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 1, 3, 3, 3, 3, 3,
            /* A */ 2, 2, 2, 2, 2, 2, 2, 2, 1, 2, 1, 2, 3, 3, 3, 3,
            /* B */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 1, 3, 3, 3, 3, 3,
            /* C */ 2, 2, 2, 2, 2, 2, 2, 2, 1, 2, 1, 2, 3, 3, 3, 3,
            /* D */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 1, 3, 3, 3, 3, 3,
            /* E */ 2, 2, 2, 2, 2, 2, 2, 2, 1, 2, 1, 2, 3, 3, 3, 3,
            /* F */ 2, 2, 1, 2, 2, 2, 2, 2, 1, 3, 1, 3, 3, 3, 3, 3,
    };

    public CPU(final CPUBus bus) {
        this.bus = bus;

        setP(0x24);
        tick = 1;
        intTick = 1;
    }

    /**
     * Updates the program counter.
     *
     * @param pc the new program counter.
     */
    public void setPC(final int pc) {
        this.pc = ByteUtils.ensureWord(pc);
    }

    /**
     * Sets the CPU cycle counter.
     * This method is primarily for testing purposes.
     *
     * @param cycles the new cycle count.
     */
    public void setCycles(final long cycles) {
        this.cycles = cycles;
    }

    /**
     * Returns the architectural state of the CPU.
     *
     * @return a snapshot of the registers and the cycle counter.
     */
    public State getState() {
        return new State(a, x, y, sp, pc, p, cycles);
    }

    /**
     * Where the CPU is standing.
     * <p>
     * Separate from {@link #getState()} because a debugger asks this once per instruction -- around
     * 1.8 million times a second -- and does not want the seven component record allocated to answer
     * it. Between instructions this is the address of the one about to run.
     */
    public int getPC() {
        return pc;
    }

    /**
     * How many bytes the instruction beginning with this opcode takes.
     */
    public static int lengthOf(final int opcode) {
        return LENGTH_PER_OPCODE[opcode & 0xFF];
    }

    /**
     * Reads or writes the whole chip, depending on which way {@code io} is pointing.
     * <p>
     * One list rather than two. The assignment form is what makes that work: saving, {@code u8(a)}
     * writes the byte and hands it straight back, so the assignment is a no-op; loading, it ignores
     * what it was given and returns what it read. A field can therefore only be forgotten in both
     * directions at once, which the divergence test catches and a mismatched pair of read and write
     * lists would not.
     * <p>
     * Everything after {@link #cycles} is the half-executed instruction: which of its cycles comes
     * next, the addresses it has worked out so far, and the interrupt latches. None of it can be
     * left out, because a frame boundary is nothing like an instruction boundary -- a state saved
     * without this would resume from the start of an instruction it was three cycles into.
     * <p>
     * Not to be confused with {@link #loadState(State)}, which is a test entry point and
     * deliberately throws all of that away.
     *
     * @see com.github.dimiro1.mynes.state.SaveState
     */
    public void serialize(final StateIO io) {
        a = io.u8(a);
        x = io.u8(x);
        y = io.u8(y);
        sp = io.u8(sp);
        pc = io.u16(pc);
        p = io.u8(p);
        cycles = io.u64(cycles);

        opcode = io.u8(opcode);
        tick = io.u16(tick);
        intTick = io.u16(intTick);
        tickValue = io.u8(tickValue);
        tickBaseAddress = io.u16(tickBaseAddress);
        tickUnfixedAddress = io.u16(tickUnfixedAddress);
        tickAddress = io.u16(tickAddress);
        tickLow = io.u8(tickLow);
        tickHigh = io.u8(tickHigh);

        // The interrupt latches, as opposed to the lines driving them. Both lines are written too,
        // even though the PPU and the BUS re-drive them once their own chunks land: two bits, and a
        // machine that is internally consistent is easier to reason about than one with a
        // deliberate hole in it.
        rstPending = io.bool(rstPending);
        nmiPending = io.bool(nmiPending);
        nmiLine = io.bool(nmiLine);
        nmiLineLastSample = io.bool(nmiLineLastSample);
        irqLine = io.bool(irqLine);
        interruptPending = io.bool(interruptPending);
        sequence = io.enumeration(sequence, Sequence.class);
        interruptVector = io.u16(interruptVector);
        stalled = io.bool(stalled);
        rdyHistory = io.u8(rdyHistory);
    }

    /**
     * Overwrites the architectural state of the CPU.
     * <p>
     * Besides the registers this drops any partially executed instruction, any interrupt
     * sequence in flight and every pending interrupt request -- including the power-on RST
     * that a freshly constructed CPU starts with. After this call the next {@link #tick()}
     * fetches an opcode from the supplied program counter.
     * <p>
     * This method is primarily for testing purposes.
     *
     * @param state the state to load.
     */
    public void loadState(final State state) {
        setA(state.a());
        setX(state.x());
        setY(state.y());
        setSP(state.sp());
        setPC(state.pc());
        setP(state.p());
        cycles = state.cycles();
        tick = 1;
        intTick = 1;
        rstPending = false;
        nmiPending = false;
        nmiLine = false;
        nmiLineLastSample = false;
        irqLine = false;
        interruptPending = false;
        sequence = Sequence.NONE;
        stalled = false;
        rdyHistory = 0;
    }

    private void setLowPC(final int low) {
        setPC(ByteUtils.setLow(low, pc));
    }

    private void setHighPC(final int high) {
        setPC(ByteUtils.setHigh(high, pc));
    }

    private void incPC() {
        setPC(pc + 1);
    }

    /**
     * Request a RST interrupt.
     */
    public void requestRST() {
        rstPending = true;
    }

    /**
     * Signal the falling edge of the NMI line.
     * <p>
     * NMI cannot be masked by the I flag and takes priority over IRQ. Because the line is edge
     * triggered, calling this twice before the CPU gets to service it still produces a single
     * interrupt.
     */
    public void requestNMI() {
        nmiPending = true;
    }

    /**
     * Drives the /NMI line.
     * <p>
     * Unlike {@link #requestNMI()} this is the line and not the edge: only a transition from
     * released to asserted arms the latch, and only {@link #sampleNMI()} looks. A device that
     * asserts and releases the line between two samples is therefore never seen, which is exactly
     * what makes the PPU's documented NMI suppression windows work.
     *
     * @param level true to assert the line, false to release it.
     */
    public void setNMILine(final boolean level) {
        nmiLine = level;
    }

    /**
     * Samples the /NMI line, latching a request if it has just been asserted.
     * <p>
     * Separate from {@link #tick()} because the sample does not happen at a cycle boundary. The
     * 6502 looks at /NMI during φ2, a little after it has finished the cycle's bus access, and on
     * an NTSC NES that gap is about one PPU dot wide -- which is exactly the width of the window
     * where reading $2002 stops an NMI that has already been requested. Whoever clocks the CPU is
     * responsible for calling this at the right point; see {@link NES#tick()}.
     * <p>
     * Runs on cycles spent stalled by a DMA transfer and on cycles in the middle of an interrupt
     * sequence too, because the sampling circuit does not care what the rest of the chip is doing.
     * An edge latched mid-sequence is what lets an NMI hijack an IRQ or BRK at its vector fetch.
     */
    public void sampleNMI() {
        if (nmiLine && !nmiLineLastSample) {
            nmiPending = true;
        }

        nmiLineLastSample = nmiLine;
    }

    /**
     * Whether the CPU is between instructions.
     * <p>
     * True when nothing is half executed: no instruction in flight, no interrupt sequence in
     * flight, and the last cycle was not one spent held off the bus by a DMA transfer.
     *
     * @return true if the next {@link #tick()} starts something new.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean isAtInstructionBoundary() {
        return !stalled && tick == 1 && intTick == 1;
    }

    /**
     * Assert or release the IRQ line.
     * <p>
     * The line is level triggered and shared: a device holds it until whatever raised it has
     * been acknowledged. Asserting it while the I flag is set does not lose the request, the CPU
     * simply keeps ignoring it until the flag clears.
     *
     * @param asserted true to pull the line low, false to release it.
     */
    public void setIRQLine(final boolean asserted) {
        irqLine = asserted;
    }

    /**
     * The level of the /IRQ line, as the last device to drive it left it.
     * <p>
     * The CPU sees one wire and cannot tell who is holding it; this is that wire, not a pending
     * interrupt. Exists so that {@link BUS}'s OR of the three interrupt sources can be tested for
     * what it puts on the line rather than for what the CPU eventually does about it.
     *
     * @return true while the line is asserted.
     */
    public boolean isIRQLineAsserted() {
        return irqLine;
    }

    /**
     * Add an object to be notified on internal events.
     *
     * @param listener Object to listen to internal events.
     */
    public void addEventListener(final CPUEventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Stop notifying one.
     * <p>
     * Not called from inside {@link CPUEventListener#onStep}: the list is walked while it notifies,
     * so a listener that took itself off from in there would be removing an element from underneath
     * the walk. A tracer that has written all it was asked for goes quiet and waits to be taken off
     * between instructions.
     *
     * @param listener the one to forget. Anything not on the list is ignored.
     */
    public void removeEventListener(final CPUEventListener listener) {
        listeners.remove(listener);
    }

    /**
     * Step one instruction per call.
     * <p>
     * An OAM DMA transfer holds the CPU off the bus for over five hundred cycles without any
     * instruction running, so the stall is absorbed here rather than being reported as a step.
     */
    public void step() {
        do {
            tick();
        } while (!isAtInstructionBoundary());
    }

    /**
     * Step one clock cycle per call.
     * <p>
     * This is the cycle's work only. The /NMI line is sampled by {@link #sampleNMI()}, which
     * happens slightly later in the cycle than the bus access does.
     */
    public void tick() {
        var kind = bus.beginDMACycle(cycles);

        // Anything but NONE means a transfer is holding RDY low, whether or not this cycle ends up
        // being one the CPU keeps.
        rdyHistory = ((rdyHistory << 1) | (kind == CPUBus.DMACycle.NONE ? 0 : 1)) & 0x07;

        if (kind == CPUBus.DMACycle.TRANSFER) {
            // Somebody else has both the address and the data pins. There is nothing for the CPU
            // to do, and no interrupt sequence in flight makes any difference: the hardware halts
            // through those too.
            stalled = true;
            cycles++;
            return;
        }

        if (kind == CPUBus.DMACycle.HALT) {
            haltedCycle();
            return;
        }

        stalled = false;
        runCycle();
    }

    /**
     * A cycle spent held off the bus, by running it and then taking it back.
     * <p>
     * A halted 6502 does not stop dead: it holds the address it had reached and reads it again on
     * every cycle until RDY comes back. That is not a detail -- it is why a transfer that lands on
     * an {@code LDA $2002} clears the VBlank flag more than once, why one on {@code LDA $2007}
     * walks the PPU's address further than the program asked, and why one on {@code LDA $4016}
     * clocks a controller nobody read.
     * <p>
     * Rather than work out in advance what the cycle was going to do, this lets it happen and then
     * puts every register back. The read stays -- it is <em>meant</em> to happen again -- and the
     * CPU is left standing exactly where it was, so the next cycle re-issues the same read.
     * <p>
     * A cycle that turns out to be a write is kept instead. The 6502 does not sample RDY while it
     * is writing, so the write goes through and the halt is simply one cycle later.
     */
    private void haltedCycle() {
        var before = save();
        speculating = true;
        wroteThisCycle = false;

        runCycle();

        speculating = false;

        if (wroteThisCycle) {
            bus.endHaltCycle(true);
            return;
        }

        restore(before);
        bus.endHaltCycle(false);

        stalled = true;
        cycles++;
    }

    private void runCycle() {
        if (canServeInterrupts()) {
            servePendingInterrupt();
            return;
        }

        if (isFirstTickOfInstruction()) {
            opcode = fetchPC();

            // Suppressed while speculating, so a tracer sees one line per instruction rather than
            // one per halted cycle. Safe to skip outright: an opcode fetch is a read, and every
            // speculative read is taken back, so a cycle that survives was never a fetch.
            if (!speculating) {
                notifyStep();
            }

            incPC();
        }

        switch (opcode) {
            case 0x00 -> brk();
            case 0x40 -> rti();
            case 0x60 -> rts();
            case 0x08 -> php();
            case 0x48 -> pha();
            case 0x28 -> plp();
            case 0x68 -> pla();
            case 0x20 -> jsr();

            case 0x0A, 0x1A, 0x18, 0x2A, 0x38, 0x3A, 0x4A, 0x58, 0x5A, 0x6A, 0x78, 0x7A, 0x88, 0x8A, 0x98, 0x9A, 0xA8,
                 0xAA, 0xB8, 0xBA, 0xC8, 0xCA, 0xD8, 0xDA, 0xE8, 0xEA, 0xF8, 0xFA -> accumulatorOrImplied();

            case 0x09, 0x0B, 0x2B, 0x29, 0x49, 0x4B, 0x69, 0x6B, 0x80, 0x82, 0x89, 0x8B, 0xA0, 0xA2, 0xA9, 0xAB, 0xC0,
                 0xC2, 0xC9, 0xCB, 0xE0, 0xE2, 0xE9, 0xEB -> immediate();

            case 0x4C -> absoluteJump();

            case 0x0C, 0x0D, 0x2C, 0x2D, 0x4D, 0x6D, 0xAC, 0xAD, 0xAE, 0xAF, 0xCC, 0xCD, 0xEC, 0xED -> absoluteRead();

            case 0x0E, 0x0F, 0x2E, 0x2F, 0x4F, 0x4E, 0x6E, 0x6F, 0xCE, 0xCF, 0xEE, 0xEF -> absoluteModify();

            case 0x8C, 0x8D, 0x8E, 0x8F -> absoluteWrite();

            case 0x04, 0x05, 0x24, 0x25, 0x44, 0x45, 0x64, 0x65, 0xA4, 0xA5, 0xA6, 0xA7, 0xC4, 0xC5, 0xE4,
                 0xE5 -> zeroPageRead();

            case 0x06, 0x07, 0x26, 0x27, 0x46, 0x47, 0x66, 0x67, 0xC6, 0xC7, 0xE6, 0xE7 -> zeroPageModify();

            case 0x84, 0x85, 0x86, 0x87 -> zeroPageWrite();
            case 0xB6, 0xB7 -> zeroPageYRead();

            case 0x15, 0x35, 0x55, 0x75, 0xB4, 0xB5, 0xD5, 0xF5, 0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4 -> zeroPageXRead();

            case 0x96, 0x97 -> zeroPageYWrite();
            case 0x94, 0x95 -> zeroPageXWrite();

            case 0x16, 0x17, 0x36, 0x37, 0x56, 0x57, 0x76, 0x77, 0xD6, 0xD7, 0xF6, 0xF7 -> zeroPageXModify();

            case 0x19, 0x39, 0x59, 0x79, 0xB9, 0xBB, 0xBE, 0xBF, 0xD9, 0xF9 -> absoluteIndexedYRead();

            case 0x1D, 0x3D, 0x5D, 0x7D, 0xBC, 0xBD, 0xDD, 0xFD, 0x1C, 0xFC,
                 0xDC, 0x7C, 0x5C, 0x3C -> absoluteIndexedXRead();

            case 0x1B, 0x3B, 0x5B, 0x7B, 0xDB, 0xFB -> absoluteIndexedYModify();

            case 0x1E, 0x1F, 0x3E, 0x3F, 0x5E, 0x5F,
                 0x7E, 0x7F, 0xDE, 0xDF, 0xFE, 0xFF -> absoluteIndexedXModify();

            case 0x99, 0x9B, 0x9E, 0x9F -> absoluteIndexedYWrite();
            case 0x9C, 0x9D -> absoluteIndexedXWrite();
            case 0x10, 0x30, 0x50, 0x70, 0x90, 0xB0, 0xD0, 0xF0 -> relative();

            case 0x01, 0x21, 0x41, 0x61, 0xA1, 0xA3, 0xC1, 0xE1 -> indexedIndirectRead();

            case 0x03, 0x23, 0x43, 0x63, 0xC3, 0xE3 -> indexedIndirectModify();
            case 0x81, 0x83 -> indexedIndirectWrite();

            case 0x11, 0x31, 0x51, 0x71, 0xB1, 0xB3, 0xD1, 0xF1 -> indirectIndexedRead();

            case 0x13, 0x33, 0x53, 0x73, 0xD3, 0xF3 -> indirectIndexedModify();
            case 0x91, 0x93 -> indirectIndexedWrite();
            case 0x6C -> absoluteIndirectJump();

            case 0x02, 0x12, 0x22, 0x32, 0x42, 0x52, 0x62, 0x72, 0x92, 0xB2, 0xD2, 0xF2 -> kil();
        }
    }

    // Increments instruction clock cycles.
    private void incTick() {
        tick++;
        cycles++;
    }

    // Increments the interrupts clock cycles.
    private void incIntTick() {
        intTick++;
        cycles++;
    }

    // Resets instruction clock cycles.
    private void resetTick() {
        tick = 1;
        cycles++;
    }

    // Resets the interrupts clock cycles.
    private void resetIntTick() {
        intTick = 1;
        cycles++;
    }

    /**
     * Samples the interrupt lines.
     * <p>
     * Called at the start of every cycle that can be an instruction's last one, before that
     * cycle does its work. Sampling <em>before</em> the work is what gives the flag-changing
     * instructions their documented behaviour for free: CLI, SEI and PLP write the I flag on
     * their last cycle, so the poll still sees the old flag and the change only takes effect for
     * the following instruction. RTI restores the flag three cycles earlier, so its own poll
     * already sees the restored value and it is not delayed.
     */
    private void pollInterrupts() {
        if (nmiPending || (irqLine && getFlagI() == 0)) {
            interruptPending = true;
        }
    }

    private boolean canServeInterrupts() {
        return isFirstTickOfInstruction()
                && (sequence != Sequence.NONE || rstPending || interruptPending);
    }

    private boolean isFirstTickOfInstruction() {
        return tick == 1;
    }

    private int fetchPC() {
        return read(pc);
    }

    private int fetchPCInc() {
        var value = read(pc);
        incPC();
        return value;
    }

    private void servePendingInterrupt() {
        if (sequence == Sequence.NONE) {
            // Reset outranks everything. IRQ and NMI share one sequence and are only told
            // apart at the vector fetch.
            sequence = rstPending ? Sequence.RESET : Sequence.INTERRUPT;
            rstPending = false;
            interruptPending = false;
        }

        if (sequence == Sequence.RESET) {
            serveReset();
        } else {
            serveInterrupt();
        }
    }

    /**
     * Picks the vector for an interrupt sequence, at the cycle it is fetched rather than when
     * the sequence started.
     * <p>
     * An NMI raised while an IRQ or BRK sequence was still pushing therefore hijacks it: the
     * sequence finishes as an NMI, the edge latch is spent, and only one interrupt is serviced.
     * A BRK hijacked this way still pushed its status byte with the B flag set, because that
     * happened before this point.
     */
    private int takeVector() {
        if (nmiPending) {
            nmiPending = false;
            return NMI_VECTOR;
        }

        return IRQ_VECTOR;
    }

    private void serveInterrupt() {
        switch (intTick) {
            case 1, 2 -> {
                // Cycles 1-2: The instruction fetch that was already under way, twice over.
                // Both bytes are discarded; the CPU is committed to the interrupt by now.
                read(pc);
                incIntTick();
            }
            case 3 -> {
                // Cycle 3: Push PCH to stack
                push(ByteUtils.getHigh(pc));
                decSP();
                incIntTick();
            }
            case 4 -> {
                // Cycle 4: Push PCL to stack
                push(ByteUtils.getLow(pc));
                decSP();
                incIntTick();
            }
            case 5 -> {
                // Cycle 5: Push status register with B flag clear (0x00) and unused flag set (0x20)
                // Hardware interrupts (IRQ/NMI) push with B=0, unlike BRK which pushes with B=1
                push((p & 0xEF) | 0x20);
                decSP();
                setFlagI(true);
                interruptVector = takeVector();
                incIntTick();
            }
            case 6 -> {
                // Cycle 6: Fetch low byte of the vector chosen on the cycle before
                tickLow = read(interruptVector);
                incIntTick();
            }
            case 7 -> {
                // Cycle 7: Fetch high byte of interrupt vector and end the sequence
                tickHigh = read(interruptVector + 1);
                setPC(ByteUtils.joinBytes(tickHigh, tickLow));
                sequence = Sequence.NONE;
                resetIntTick();
            }
        }
    }

    private void serveReset() {
        switch (intTick) {
            // Cycle 1-2: Internal operations
            case 1, 2 -> incIntTick();
            case 3, 4, 5 -> {
                // Cycle 3-5: Dummy stack reads (SP is decremented but no actual push occurs)
                pop();
                decSP();
                incIntTick();
            }
            case 6 -> {
                // Cycle 6: Fetch low byte of reset vector
                tickLow = read(RST_VECTOR);
                incIntTick();
            }
            case 7 -> {
                // Cycle 7: Fetch high byte of reset vector
                tickHigh = read(RST_VECTOR + 1);
                incIntTick();
            }
            case 8 -> {
                // Cycle 8: Set PC to reset vector, set I flag, and end the sequence
                setPC(ByteUtils.joinBytes(tickHigh, tickLow));
                setFlagI(true);
                sequence = Sequence.NONE;
                resetIntTick();
            }
        }
    }

    private void push(final int data) {
        write(0x100 + sp, data);
    }

    private void incSP() {
        setSP(sp + 1);
    }

    private void decSP() {
        setSP(sp - 1);
    }

    private int pop() {
        return read(0x100 + sp);
    }

    private int read(final int address) {
        return ByteUtils.ensureByte(bus.read(ByteUtils.ensureWord(address)));
    }

    private void write(final int address, final int data) {
        wroteThisCycle = true;
        bus.write(ByteUtils.ensureWord(address), ByteUtils.ensureByte(data));
    }

    private Registers save() {
        var into = new Registers();

        into.a = a;
        into.x = x;
        into.y = y;
        into.sp = sp;
        into.pc = pc;
        into.p = p;
        into.cycles = cycles;
        into.opcode = opcode;
        into.tick = tick;
        into.intTick = intTick;
        into.tickValue = tickValue;
        into.tickBaseAddress = tickBaseAddress;
        into.tickUnfixedAddress = tickUnfixedAddress;
        into.tickAddress = tickAddress;
        into.tickLow = tickLow;
        into.tickHigh = tickHigh;
        into.rstPending = rstPending;
        into.nmiPending = nmiPending;
        into.interruptPending = interruptPending;
        into.sequence = sequence;
        into.interruptVector = interruptVector;

        return into;
    }

    private void restore(final Registers from) {
        a = from.a;
        x = from.x;
        y = from.y;
        sp = from.sp;
        pc = from.pc;
        p = from.p;
        cycles = from.cycles;
        opcode = from.opcode;
        tick = from.tick;
        intTick = from.intTick;
        tickValue = from.tickValue;
        tickBaseAddress = from.tickBaseAddress;
        tickUnfixedAddress = from.tickUnfixedAddress;
        tickAddress = from.tickAddress;
        tickLow = from.tickLow;
        tickHigh = from.tickHigh;
        rstPending = from.rstPending;
        nmiPending = from.nmiPending;
        interruptPending = from.interruptPending;
        sequence = from.sequence;
        interruptVector = from.interruptVector;
    }

    private void absoluteJump() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                pollInterrupts();
                tickHigh = fetchPC();
                setPC(ByteUtils.joinBytes(tickHigh, tickLow));
                resetTick();
            }
        }
    }

    private void absoluteIndirectJump() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);
                setLowPC(read(tickAddress));
                incTick();
            }
            case 5 -> {
                pollInterrupts();
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow + 1);
                setHighPC(read(tickAddress));
                resetTick();
            }
        }
    }

    private void absoluteRead() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                tickValue = read(ByteUtils.joinBytes(tickHigh, tickLow));
                readOperation(tickValue);
                resetTick();
            }
        }
    }

    private void absoluteModify() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);
                tickValue = read(tickAddress);
                incTick();
            }
            case 5 -> {
                write(tickAddress, tickValue);
                tickValue = modifyOperation(tickValue);
                incTick();
            }
            case 6 -> {
                pollInterrupts();
                write(tickAddress, tickValue);
                resetTick();
            }
        }
    }

    private void absoluteWrite() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);
                writeOperation();
                resetTick();
            }
        }
    }

    private void zeroPageRead() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                pollInterrupts();
                tickValue = read(tickAddress);
                readOperation(tickValue);
                resetTick();
            }
        }
    }

    private void zeroPageModify() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickValue = read(tickAddress);
                incTick();
            }
            case 4 -> {
                write(tickAddress, tickValue);
                tickValue = modifyOperation(tickValue);
                incTick();
            }
            case 5 -> {
                pollInterrupts();
                write(tickAddress, tickValue);
                resetTick();
            }
        }
    }

    private void zeroPageWrite() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                pollInterrupts();
                writeOperation();
                resetTick();
            }
        }
    }

    private void zeroPageYRead() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                read(tickBaseAddress);
                tickAddress = ByteUtils.ensureByte(tickBaseAddress + y);
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                tickValue = read(tickAddress);
                readOperation(tickValue);
                resetTick();
            }
        }
    }

    private void zeroPageXRead() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                read(tickBaseAddress);
                tickAddress = ByteUtils.ensureByte(tickBaseAddress + x);
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                tickValue = read(tickAddress);
                readOperation(tickValue);
                resetTick();
            }
        }
    }

    private void zeroPageYWrite() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                read(tickBaseAddress);
                tickAddress = ByteUtils.ensureByte(tickBaseAddress + y);
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                writeOperation();
                resetTick();
            }
        }
    }

    private void zeroPageXWrite() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                read(tickBaseAddress);
                tickAddress = ByteUtils.ensureByte(tickBaseAddress + x);
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                writeOperation();
                resetTick();
            }
        }
    }

    private void zeroPageXModify() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                read(tickBaseAddress);
                tickAddress = ByteUtils.ensureByte(tickBaseAddress + x);
                incTick();
            }
            case 4 -> {
                tickValue = read(tickAddress);
                incTick();
            }
            case 5 -> {
                write(tickAddress, tickValue);
                tickValue = modifyOperation(tickValue);
                incTick();
            }
            case 6 -> {
                pollInterrupts();
                write(tickAddress, tickValue);
                resetTick();
            }
        }
    }

    private void absoluteIndexedYRead() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + y
                );

                // The read happens either way: the CPU cannot know the high byte needs fixing
                // until it has already put the unfixed address on the bus.
                tickValue = read(tickUnfixedAddress);

                if (
                        ByteUtils.isDifferentPage(tickAddress, tickUnfixedAddress)
                ) {
                    incTick();
                } else {
                    readOperation(tickValue);
                    resetTick(); // no need to execute cycle 5
                }
            }
            case 5 -> {
                pollInterrupts();
                readOperation(read(tickAddress));
                resetTick();
            }
        }
    }

    private void absoluteIndexedYModify() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + y
                );
                read(tickUnfixedAddress);
                incTick();
            }
            case 5 -> {
                tickValue = read(tickAddress);
                incTick();
            }
            case 6 -> {
                write(tickAddress, tickValue);
                tickValue = modifyOperation(tickValue);
                incTick();
            }
            case 7 -> {
                pollInterrupts();
                write(tickAddress, tickValue);
                resetTick();
            }
        }
    }

    private void absoluteIndexedXModify() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + x);
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + x
                );
                read(tickUnfixedAddress);
                incTick();
            }
            case 5 -> {
                tickValue = read(tickAddress);
                incTick();
            }
            case 6 -> {
                write(tickAddress, tickValue);
                tickValue = modifyOperation(tickValue);
                incTick();
            }
            case 7 -> {
                pollInterrupts();
                write(tickAddress, tickValue);
                resetTick();
            }
        }
    }

    private void absoluteIndexedXRead() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + x);
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + x
                );

                // The read happens either way: the CPU cannot know the high byte needs fixing
                // until it has already put the unfixed address on the bus.
                tickValue = read(tickUnfixedAddress);

                if (
                        ByteUtils.isDifferentPage(tickAddress, tickUnfixedAddress)
                ) {
                    incTick();
                } else {
                    readOperation(tickValue);
                    resetTick(); // no need to execute cycle 5
                }
            }
            case 5 -> {
                pollInterrupts();
                readOperation(read(tickAddress));
                resetTick();
            }
        }
    }

    private void absoluteIndexedYWrite() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + y
                );
                read(tickUnfixedAddress);
                incTick();
            }
            case 5 -> {
                pollInterrupts();
                writeOperation();
                resetTick();
            }
        }
    }

    private void absoluteIndexedXWrite() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickHigh = fetchPCInc();
                incTick();
            }
            case 4 -> {
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + x);
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + x
                );
                read(tickUnfixedAddress);
                incTick();
            }
            case 5 -> {
                pollInterrupts();
                writeOperation();
                resetTick();
            }
        }
    }

    /**
     * Conditional branches, and the one place where the interrupt poll does not simply happen on
     * the last cycle.
     * <p>
     * Cycle 2 always polls -- it is the last cycle when the branch is not taken. Cycle 3, the
     * last cycle of a taken branch that stays inside the page, does <em>not</em>: that is the
     * documented branch quirk, and it delays an interrupt raised during cycle 2 by a whole
     * instruction. Cycle 4, reached only when the branch crosses a page, polls normally.
     */
    private void relative() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                pollInterrupts();
                tickBaseAddress = fetchPCInc();

                if (
                        switch (opcode) {
                            case 0x10 -> getFlagN() == 0;
                            case 0x30 -> getFlagN() == 1;
                            case 0x50 -> getFlagV() == 0;
                            case 0x70 -> getFlagV() == 1;
                            case 0x90 -> getFlagC() == 0;
                            case 0xB0 -> getFlagC() == 1;
                            case 0xD0 -> getFlagZ() == 0;
                            case 0xF0 -> getFlagZ() == 1;
                            default -> throw new IllegalStateException(
                                    "Unexpected opcode: " + opcode
                            );
                        }
                ) {
                    incTick();
                } else {
                    resetTick();
                }
            }
            case 3 -> {
                // Deliberately no poll here; see the branch quirk above.
                // The CPU has already started fetching the next opcode while it adds the offset.
                fetchPC();

                tickAddress = ByteUtils.ensureWord(pc + (byte) tickBaseAddress);
                tickUnfixedAddress = ByteUtils.joinBytes(
                        ByteUtils.getHigh(pc), ByteUtils.getLow(tickAddress)
                );

                if (ByteUtils.isDifferentPage(pc, tickAddress)) {
                    incTick();
                } else {
                    resetTick();
                }

                setPC(tickAddress);
            }
            case 4 -> {
                pollInterrupts();
                // The extra cycle is spent reading from the target with the old high byte,
                // which is what makes a page-crossing branch cost four cycles instead of three.
                read(tickUnfixedAddress);
                resetTick();
            }
        }
    }

    private void indexedIndirectRead() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                read(tickBaseAddress);
                tickBaseAddress = ByteUtils.ensureByte(tickBaseAddress + x);
                incTick();
            }
            case 4 -> {
                tickLow = read(tickBaseAddress);
                incTick();
            }
            case 5 -> {
                tickHigh = read(ByteUtils.ensureByte(tickBaseAddress + 1));
                incTick();
            }
            case 6 -> {
                pollInterrupts();
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);
                tickValue = read(tickAddress);
                readOperation(tickValue);
                resetTick();
            }
        }
    }

    private void indexedIndirectModify() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                read(tickBaseAddress);
                tickBaseAddress = ByteUtils.ensureByte(tickBaseAddress + x);
                incTick();
            }
            case 4 -> {
                tickLow = read(tickBaseAddress);
                incTick();
            }
            case 5 -> {
                tickHigh = read(ByteUtils.ensureByte(tickBaseAddress + 1));
                incTick();
            }
            case 6 -> {
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);
                tickValue = read(tickAddress);
                incTick();
            }
            case 7 -> {
                write(tickAddress, tickValue);
                tickValue = modifyOperation(tickValue);
                incTick();
            }
            case 8 -> {
                pollInterrupts();
                write(tickAddress, tickValue);
                resetTick();
            }
        }
    }

    private void indexedIndirectWrite() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                read(tickBaseAddress);
                tickBaseAddress = ByteUtils.ensureByte(tickBaseAddress + x);
                incTick();
            }
            case 4 -> {
                tickLow = read(tickBaseAddress);
                incTick();
            }
            case 5 -> {
                tickHigh = read(ByteUtils.ensureByte(tickBaseAddress + 1));
                incTick();
            }
            case 6 -> {
                pollInterrupts();
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);
                writeOperation();
                resetTick();
            }
        }
    }

    private void indirectIndexedRead() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickLow = read(tickBaseAddress);
                incTick();
            }
            case 4 -> {
                tickHigh = read(ByteUtils.ensureByte(tickBaseAddress + 1));
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + y
                );
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                incTick();
            }
            case 5 -> {
                pollInterrupts();
                tickValue = read(tickUnfixedAddress);

                if (
                        ByteUtils.isDifferentPage(tickUnfixedAddress, tickAddress)
                ) {
                    incTick();
                } else {
                    readOperation(tickValue);
                    resetTick();
                }
            }
            case 6 -> {
                pollInterrupts();
                tickValue = read(tickAddress);
                readOperation(tickValue);
                resetTick();
            }
        }
    }

    private void indirectIndexedModify() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickLow = read(tickBaseAddress);
                incTick();
            }
            case 4 -> {
                tickHigh = read(ByteUtils.ensureByte(tickBaseAddress + 1));
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + y
                );
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                incTick();
            }
            case 5 -> {
                read(tickUnfixedAddress);
                incTick();
            }
            case 6 -> {
                tickValue = read(tickAddress);
                incTick();
            }
            case 7 -> {
                write(tickAddress, tickValue);
                tickValue = modifyOperation(tickValue);
                incTick();
            }
            case 8 -> {
                pollInterrupts();
                write(tickAddress, tickValue);
                resetTick();
            }
        }
    }

    private void indirectIndexedWrite() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();
                incTick();
            }
            case 3 -> {
                tickLow = read(tickBaseAddress);
                incTick();
            }
            case 4 -> {
                tickHigh = read(ByteUtils.ensureByte(tickBaseAddress + 1));
                tickAddress = ByteUtils.ensureWord(
                        ByteUtils.joinBytes(tickHigh, tickLow) + y
                );
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                incTick();
            }
            case 5 -> {
                read(tickUnfixedAddress);
                incTick();
            }
            case 6 -> {
                pollInterrupts();
                writeOperation();
                resetTick();
            }
        }
    }

    private void immediate() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                pollInterrupts();
                tickValue = fetchPCInc();
                readOperation(tickValue);
                resetTick();
            }
        }
    }

    private void accumulatorOrImplied() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                pollInterrupts();
                fetchPC();

                switch (opcode) {
                    case 0x0A -> setA(asl(a));
                    case 0x18 -> clc();
                    case 0x2A -> setA(rol(a));
                    case 0x38 -> sec();
                    case 0x4A -> setA(lsr(a));
                    case 0x58 -> cli();
                    case 0x6A -> setA(ror(a));
                    case 0x78 -> sei();
                    case 0x88 -> dey();
                    case 0x8A -> txa();
                    case 0x98 -> tya();
                    case 0x9A -> txs();
                    case 0xA8 -> tay();
                    case 0xAA -> tax();
                    case 0xB8 -> clv();
                    case 0xBA -> tsx();
                    case 0xC8 -> iny();
                    case 0xCA -> dex();
                    case 0xD8 -> cld();
                    case 0xE8 -> inx();
                    case 0xF8 -> sed();
                    case 0x1A, 0xFA, 0x3A, 0x5A, 0x7A, 0xDA, 0xEA -> nop();
                }

                resetTick();
            }
        }
    }

    private void php() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                fetchPC();
                incTick();
            }
            case 3 -> {
                pollInterrupts();
                push(p | 0x30);
                decSP();
                resetTick();
            }
        }
    }

    private void pha() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                fetchPC();
                incTick();
            }
            case 3 -> {
                pollInterrupts();
                push(a);
                decSP();
                resetTick();
            }
        }
    }

    private void plp() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                fetchPC();
                incTick();
            }
            case 3 -> {
                // The stack pointer is incremented during this cycle, so the address that goes
                // out on the bus is still the old one and the byte read is discarded.
                pop();
                incSP();
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                setP((pop() & 0xEF) | 0x20);
                resetTick();
            }
        }
    }

    private void pla() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                fetchPC();
                incTick();
            }
            case 3 -> {
                // Discarded stack read while the stack pointer is incremented.
                pop();
                incSP();
                incTick();
            }
            case 4 -> {
                pollInterrupts();
                setA(pop());
                setZeroNegFlags(a);
                resetTick();
            }
        }
    }

    private void brk() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                fetchPCInc();
                incTick();
            }
            case 3 -> {
                push(ByteUtils.getHigh(pc));
                decSP();
                incTick();
            }
            case 4 -> {
                push(ByteUtils.getLow(pc));
                decSP();
                incTick();
            }
            case 5 -> {
                push(p | 0x30);
                decSP();

                // BRK picks its vector the same way an interrupt sequence does, so an NMI that
                // arrived while the status byte was being pushed hijacks it.
                interruptVector = takeVector();
                incTick();
            }
            case 6 -> {
                setLowPC(read(interruptVector));
                setFlagI(true);
                incTick();
            }
            case 7 -> {
                setHighPC(read(interruptVector + 1));
                resetTick();
            }
        }
    }

    private void rti() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                fetchPC();
                incTick();
            }
            case 3 -> {
                // Discarded stack read while the stack pointer is incremented.
                pop();
                incSP();
                incTick();
            }
            case 4 -> {
                setP((pop() & 0xEF) | 0x20);
                incSP();
                incTick();
            }
            case 5 -> {
                setLowPC(pop());
                incSP();
                incTick();
            }
            case 6 -> {
                pollInterrupts();
                setHighPC(pop());
                resetTick();
            }
        }
    }

    private void rts() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                fetchPC();
                incTick();
            }
            case 3 -> {
                // Discarded stack read while the stack pointer is incremented.
                pop();
                incSP();
                incTick();
            }
            case 4 -> {
                setLowPC(pop());
                incSP();
                incTick();
            }
            case 5 -> {
                setHighPC(pop());
                incTick();
            }
            case 6 -> {
                pollInterrupts();
                // The address pulled off the stack is one short of the return address, so the
                // last cycle reads from it before correcting it, and that read is discarded.
                fetchPC();
                incPC();
                resetTick();
            }
        }
    }

    private void jsr() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
                // Discarded stack read, one cycle before the return address is pushed over it.
                pop();
                incTick();
            }
            case 4 -> {
                push(ByteUtils.getHigh(pc));
                decSP();
                incTick();
            }
            case 5 -> {
                push(ByteUtils.getLow(pc));
                decSP();
                incTick();
            }
            case 6 -> {
                pollInterrupts();
                setPC(ByteUtils.joinBytes(fetchPC(), tickLow));
                resetTick();
            }
        }
    }

    /**
     * What a read instruction does with the byte its addressing mode fetched.
     * <p>
     * Nine modes reach here and every one of them used to carry its own copy of this switch, which
     * is nine places for one fact to be written down and eight of them to be forgotten. One switch
     * can serve all nine because an opcode has exactly one addressing mode: the nine sets are
     * disjoint, so there is nothing for the mode to disambiguate. What stays with each mode is its
     * cycle switch, which is where the timing lives.
     *
     * @param value the byte the mode put on the bus, passed rather than read from
     *              {@link #tickValue} because the page-crossing modes call this with a byte they
     *              have not stored there.
     */
    private void readOperation(final int value) {
        switch (opcode) {
            case 0x01, 0x05, 0x09, 0x0D, 0x11, 0x15, 0x19, 0x1D -> ora(value);
            case 0x21, 0x25, 0x29, 0x2D, 0x31, 0x35, 0x39, 0x3D -> and(value);
            case 0x41, 0x45, 0x49, 0x4D, 0x51, 0x55, 0x59, 0x5D -> eor(value);
            case 0x61, 0x65, 0x69, 0x6D, 0x71, 0x75, 0x79, 0x7D -> adc(value);
            case 0xA1, 0xA5, 0xA9, 0xAD, 0xB1, 0xB5, 0xB9, 0xBD -> lda(value);
            case 0xC1, 0xC5, 0xC9, 0xCD, 0xD1, 0xD5, 0xD9, 0xDD -> cmp(value);
            case 0xE1, 0xE5, 0xE9, 0xEB, 0xED, 0xF1, 0xF5, 0xF9, 0xFD -> sbc(value);

            case 0xA2, 0xA6, 0xAE, 0xB6, 0xBE -> ldx(value);
            case 0xA0, 0xA4, 0xAC, 0xB4, 0xBC -> ldy(value);
            case 0xE0, 0xE4, 0xEC -> cpx(value);
            case 0xC0, 0xC4, 0xCC -> cpy(value);
            case 0x24, 0x2C -> bit(value);

            // The illegal reads: LAX in six addressing modes, LAS in one -- absolute indexed by Y
            // -- and the rest immediate only, the last two of those being the ones real hardware
            // is unstable about. See UNSTABLE_MAGIC.
            case 0xA3, 0xA7, 0xAF, 0xB3, 0xB7, 0xBF -> lax(value);
            case 0xBB -> las(value);
            case 0x0B, 0x2B -> anc(value);
            case 0x4B -> asr(value);
            case 0x6B -> arr(value);
            case 0xCB -> axs(value);
            case 0x8B -> xaa(value);
            case 0xAB -> lxa(value);

            case 0x04, 0x0C, 0x14, 0x1C, 0x34, 0x3C, 0x44, 0x54, 0x5C, 0x64, 0x74, 0x7C,
                 0x80, 0x82, 0x89, 0xC2, 0xD4, 0xDC, 0xE2, 0xF4, 0xFC -> nop();

            default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
        }
    }

    /**
     * What a read-modify-write instruction does to the byte between the two writes.
     * <p>
     * The same seven modes, one mapping, for the reason given on {@link #readOperation(int)}. The
     * twelve operations are six shifts and the six illegal opcodes that pair a shift with an ALU
     * operation -- which is why those six return the shifted byte and not the ALU's result: what
     * goes back to memory is the shift, and the ALU only touches the accumulator and the flags.
     *
     * @param value the byte read from the address being modified.
     * @return what to write over it on the cycle after next.
     */
    private int modifyOperation(final int value) {
        return switch (opcode) {
            case 0x06, 0x0E, 0x16, 0x1E -> asl(value);
            case 0x03, 0x07, 0x0F, 0x13, 0x17, 0x1B, 0x1F -> slo(value);
            case 0x26, 0x2E, 0x36, 0x3E -> rol(value);
            case 0x23, 0x27, 0x2F, 0x33, 0x37, 0x3B, 0x3F -> rla(value);
            case 0x46, 0x4E, 0x56, 0x5E -> lsr(value);
            case 0x43, 0x47, 0x4F, 0x53, 0x57, 0x5B, 0x5F -> sre(value);
            case 0x66, 0x6E, 0x76, 0x7E -> ror(value);
            case 0x63, 0x67, 0x6F, 0x73, 0x77, 0x7B, 0x7F -> rra(value);
            case 0xC6, 0xCE, 0xD6, 0xDE -> dec(value);
            case 0xC3, 0xC7, 0xCF, 0xD3, 0xD7, 0xDB, 0xDF -> dcp(value);
            case 0xE6, 0xEE, 0xF6, 0xFE -> inc(value);
            case 0xE3, 0xE7, 0xEF, 0xF3, 0xF7, 0xFB, 0xFF -> isc(value);
            default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
        };
    }

    /**
     * The whole of what a store instruction does on its last cycle.
     * <p>
     * Every one of the eight modes has worked {@link #tickAddress} out by the time it gets here, so
     * all that is left to differ is which register goes out on the bus -- and, for the five
     * unstable ones, that it goes out through {@link #storeHigh(int)} rather than straight.
     */
    private void writeOperation() {
        switch (opcode) {
            case 0x81, 0x85, 0x8D, 0x91, 0x95, 0x99, 0x9D -> write(tickAddress, a);
            case 0x86, 0x8E, 0x96 -> write(tickAddress, x);
            case 0x84, 0x8C, 0x94 -> write(tickAddress, y);
            case 0x83, 0x87, 0x8F, 0x97 -> write(tickAddress, a & x);

            case 0x9C -> storeHigh(y);
            case 0x9E -> storeHigh(x);
            case 0x93, 0x9F -> storeHigh(a & x);
            case 0x9B -> {
                // TAS/SHS also copies A & X into the stack pointer.
                setSP(a & x);
                storeHigh(sp);
            }

            default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
        }
    }

    /**
     * The store instruction shared by the unstable SH family: SHA ($93/$9F), SHY ($9C),
     * SHX ($9E) and TAS ($9B).
     * <p>
     * These AND the register with the high byte of the operand address plus one. The high byte
     * used is the one that was <em>fetched</em>, not the one page-crossing would have corrected
     * it to, because the AND happens while the address is still being fixed up.
     * <p>
     * When the index does carry into the high byte the fix-up collides with that AND and the
     * value ends up on the address bus in place of the high byte, so the store lands in the
     * page the value names rather than in the intended one. Emulating that "H corruption"
     * matters because the same quirk is what makes these opcodes usable at all: software picks
     * operands that never cross a page.
     * <p>
     * Verified against all 10,000 Tom Harte cases of each of the five opcodes.
     * <p>
     * The exception is a DMA transfer. If RDY went low two cycles before this one, the high byte
     * has had an extra cycle to settle onto the address pins, the AND never reaches it, and the
     * instruction behaves like the plain store it looks like: the register goes out whole and it
     * goes to the address the operand names. The Harte set has no way to express that, so this is
     * the one line of the instruction it does not cover.
     *
     * @param register the register (or combination of registers) being stored.
     */
    private void storeHigh(final int register) {
        if ((rdyHistory & 0x04) != 0) {
            write(tickAddress, ByteUtils.ensureByte(register));

            return;
        }

        var value = ByteUtils.ensureByte(register & ByteUtils.ensureByte(tickHigh + 1));

        var address = ByteUtils.isDifferentPage(tickAddress, tickUnfixedAddress)
                ? ByteUtils.joinBytes(value, ByteUtils.getLow(tickAddress))
                : tickAddress;

        write(address, value);
    }

    // The 6502 NOP: it deliberately does nothing, the empty body is the whole instruction.
    @SuppressWarnings("EmptyMethod")
    private void nop() {
        /* No Operation */
    }

    private void ldy(final int value) {
        setY(value);
        setZeroNegFlags(y);
    }

    private void cpy(final int value) {
        var res = ByteUtils.ensureByte(y - value);
        setZeroNegFlags(res);
        setFlagC(res <= y);
    }

    private void cpx(final int value) {
        var res = ByteUtils.ensureByte(x - value);
        setZeroNegFlags(res);
        setFlagC(res <= x);
    }

    private void ora(final int value) {
        setA(a | value);
        setZeroNegFlags(a);
    }

    private void and(final int value) {
        setA(a & value);
        setZeroNegFlags(a);
    }

    private void eor(final int value) {
        setA(a ^ value);
        setZeroNegFlags(a);
    }

    private void asr(final int value) {
        setA(lsr(a & value));
    }

    private void adc(final int value) {
        var a = this.a;
        var c = getFlagC();
        var r = a + value + c;

        setA(ByteUtils.ensureByte(r));
        setFlagC(r > 0xFF);
        setFlagV(
                ((a ^ value) & 0x80) == 0 &&
                        ((a ^ ByteUtils.ensureByte(r)) & 0x80) != 0
        );
        setZeroNegFlags(this.a);
    }

    private void lda(final int value) {
        setA(value);
        setZeroNegFlags(a);
    }

    private void cmp(final int value) {
        var res = ByteUtils.ensureByte(a - value);
        setZeroNegFlags(res);
        setFlagC(a >= value);
    }

    private void sbc(final int value) {
        var a = this.a;
        var r = (a - value - (1 - getFlagC()));

        setA(ByteUtils.ensureByte(r));
        setFlagC(r >= 0);
        setFlagV(((a ^ value) & 0x80) != 0 &&
                ((a ^ ByteUtils.ensureByte(r)) & 0x80) != 0
        );
        setZeroNegFlags(this.a);
    }

    /**
     * One of the twelve opcodes that jam the processor. Nothing recovers it but a reset, and no
     * cartridge worth running executes one, so this is treated as a bug in the emulator rather than
     * as a machine that has to be modelled.
     * <p>
     * Except while speculating. A halted CPU re-reads the address it had reached on every cycle, so
     * a program stopped on an opcode fetch decodes whatever is on the bus over and over -- and if
     * the bus happens to be floating, one of those bytes can be any of the twelve. It has not
     * executed anything: the cycle is about to be taken back, and the fetch will happen again once
     * RDY comes up. Throwing there would kill a run over a byte the machine never used.
     */
    private void kil() {
        if (speculating) {
            return;
        }

        throw new RuntimeException("kil is an illegal opcode");
    }

    private void ldx(final int value) {
        setX(value);
        setZeroNegFlags(x);
    }

    private int slo(final int value) {
        var res = asl(value);
        ora(res);
        return res;
    }

    private int rla(final int value) {
        var res = rol(value);
        and(res);
        return res;
    }

    private int sre(final int value) {
        var res = lsr(value);
        eor(res);
        return res;
    }

    private int rra(final int value) {
        var res = ror(value);
        adc(res);
        return res;
    }

    private void lax(final int value) {
        setA(value);
        setX(a);
        setZeroNegFlags(a);
    }

    private int dcp(final int value) {
        var res = dec(value);
        cmp(res);
        return res;
    }

    private int isc(final int value) {
        var res = inc(value);
        sbc(res);
        return res;
    }

    private void bit(final int value) {
        setFlagZ((value & a) == 0);
        setFlagN(ByteUtils.getBit(7, value));
        setFlagV(ByteUtils.getBit(6, value));
    }

    private int asl(final int value) {
        var shifted = ByteUtils.ensureByte(value << 1);
        setFlagC(ByteUtils.getBit(7, value));
        setZeroNegFlags(shifted);
        return shifted;
    }

    private int rol(final int value) {
        var rotated = ByteUtils.ensureByte((value << 1) | getFlagC());
        setFlagC(ByteUtils.getBit(7, value));
        setZeroNegFlags(rotated);
        return rotated;
    }

    private int lsr(final int value) {
        var shifted = ByteUtils.ensureByte(value >> 1);
        setFlagC(ByteUtils.getBit(0, value));
        setZeroNegFlags(shifted);
        return shifted;
    }

    private int ror(final int value) {
        var rotated = ByteUtils.ensureByte((value >> 1) | (getFlagC() << 7));
        setFlagC(ByteUtils.getBit(0, value));
        setZeroNegFlags(rotated);
        return rotated;
    }

    private int dec(final int value) {
        var res = ByteUtils.ensureByte(value - 1);
        setZeroNegFlags(res);
        return res;
    }

    private int inc(final int value) {
        var res = ByteUtils.ensureByte(value + 1);
        setZeroNegFlags(res);
        return res;
    }

    private void clc() {
        setFlagC(false);
    }

    private void sec() {
        setFlagC(true);
    }

    private void cli() {
        setFlagI(false);
    }

    private void sei() {
        setFlagI(true);
    }

    private void dey() {
        setY(y - 1);
        setZeroNegFlags(y);
    }

    private void tya() {
        setA(y);
        setZeroNegFlags(a);
    }

    private void tay() {
        setY(a);
        setZeroNegFlags(y);
    }

    private void clv() {
        setFlagV(false);
    }

    private void iny() {
        setY(y + 1);
        setZeroNegFlags(y);
    }

    private void cld() {
        setFlagD(false);
    }

    private void inx() {
        setX(x + 1);
        setZeroNegFlags(x);
    }

    private void sed() {
        setFlagD(true);
    }

    private void txa() {
        setA(x);
        setZeroNegFlags(a);
    }

    private void txs() {
        setSP(x);
    }

    private void tax() {
        setX(a);
        setZeroNegFlags(x);
    }

    private void tsx() {
        setX(sp);
        setZeroNegFlags(x);
    }

    private void dex() {
        setX(x - 1);
        setZeroNegFlags(x);
    }

    private void anc(final int value) {
        and(value);
        setFlagC(getFlagN());
    }

    private void arr(final int value) {
        setA(((value & a) >> 1) | (getFlagC() << 7));
        setFlagC(ByteUtils.getBit(6, a));
        setZeroNegFlags(a);

        // only binary mode
        setFlagV(ByteUtils.getBit(6, a) != ByteUtils.getBit(5, a));
    }

    /**
     * XAA/ANE ($8B). Unstable: the accumulator is OR'd with a constant that depends on the
     * particular chip and its temperature before the AND. {@link #UNSTABLE_MAGIC} is the value
     * the Tom Harte reference implementation uses. Does not touch the carry flag.
     */
    private void xaa(final int value) {
        setA((a | UNSTABLE_MAGIC) & x & value);
        setZeroNegFlags(a);
    }

    /**
     * LXA/ATX ($AB). Unstable in the same way as {@link #xaa(int)}; loads both A and X.
     */
    private void lxa(final int value) {
        setA((a | UNSTABLE_MAGIC) & value);
        setX(a);
        setZeroNegFlags(a);
    }

    /**
     * LAS/LAR ($BB). ANDs memory with the stack pointer and puts the result in A, X and SP.
     */
    private void las(final int value) {
        var res = ByteUtils.ensureByte(value & sp);
        setA(res);
        setX(res);
        setSP(res);
        setZeroNegFlags(res);
    }

    private void axs(final int value) {
        var res = ByteUtils.ensureByte(a & x) - value;
        setX(ByteUtils.ensureByte(res));
        setZeroNegFlags(x);
        setFlagC(res >= 0);
    }

    private void setFlagN(final int n) {
        setFlagN(n > 0);
    }

    private void setFlagN(final boolean n) {
        setP(ByteUtils.setOrClearBitIf(n, 7, p));
    }

    private int getFlagN() {
        return ByteUtils.getBit(7, p);
    }

    private void setFlagV(final int v) {
        setFlagV(v > 0);
    }

    private void setFlagV(final boolean v) {
        setP(ByteUtils.setOrClearBitIf(v, 6, p));
    }

    private int getFlagV() {
        return ByteUtils.getBit(6, p);
    }

    private void setFlagD(final boolean d) {
        setP(ByteUtils.setOrClearBitIf(d, 3, p));
    }

    private void setFlagI(final boolean i) {
        setP(ByteUtils.setOrClearBitIf(i, 2, p));
    }

    private int getFlagI() {
        return ByteUtils.getBit(2, p);
    }

    private void setFlagZ(final boolean z) {
        setP(ByteUtils.setOrClearBitIf(z, 1, p));
    }

    private void setZeroNegFlags(final int value) {
        setFlagZ(value == 0);
        setFlagN(ByteUtils.getBit(7, value));
    }

    private int getFlagZ() {
        return ByteUtils.getBit(1, p);
    }

    private void setFlagC(int c) {
        setFlagC(c > 0);
    }

    private void setFlagC(final boolean c) {
        setP(ByteUtils.setOrClearBitIf(c, 0, p));
    }

    private int getFlagC() {
        return ByteUtils.getBit(0, p);
    }

    private void setA(final int a) {
        this.a = ByteUtils.ensureByte(a);
    }

    private void setX(final int x) {
        this.x = ByteUtils.ensureByte(x);
    }

    private void setY(final int y) {
        this.y = ByteUtils.ensureByte(y);
    }

    private void setSP(final int sp) {
        this.sp = ByteUtils.ensureByte(sp);
    }

    private void setP(final int p) {
        this.p = ByteUtils.ensureByte(p);
    }

    /**
     * Hands the state of the instruction about to run to the listeners.
     * <p>
     * The operand bytes have to be looked at without going through the bus, and only as far as
     * the instruction actually reaches: a real read of $2002 or $4016 would clear a latch or
     * clock a controller's shift register, so tracing the machine would change what it does.
     */
    private void notifyStep() {
        if (listeners.isEmpty()) {
            return;
        }

        var length = LENGTH_PER_OPCODE[opcode];
        var operand1 = length > 1 ? bus.peek(ByteUtils.ensureWord(pc + 1)) : 0;
        var operand2 = length > 2 ? bus.peek(ByteUtils.ensureWord(pc + 2)) : 0;

        listeners.forEach(l ->
                l.onStep(pc, a, x, y, p, sp, opcode, operand1, operand2, length, cycles)
        );
    }

    /**
     * A snapshot of the architectural state of the CPU.
     *
     * @param a      the accumulator.
     * @param x      the x index register.
     * @param y      the y index register.
     * @param sp     the stack pointer.
     * @param pc     the program counter.
     * @param p      the status register.
     * @param cycles the cycle counter.
     */
    public record State(int a, int x, int y, int sp, int pc, int p, long cycles) {
    }

    /**
     * The interrupt sequence the CPU is in the middle of.
     */
    private enum Sequence {
        /**
         * None - normal execution continues.
         */
        NONE,

        /**
         * The seven cycle sequence shared by IRQ (typically raised by a mapper or the APU,
         * vectoring to $FFFE) and NMI (raised by the PPU at the start of VBlank, vectoring to
         * $FFFA). Which of the two it turns out to be is decided at the vector fetch.
         */
        INTERRUPT,

        /**
         * The eight cycle reset sequence, run on power up and when the reset button is pressed.
         * Vectors to $FFFC.
         */
        RESET,
    }
}
