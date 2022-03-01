package mynes.cpu;

import mynes.memory.Memory;
import mynes.utils.ByteUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * CPU implements the R2A07 CPU found in the NES video game console.
 *
 * <ul>
 *   <li>http://nesdev.com/6502_cpu.txt
 *   <li>http://www.oxyron.de/html/opcodes02.html
 * </ul>
 */
public class CPU {
    private int a, x, y, sp, pc, p;
    private long cycles;
    private int tick, intTick, tickValue, tickBaseAddress, tickUnfixedAddress, tickAddress, tickLow, tickHigh;
    private int opcode;

    private final Memory memory;
    private Interrupt pendingInterrupt = Interrupt.RST;
    private final List<EventListener> listeners = new ArrayList<>();

    private final int[] lengthPerOpcode = {
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

    public CPU(final Memory memory) {
        this.memory = memory;

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
        pendingInterrupt = Interrupt.RST;
    }

    /**
     * Request a NMI interrupt.
     */
    public void requestNMI() {
        pendingInterrupt = Interrupt.NMI;
    }

    /**
     * Request a IRQ interrupt.
     */
    public void requestIRQ() {
        if (getFlagI() == 0) {
            pendingInterrupt = Interrupt.IRQ;
        }
    }

    /**
     * Add an object to be notified on internal events.
     *
     * @param listener Object to listen to internal events.
     */
    public void addEventListener(final EventListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    /**
     * Step one instruction per call.
     */
    public void step() {
        do {
            tick();
        } while (isRunningInstruction() || isServingInterrupt());
    }

    /**
     * Step one clock cycle per call.
     */
    public void tick() {
        if (canServeInterrupts()) {
            servePendingInterrupt();
            return;
        }

        if (isFirstTickOfInstruction()) {
            opcode = fetchPC();
            notifyStep();
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

    private boolean canServeInterrupts() {
        return tick == 1 && (pendingInterrupt != Interrupt.NIL);
    }

    private boolean isServingInterrupt() {
        return intTick != 1;
    }

    private boolean isRunningInstruction() {
        return tick != 1;
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
        switch (pendingInterrupt) {
            case IRQ -> serveInterrupt(0xFFFE);
            case NMI -> serveInterrupt(0xFFFA);
            case RST -> serveReset();
        }
    }

    private void serveInterrupt(final int address) {
        switch (intTick) {
            case 1 -> {
                push(ByteUtils.getHigh(pc));
                decSP();
                incIntTick();
            }
            case 2 -> {
                push(ByteUtils.getLow(pc));
                decSP();
                incIntTick();
            }
            case 3 -> {
                push(p & 0x20 | 0x10);
                decSP();
                incIntTick();
            }
            case 4 -> {
                tickLow = read(address);
                incIntTick();
            }
            case 5 -> {
                tickHigh = read(address + 1);
                setPC(ByteUtils.joinBytes(tickHigh, tickLow));
                incIntTick();
            }
            case 6 -> {
                setFlagI(true);
                incIntTick();
            }
            case 7 -> {
                pendingInterrupt = Interrupt.NIL;
                resetIntTick();
            }
        }
    }

    private void serveReset() {
        switch (intTick) {
            case 1 -> {
                tickLow = read(0xFFFC);
                incIntTick();
            }
            case 2 -> {
                tickHigh = read(0xFFFD);
                incIntTick();
            }
            case 3, 4, 5 -> {
                pop();
                decSP();
                incIntTick();
            }
            case 6 -> {
                setLowPC(tickLow);
                setFlagI(true);
                incIntTick();
            }
            case 7 -> {
                setHighPC(tickHigh);
                pendingInterrupt = Interrupt.NIL;
                resetIntTick();
            }
        }
    }

    private void push(int data) {
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
        return ByteUtils.ensureByte(memory.read(ByteUtils.ensureWord(address)));
    }

    private void write(final int address, final int data) {
        memory.write(ByteUtils.ensureWord(address), ByteUtils.ensureByte(data));
    }

    private void absoluteJump() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
                incTick();
            }
            case 3 -> {
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
                tickValue = read(ByteUtils.joinBytes(tickHigh, tickLow));

                switch (opcode) {
                    case 0x0C -> nop();
                    case 0x0D -> ora(tickValue);
                    case 0x2C -> bit(tickValue);
                    case 0x2D -> and(tickValue);
                    case 0x4D -> eor(tickValue);
                    case 0x6D -> adc(tickValue);
                    case 0xAC -> ldy(tickValue);
                    case 0xAD -> lda(tickValue);
                    case 0xAE -> ldx(tickValue);
                    case 0xAF -> lax(tickValue);
                    case 0xCC -> cpy(tickValue);
                    case 0xCD -> cmp(tickValue);
                    case 0xEC -> cpx(tickValue);
                    case 0xED -> sbc(tickValue);
                }

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

                tickValue = switch (opcode) {
                    case 0x0E -> asl(tickValue);
                    case 0x0F -> slo(tickValue);
                    case 0x2E -> rol(tickValue);
                    case 0x2F -> rla(tickValue);
                    case 0x4F -> sre(tickValue);
                    case 0x4E -> lsr(tickValue);
                    case 0x6E -> ror(tickValue);
                    case 0x6F -> rra(tickValue);
                    case 0xCE -> dec(tickValue);
                    case 0xCF -> dcp(tickValue);
                    case 0xEE -> inc(tickValue);
                    case 0xEF -> isc(tickValue);
                    default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
                };
                incTick();
            }
            case 6 -> {
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
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);
                switch (opcode) {
                    case 0x8C -> write(tickAddress, y);
                    case 0x8D -> write(tickAddress, a);
                    case 0x8E -> write(tickAddress, x);
                    case 0x8F -> write(tickAddress, a & x);
                }
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
                tickValue = read(tickAddress);

                switch (opcode) {
                    case 0x05 -> ora(tickValue);
                    case 0x24 -> bit(tickValue);
                    case 0x25 -> and(tickValue);
                    case 0x45 -> eor(tickValue);
                    case 0x65 -> adc(tickValue);
                    case 0xA4 -> ldy(tickValue);
                    case 0xA5 -> lda(tickValue);
                    case 0xA6 -> ldx(tickValue);
                    case 0xA7 -> lax(tickValue);
                    case 0xC4 -> cpy(tickValue);
                    case 0xC5 -> cmp(tickValue);
                    case 0xE4 -> cpx(tickValue);
                    case 0xE5 -> sbc(tickValue);
                    case 0x04, 0x44, 0x64 -> nop();
                }

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

                tickValue = switch (opcode) {
                    case 0x06 -> asl(tickValue);
                    case 0x07 -> slo(tickValue);
                    case 0x26 -> rol(tickValue);
                    case 0x27 -> rla(tickValue);
                    case 0x47 -> sre(tickValue);
                    case 0x66 -> ror(tickValue);
                    case 0x67 -> rra(tickValue);
                    case 0xC6 -> dec(tickValue);
                    case 0xC7 -> dcp(tickValue);
                    case 0xE6 -> inc(tickValue);
                    case 0xE7 -> isc(tickValue);
                    case 0x46, 0x56 -> lsr(tickValue);
                    default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
                };

                incTick();
            }
            case 5 -> {
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
                switch (opcode) {
                    case 0x84 -> write(tickAddress, y);
                    case 0x85 -> write(tickAddress, a);
                    case 0x86 -> write(tickAddress, x);
                    case 0x87 -> write(tickAddress, a & x);
                }

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
                tickValue = read(tickAddress);

                switch (opcode) {
                    case 0xB6 -> ldx(tickValue);
                    case 0xB7 -> lax(tickValue);
                }

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
                tickValue = read(tickAddress);

                switch (opcode) {
                    case 0x15 -> ora(tickValue);
                    case 0x35 -> and(tickValue);
                    case 0x55 -> eor(tickValue);
                    case 0x75 -> adc(tickValue);
                    case 0xB4 -> ldy(tickValue);
                    case 0xB5 -> lda(tickValue);
                    case 0xD5 -> cmp(tickValue);
                    case 0xF5 -> sbc(tickValue);
                    case 0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4 -> nop();
                }

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
                switch (opcode) {
                    case 0x96 -> write(tickAddress, x);
                    case 0x97 -> write(tickAddress, a & x);
                }

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
                switch (opcode) {
                    case 0x94 -> write(tickAddress, y);
                    case 0x95 -> write(tickAddress, a);
                }

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

                tickValue = switch (opcode) {
                    case 0x16 -> asl(tickValue);
                    case 0x17 -> slo(tickValue);
                    case 0x36 -> rol(tickValue);
                    case 0x37 -> rla(tickValue);
                    case 0x56 -> lsr(tickValue);
                    case 0x57 -> sre(tickValue);
                    case 0x76 -> ror(tickValue);
                    case 0x77 -> rra(tickValue);
                    case 0xD6 -> dec(tickValue);
                    case 0xD7 -> dcp(tickValue);
                    case 0xF6 -> inc(tickValue);
                    case 0xF7 -> isc(tickValue);
                    default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
                };

                incTick();
            }
            case 6 -> {
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
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + y);

                if (ByteUtils.isDifferentPage(tickAddress, tickUnfixedAddress)) {
                    incTick();
                } else {
                    absoluteIndexedYReadAction(read(tickUnfixedAddress));
                    resetTick(); // no need to execute cycle 5
                }
            }
            case 5 -> {
                absoluteIndexedYReadAction(read(tickAddress));
                resetTick();
            }
        }
    }

    private void absoluteIndexedYReadAction(final int value) {
        switch (opcode) {
            case 0x19 -> ora(value);
            case 0x39 -> and(value);
            case 0x59 -> eor(value);
            case 0x79 -> adc(value);
            case 0xB9 -> lda(value);
            case 0xBB -> las(value);
            case 0xBE -> ldx(value);
            case 0xBF -> lax(value);
            case 0xD9 -> cmp(value);
            case 0xF9 -> sbc(value);
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
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + y);
                read(tickUnfixedAddress);
                incTick();
            }
            case 5 -> {
                tickValue = read(tickAddress);
                incTick();
            }
            case 6 -> {
                write(tickAddress, tickValue);

                tickValue = switch (opcode) {
                    case 0x1B -> slo(tickValue);
                    case 0x3B -> rla(tickValue);
                    case 0x5B -> sre(tickValue);
                    case 0x7B -> rra(tickValue);
                    case 0xDB -> dcp(tickValue);
                    case 0xFB -> isc(tickValue);
                    default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
                };

                incTick();
            }
            case 7 -> {
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
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + x);
                read(tickUnfixedAddress);
                incTick();
            }
            case 5 -> {
                tickValue = read(tickAddress);
                incTick();
            }
            case 6 -> {
                write(tickAddress, tickValue);

                tickValue = switch (opcode) {
                    case 0x1F -> slo(tickValue);
                    case 0x1E -> asl(tickValue);
                    case 0x3F -> rla(tickValue);
                    case 0x3E -> rol(tickValue);
                    case 0x5F -> sre(tickValue);
                    case 0x5E -> lsr(tickValue);
                    case 0x7F -> rra(tickValue);
                    case 0x7E -> ror(tickValue);
                    case 0xDF -> dcp(tickValue);
                    case 0xDE -> dec(tickValue);
                    case 0xFF -> isc(tickValue);
                    case 0xFE -> inc(tickValue);
                    default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
                };

                incTick();
            }
            case 7 -> {
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
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + x);
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + x);

                if (ByteUtils.isDifferentPage(tickAddress, tickUnfixedAddress)) {
                    incTick();
                } else {
                    absoluteIndexedXReadAction(read(tickUnfixedAddress));
                    resetTick(); // no need to execute cycle 5
                }
            }
            case 5 -> {
                absoluteIndexedXReadAction(read(tickAddress));
                resetTick();
            }
        }
    }

    private void absoluteIndexedXReadAction(final int value) {
        switch (opcode) {
            case 0x1D -> ora(value);
            case 0x3D -> and(value);
            case 0x5D -> eor(value);
            case 0x7D -> adc(value);
            case 0xBC -> ldy(value);
            case 0xBD -> lda(value);
            case 0xDD -> cmp(value);
            case 0xFD -> sbc(value);
            case 0x1C, 0xFC, 0xDC, 0x7C, 0x5C, 0x3C -> nop();
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
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + y);
                read(tickUnfixedAddress);
                incTick();
            }
            case 5 -> {
                switch (opcode) {
                    case 0x99 -> write(tickAddress, a);
                    case 0x9B -> write(tickAddress, xas(tickHigh + 1));
                    case 0x9E -> write(tickAddress, shx(tickAddress));
                    case 0x9F -> write(tickAddress, x & a & (ByteUtils.getHigh(tickAddress) + 1));
                }
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
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + x);
                read(tickUnfixedAddress);
                incTick();
            }
            case 5 -> {
                switch (opcode) {
                    case 0x9C -> write(tickAddress, shy(tickAddress));
                    case 0x9D -> write(tickAddress, a);
                }
                resetTick();
            }
        }
    }

    private void relative() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickBaseAddress = fetchPCInc();

                var branchTaken = switch (opcode) {
                    case 0x10 -> getFlagN() == 0;
                    case 0x30 -> getFlagN() == 1;
                    case 0x50 -> getFlagV() == 0;
                    case 0x70 -> getFlagV() == 1;
                    case 0x90 -> getFlagC() == 0;
                    case 0xB0 -> getFlagC() == 1;
                    case 0xD0 -> getFlagZ() == 0;
                    case 0xF0 -> getFlagZ() == 1;
                    default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
                };

                if (branchTaken) {
                    incTick();
                } else {
                    resetTick();
                }
            }
            case 3 -> {
                tickAddress = ByteUtils.ensureWord(pc + (byte) tickBaseAddress);

                if (ByteUtils.isDifferentPage(pc, tickAddress)) {
                    incTick();
                } else {
                    resetTick();
                }

                setPC(tickAddress);
            }
            case 4 -> resetTick();
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
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);
                tickValue = read(tickAddress);

                switch (opcode) {
                    case 0x01 -> ora(tickValue);
                    case 0x21 -> and(tickValue);
                    case 0x41 -> eor(tickValue);
                    case 0x61 -> adc(tickValue);
                    case 0xA1 -> lda(tickValue);
                    case 0xA3 -> lax(tickValue);
                    case 0xC1 -> cmp(tickValue);
                    case 0xE1 -> sbc(tickValue);
                }
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

                tickValue = switch (opcode) {
                    case 0x03 -> slo(tickValue);
                    case 0x23 -> rla(tickValue);
                    case 0x43 -> sre(tickValue);
                    case 0x63 -> rra(tickValue);
                    case 0xC3 -> dcp(tickValue);
                    case 0xE3 -> isc(tickValue);
                    default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
                };

                incTick();
            }
            case 8 -> {
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
                tickAddress = ByteUtils.joinBytes(tickHigh, tickLow);

                switch (opcode) {
                    case 0x81 -> write(tickAddress, a);
                    case 0x83 -> write(tickAddress, a & x);
                }

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
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + y);
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                incTick();
            }
            case 5 -> {
                tickValue = read(tickUnfixedAddress);

                if (ByteUtils.isDifferentPage(tickUnfixedAddress, tickAddress)) {
                    incTick();
                } else {
                    indirectIndexedReadAction(tickValue);
                    resetTick();
                }
            }
            case 6 -> {
                tickValue = read(tickAddress);
                indirectIndexedReadAction(tickValue);
                resetTick();
            }
        }
    }

    private void indirectIndexedReadAction(final int value) {
        switch (opcode) {
            case 0x11 -> ora(value);
            case 0x31 -> and(value);
            case 0x51 -> eor(value);
            case 0x71 -> adc(value);
            case 0xB1 -> lda(value);
            case 0xB3 -> lax(value);
            case 0xD1 -> cmp(value);
            case 0xF1 -> sbc(value);
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
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + y);
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

                tickValue = switch (opcode) {
                    case 0x13 -> slo(tickValue);
                    case 0x33 -> rla(tickValue);
                    case 0x53 -> sre(tickValue);
                    case 0x73 -> rra(tickValue);
                    case 0xD3 -> dcp(tickValue);
                    case 0xF3 -> isc(tickValue);
                    default -> throw new IllegalStateException("Unexpected opcode: " + opcode);
                };
                incTick();
            }
            case 8 -> {
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
                tickAddress = ByteUtils.ensureWord(ByteUtils.joinBytes(tickHigh, tickLow) + y);
                tickUnfixedAddress = ByteUtils.joinBytes(tickHigh, tickLow + y);
                incTick();
            }
            case 5 -> {
                read(tickUnfixedAddress);
                incTick();
            }
            case 6 -> {
                switch (opcode) {
                    case 0x91 -> write(tickAddress, a);
                    case 0x93 -> write(tickAddress, x & a & (ByteUtils.getHigh(tickAddress) + 1));
                }
                resetTick();
            }
        }
    }

    private void immediate() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
                tickValue = fetchPCInc();

                switch (opcode) {
                    case 0x09 -> ora(tickValue);
                    case 0x29 -> and(tickValue);
                    case 0x49 -> eor(tickValue);
                    case 0x4B -> asr(tickValue);
                    case 0x69 -> adc(tickValue);
                    case 0x6B -> arr(tickValue);
                    case 0x8B -> xaa(tickValue);
                    case 0xA0 -> ldy(tickValue);
                    case 0xA2 -> ldx(tickValue);
                    case 0xA9 -> lda(tickValue);
                    case 0xAB -> lax(tickValue);
                    case 0xC0 -> cpy(tickValue);
                    case 0xC9 -> cmp(tickValue);
                    case 0xCB -> axs(tickValue);
                    case 0xE0 -> cpx(tickValue);
                    case 0x0B, 0x2B -> anc(tickValue);
                    case 0xE9, 0xEB -> sbc(tickValue);
                    case 0x80, 0x89, 0x82, 0xC2, 0xE2 -> nop();
                }

                resetTick();
            }
        }
    }

    private void accumulatorOrImplied() {
        switch (tick) {
            case 1 -> incTick();
            case 2 -> {
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
                incSP();
                incTick();
            }
            case 4 -> {
                setP(pop() & 0xEF | 0x20);
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
                incSP();
                incTick();
            }
            case 4 -> {
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
                incTick();
            }
            case 6 -> {
                setLowPC(read(0xFFFE));
                setFlagI(true);
                incTick();
            }
            case 7 -> {
                setHighPC(read(0xFFFF));
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
                incSP();
                incTick();
            }
            case 4 -> {
                setP(pop() & 0xEF | 0x20);
                incSP();
                incTick();
            }
            case 5 -> {
                setLowPC(pop());
                incSP();
                incTick();
            }
            case 6 -> {
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
                incPC();
                resetTick();
            }
        }
    }

    private void jsr() {
        switch (tick) {
            case 1, 3 -> incTick();
            case 2 -> {
                tickLow = fetchPCInc();
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
                setPC(ByteUtils.joinBytes(fetchPC(), tickLow));
                resetTick();
            }
        }
    }

    private int shx(final int address) {
        if (y + ByteUtils.ensureByte(address - y) <= 0xFF) {
            return x & (ByteUtils.getHigh(address) + 1);
        } else {
            return read(address);
        }
    }

    private int shy(final int address) {
        if (x + ByteUtils.ensureByte(address - x) <= 0xFF) {
            return y & (ByteUtils.getHigh(address) + 1);
        } else {
            return read(address);
        }
    }

    private int xas(final int value) {
        setSP(x & a);
        return sp & value;
    }

    private void nop() { /* No Operation */ }

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
        setFlagV(((a ^ value) & 0x80) == 0 && ((a ^ ByteUtils.ensureByte(r)) & 0x80) != 0);
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
        setFlagV(((a ^ value) & 0x80) != 0 && ((a ^ ByteUtils.ensureByte(r)) & 0x80) != 0);
        setZeroNegFlags(this.a);
    }

    private void kil() {
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
        setA(((value & a) >> 1) | getFlagC() << 7);
        setFlagC(ByteUtils.getBit(6, a));
        setZeroNegFlags(a);

        // only binary mode
        setFlagV(ByteUtils.getBit(6, a) != ByteUtils.getBit(5, a));
    }

    private void xaa(final int value) {
        setA(a & (x & value));
        setFlagC(ByteUtils.getBit(7, a));
        setZeroNegFlags(a);
    }

    private void las(final int value) {
        setP(value);
        setA(p);
        setX(p);
    }

    private void axs(final int value) {
        var res = ByteUtils.ensureByte(a & x) - value;
        setX(ByteUtils.ensureByte(res));
        setZeroNegFlags(x);
        setFlagC(res >= 0);
    }

    private void setFlagN(int n) {
        setFlagN(n > 0);
    }

    private void setFlagN(boolean n) {
        setP(ByteUtils.setOrClearBitIf(n, 7, p));
    }

    private int getFlagN() {
        return ByteUtils.getBit(7, p);
    }

    private void setFlagV(int v) {
        setFlagV(v > 0);
    }

    private void setFlagV(boolean v) {
        setP(ByteUtils.setOrClearBitIf(v, 6, p));
    }

    private int getFlagV() {
        return ByteUtils.getBit(6, p);
    }

    private void setFlagD(boolean d) {
        setP(ByteUtils.setOrClearBitIf(d, 3, p));
    }

    private void setFlagI(boolean i) {
        setP(ByteUtils.setOrClearBitIf(i, 2, p));
    }

    private int getFlagI() {
        return ByteUtils.getBit(2, p);
    }

    private void setFlagZ(boolean z) {
        setP(ByteUtils.setOrClearBitIf(z, 1, p));
    }

    private void setZeroNegFlags(int value) {
        setFlagZ(value == 0);
        setFlagN(ByteUtils.getBit(7, value));
    }

    private int getFlagZ() {
        return ByteUtils.getBit(1, p);
    }

    private void setFlagC(int c) {
        setFlagC(c > 0);
    }

    private void setFlagC(boolean c) {
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

    private void notifyStep() {
        if (listeners.size() == 0) {
            return;
        }

        listeners.forEach(l -> l.onStep(pc, a, x, y, p, sp, opcode,
                read(pc + 1), read(pc + 2), lengthPerOpcode[opcode], cycles));
    }
}
