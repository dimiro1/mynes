package com.github.dimiro1.mynes.cpu;

import com.github.dimiro1.mynes.memory.Memory;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static com.github.dimiro1.mynes.cpu.AddressMode.*;

/**
 * CPU implements the R2A07 CPU found in the NES video game console.
 *
 * <ul>
 *   <li>http://nesdev.com/6502_cpu.txt
 *   <li>http://www.oxyron.de/html/opcodes02.html
 *   <li>http://www.oxyron.de/html/opcodes02.html
 * </ul>
 */
public class CPU {
    private int a, x, y, sp, pc, p;
    private int cycles;

    private final List<EventListener> listeners;
    private final Memory memory;
    private Interrupt pendingInterrupt = Interrupt.NIL;

    private final int[] cyclesPerOpcode = {
            /*      0  1  2  3  4  5  6  7  8  9  A  B  C  D  E  F */
            /* 0 */ 7, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 4, 4, 6, 6,
            /* 1 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 2 */ 6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 4, 4, 6, 6,
            /* 3 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 4 */ 6, 6, 2, 8, 3, 3, 5, 5, 3, 2, 2, 2, 3, 4, 6, 6,
            /* 5 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 6 */ 6, 6, 2, 8, 3, 3, 5, 5, 4, 2, 2, 2, 5, 4, 6, 6,
            /* 7 */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* 8 */ 2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,
            /* 9 */ 2, 6, 2, 6, 4, 4, 4, 4, 2, 5, 2, 5, 5, 5, 5, 5,
            /* A */ 2, 6, 2, 6, 3, 3, 3, 3, 2, 2, 2, 2, 4, 4, 4, 4,
            /* B */ 2, 5, 2, 5, 4, 4, 4, 4, 2, 4, 2, 4, 4, 4, 4, 4,
            /* C */ 2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,
            /* D */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,
            /* E */ 2, 6, 2, 8, 3, 3, 5, 5, 2, 2, 2, 2, 4, 4, 6, 6,
            /* F */ 2, 5, 2, 8, 4, 4, 6, 6, 2, 4, 2, 7, 4, 4, 7, 7,};

    private final int[] lengthPerOpcode = {
            /*      0  1  2  3  4  5  6  7  8  9  A  B  C  D  E  F */
            /* 0 */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 2, 1, 0, 3, 3, 3, 0,
            /* 1 */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 3, 1, 0, 3, 3, 3, 0,
            /* 2 */ 3, 2, 0, 0, 2, 2, 2, 0, 1, 2, 1, 0, 3, 3, 3, 0,
            /* 3 */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 3, 1, 0, 3, 3, 3, 0,
            /* 4 */ 1, 2, 0, 0, 2, 2, 2, 0, 1, 2, 1, 0, 3, 3, 3, 0,
            /* 5 */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 3, 1, 0, 3, 3, 3, 0,
            /* 6 */ 1, 2, 0, 0, 2, 2, 2, 0, 1, 2, 1, 0, 3, 3, 3, 0,
            /* 7 */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 3, 1, 0, 3, 3, 3, 0,
            /* 8 */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 0, 1, 0, 3, 3, 3, 0,
            /* 9 */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 3, 1, 0, 0, 3, 0, 0,
            /* A */ 2, 2, 2, 0, 2, 2, 2, 0, 1, 2, 1, 0, 3, 3, 3, 0,
            /* B */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 3, 1, 0, 3, 3, 3, 0,
            /* C */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 2, 1, 0, 3, 3, 3, 0,
            /* D */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 3, 1, 0, 3, 3, 3, 0,
            /* E */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 2, 1, 0, 3, 3, 3, 0,
            /* F */ 2, 2, 0, 0, 2, 2, 2, 0, 1, 3, 1, 0, 3, 3, 3, 0,
    };

    private final AddressMode[] modePerOpcode = {
            /*        0    1    2    3    4    5    6    7    8    9    A    B    C    D    E    F */
            /* 0 */ IMP, IDX, IMP, IDX, ZPG, ZPG, ZPG, ZPG, IMP, IMD, ACC, IMD, ABS, ABS, ABS, ABS,
            /* 1 */ REL, INX, IMP, INX, ZPX, ZPX, ZPX, ZPX, IMP, ABY, IMP, ABY, ABX, ABX, ABX, ABX,
            /* 2 */ ABS, IDX, IMP, IDX, ZPG, ZPG, ZPG, ZPG, IMP, IMD, ACC, IMD, ABS, ABS, ABS, ABS,
            /* 3 */ REL, INX, IMP, INX, ZPX, ZPX, ZPX, ZPX, IMP, ABY, IMP, ABY, ABX, ABX, ABX, ABX,
            /* 4 */ IMP, IDX, IMP, IDX, ZPG, ZPG, ZPG, ZPG, IMP, IMD, ACC, IMD, ABS, ABS, ABS, ABS,
            /* 5 */ REL, INX, IMP, INX, ZPX, ZPX, ZPX, ZPX, IMP, ABY, IMP, ABY, ABX, ABX, ABX, ABX,
            /* 6 */ IMP, IDX, IMP, IDX, ZPG, ZPG, ZPG, ZPG, IMP, IMD, ACC, IMD, IND, ABS, ABS, ABS,
            /* 7 */ REL, INX, IMP, INX, ZPX, ZPX, ZPX, ZPX, IMP, ABY, IMP, ABY, ABX, ABX, ABX, ABX,
            /* 8 */ IMD, IDX, IMD, IDX, ZPG, ZPG, ZPG, ZPG, IMP, IMD, IMP, IMD, ABS, ABS, ABS, ABS,
            /* 9 */ REL, INX, IMP, INX, ZPX, ZPX, ZPY, ZPY, IMP, ABY, IMP, ABY, ABX, ABX, ABY, ABY,
            /* A */ IMD, IDX, IMD, IDX, ZPG, ZPG, ZPG, ZPG, IMP, IMD, IMP, IMD, ABS, ABS, ABS, ABS,
            /* B */ REL, INX, IMP, INX, ZPX, ZPX, ZPY, ZPY, IMP, ABY, IMP, ABY, ABX, ABX, ABY, ABY,
            /* C */ IMD, IDX, IMD, IDX, ZPG, ZPG, ZPG, ZPG, IMP, IMD, IMP, IMD, ABS, ABS, ABS, ABS,
            /* D */ REL, INX, IMP, INX, ZPX, ZPX, ZPX, ZPX, IMP, ABY, IMP, ABY, ABX, ABX, ABX, ABX,
            /* E */ IMD, IDX, IMD, IDX, ZPG, ZPG, ZPG, ZPG, IMP, IMD, IMP, IMD, ABS, ABS, ABS, ABS,
            /* F */ REL, INX, IMP, INX, ZPX, ZPX, ZPX, ZPX, IMP, ABY, IMP, ABY, ABX, ABX, ABX, ABX,
    };

    @SuppressWarnings("JavacQuirks")
    private final List<Consumer<Operand>> instructions = List.of(
            /*           0          1          2          3          4          5          6          7          8          9          A          B          C          D          E          F */
            /* 0 */ this::brk, this::ora, this::kil, this::slo, this::nop, this::ora, this::asl, this::slo, this::php, this::ora, this::asl, this::anc, this::nop, this::ora, this::asl, this::slo,
            /* 1 */ this::bpl, this::ora, this::kil, this::slo, this::nop, this::ora, this::asl, this::slo, this::clc, this::ora, this::nop, this::slo, this::nop, this::ora, this::asl, this::slo,
            /* 2 */ this::jsr, this::and, this::kil, this::rla, this::bit, this::and, this::rol, this::rla, this::plp, this::and, this::rol, this::anc, this::bit, this::and, this::rol, this::rla,
            /* 3 */ this::bmi, this::and, this::kil, this::rla, this::nop, this::and, this::rol, this::rla, this::sec, this::and, this::nop, this::rla, this::nop, this::and, this::rol, this::rla,
            /* 4 */ this::rti, this::eor, this::kil, this::sre, this::nop, this::eor, this::lsr, this::sre, this::pha, this::eor, this::lsr, this::alr, this::jmp, this::eor, this::lsr, this::sre,
            /* 5 */ this::bvc, this::eor, this::kil, this::sre, this::nop, this::eor, this::lsr, this::sre, this::cli, this::eor, this::nop, this::sre, this::nop, this::eor, this::lsr, this::sre,
            /* 6 */ this::rts, this::adc, this::kil, this::rra, this::nop, this::adc, this::ror, this::rra, this::pla, this::adc, this::ror, this::arr, this::jmp, this::adc, this::ror, this::rra,
            /* 7 */ this::bvs, this::adc, this::kil, this::rra, this::nop, this::adc, this::ror, this::rra, this::sei, this::adc, this::nop, this::rra, this::nop, this::adc, this::ror, this::rra,
            /* 8 */ this::nop, this::sta, this::nop, this::sax, this::sty, this::sta, this::stx, this::sax, this::dey, this::nop, this::txa, this::xaa, this::sty, this::sta, this::stx, this::sax,
            /* 9 */ this::bcc, this::sta, this::kil, this::ahx, this::sty, this::sta, this::stx, this::sax, this::tya, this::sta, this::txs, this::tas, this::shy, this::sta, this::shx, this::ahx,
            /* A */ this::ldy, this::lda, this::ldx, this::lax, this::ldy, this::lda, this::ldx, this::lax, this::tay, this::lda, this::tax, this::lax, this::ldy, this::lda, this::ldx, this::lax,
            /* B */ this::bcs, this::lda, this::kil, this::lax, this::ldy, this::lda, this::ldx, this::lax, this::clv, this::lda, this::tsx, this::las, this::ldy, this::lda, this::ldx, this::lax,
            /* C */ this::cpy, this::cmp, this::nop, this::dcp, this::cpy, this::cmp, this::dec, this::dcp, this::iny, this::cmp, this::dex, this::axs, this::cpy, this::cmp, this::dec, this::dcp,
            /* D */ this::bne, this::cmp, this::kil, this::dcp, this::nop, this::cmp, this::dec, this::dcp, this::cld, this::cmp, this::nop, this::dcp, this::nop, this::cmp, this::dec, this::dcp,
            /* E */ this::cpx, this::sbc, this::nop, this::isc, this::cpx, this::sbc, this::inc, this::isc, this::inx, this::sbc, this::nop, this::sbc, this::cpx, this::sbc, this::inc, this::isc,
            /* F */ this::beq, this::sbc, this::kil, this::isc, this::nop, this::sbc, this::inc, this::isc, this::sed, this::sbc, this::nop, this::isc, this::nop, this::sbc, this::inc, this::isc
    );

    public CPU(final Memory memory) {
        listeners = new ArrayList<>();
        this.memory = memory;
        this.init();
    }

    /**
     * Reset the CPU to its initial state.
     */
    public void reset() {
        this.init();
        this.notifyReset();
        this.cycles += 7;
    }

    /**
     * Updates the program counter.
     *
     * @param pc the new program counter.
     */
    public void setPC(final int pc) {
        var previous = this.pc;
        this.pc = pc & 0xFFFF;
        this.notifyRegisterChange(Register.PC, previous, this.pc);
    }

    /**
     * Request a NMI interrupt.
     */
    public void requestNMI() {
        this.pendingInterrupt = Interrupt.NMI;
    }

    /**
     * Request a IRQ interrupt.
     */
    public void requestIRQ() {
        if (this.getFlagI() == 0) {
            this.pendingInterrupt = Interrupt.IRQ;
        }
    }

    /**
     * Executes a single instruction.
     */
    public void step() {
        this.handlePendingInterrupt();

        var opcode = this.memory.read(this.pc);
        var addressMode = this.getMode(opcode);
        var operand = this.getOperand(addressMode);
        var instructionFunc = this.instructions.get(opcode);

        var instructionBytes = switch (this.lengthPerOpcode[opcode]) {
            case 1 -> new int[]{opcode, this.memory.read(this.pc + 1)};
            case 2 -> new int[]{
                    opcode,
                    this.memory.read(this.pc + 1),
                    this.memory.read(this.pc + 2),
            };
            default -> new int[]{opcode}; // 0
        };

        this.notifyStep(instructionBytes, operand.address(), operand.data(), addressMode);

        this.cycles += this.cyclesPerOpcode[opcode];
        this.pc += this.lengthPerOpcode[opcode];

        instructionFunc.accept(operand);
    }

    /**
     * Add an object to be notified on internal events.
     *
     * @param listener Object to listen to internal events.
     */
    public void addEventListener(final EventListener listener) {
        if (!this.listeners.contains(listener)) {
            this.listeners.add(listener);
        }
    }

    private void init() {
        this.setPC(this.readWord(0xFFFC));
        this.setSP(0xFD);
        this.setP(0x34);
        this.cycles = 7;
    }

    private void handlePendingInterrupt() {
        switch (this.pendingInterrupt) {
            case IRQ -> this.handleInterrupt(0xFFFE);
            case NMI -> this.handleInterrupt(0xFFFA);
        }
    }

    private void handleInterrupt(final int address) {
        this.pushWord(this.pc);
        this.push(this.p & 0x20 | 0x10);
        this.setPC(this.readWord(address));
        this.setFlagI(true);
        this.cycles += 7;
    }

    private void push(int data) {
        this.memory.write(0x100 + this.sp, data);
        this.sp--;
    }

    private void pushWord(int data) {
        this.push((data & 0xFF00) >> 8);
        this.push(data & 0xFF);
    }

    private int pop() {
        this.sp++;
        return this.memory.read(0x100 + this.sp);
    }

    private int popWord() {
        var low = this.pop();
        var hig = this.pop();

        return (hig << 8) | low;
    }

    private int readWord(int baseAddress) {
        var low = this.memory.read(baseAddress);
        var hig = this.memory.read(baseAddress + 1);
        return hig << 8 | low;
    }

    private int readWordBug(int baseAddress) {
        var bug = (baseAddress & 0xFF00) | ((baseAddress & 0xFF) + 1);
        var low = this.memory.read(baseAddress);
        var hig = this.memory.read(bug);
        return hig << 8 | low;
    }

    private void branch(final Operand operand) {
        this.pc = operand.address();
        this.cycles++; // extra cycle
    }

    private void brk(final Operand operand) {
        this.pushWord(this.pc);
        this.push(this.p | 0x30); // bit4 and bit5 high
        this.pc = this.readWord(0xFFFE);
        this.setFlagI(true);
    }

    private void bpl(final Operand operand) {
        if (this.getFlagN() == 0) {
            this.branch(operand);
        }
    }

    private void jsr(final Operand operand) {
        this.pushWord(this.pc - 1);
        this.pc = operand.address();
    }

    private void bmi(final Operand operand) {
        if (this.getFlagN() > 0) {
            this.branch(operand);
        }
    }

    private void rti(final Operand operand) {
        this.setP(this.pop());
        this.setPC(this.popWord());
    }

    private void bvc(final Operand operand) {
        if (this.getFlagV() == 0) {
            this.branch(operand);
        }
    }

    private void rts(final Operand operand) {
        this.setPC(this.popWord() + 1);
    }

    private void bvs(final Operand operand) {
        if (this.getFlagV() > 0) {
            this.branch(operand);
        }
    }

    private void nop(final Operand operand) { /* No Operation */ }

    private void bcc(final Operand operand) {
        if (this.getFlagC() == 0) {
            this.branch(operand);
        }
    }

    private void ldy(final Operand operand) {
        this.setY(operand.data());
        this.setFlagZ(this.y == 0);
        this.setFlagN(this.getBit(7, this.y));
    }

    private void bcs(final Operand operand) {
        if (this.getFlagC() > 0) {
            this.branch(operand);
        }
    }

    private void cpy(final Operand operand) {
        var res = this.y - operand.data();
        this.setFlagZ(res == 0);
        this.setFlagN(this.getBit(7, res));
        this.setFlagC(this.y >= operand.data());
    }

    private void bne(final Operand operand) {
        if (this.getFlagZ() == 0) {
            this.branch(operand);
        }
    }

    private void cpx(final Operand operand) {
        var res = this.x - operand.data();
        this.setFlagZ(res == 0);
        this.setFlagN(this.getBit(7, res));
        this.setFlagC(this.x >= operand.data());
    }

    private void beq(final Operand operand) {
        if (this.getFlagZ() > 0) {
            this.branch(operand);
        }
    }

    private void ora(final Operand operand) {
        this.setA(this.a | operand.data());
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void and(final Operand operand) {
        this.setA(this.a & operand.data());
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void eor(final Operand operand) {
        this.setA(this.a ^ operand.data());
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void adc(final Operand operand) {
        var a = this.a;
        var m = operand.data();
        var c = this.getFlagC();
        var r = a + m + c;

        this.setA(r);
        this.setFlagC(r > 0xFF);
        this.setFlagV(((a ^ m) & 0x80) == 0 && ((a ^ r) & 0x80) != 0);
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void sta(final Operand operand) {
        this.memory.write(operand.address(), this.a);
    }

    private void lda(final Operand operand) {
        this.setA(operand.data());
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void cmp(final Operand operand) {
        var res = this.a - operand.data();
        this.setFlagZ(res == 0);
        this.setFlagN(this.getBit(7, res));
        this.setFlagC(this.a >= operand.data());
    }

    private void sbc(final Operand operand) {
        var a = this.a;
        var m = operand.data();
        var c = this.getFlagC();
        var r = a - m - (1 - c);

        this.setA(r);
        this.setFlagC(r >= 0);
        this.setFlagV(((a ^ m) & 0x80) != 0 && ((a ^ this.a) & 0x80) != 0);
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void kil(final Operand operand) {
        this.illegal("kil");
    }

    private void illegal(final String name) {
        throw new RuntimeException(String.format("%s is an illegal opcode", name));
    }

    private void ldx(final Operand operand) {
        this.setX(operand.data());
        this.setFlagZ(this.x == 0);
        this.setFlagN(this.getBit(7, this.x));
    }

    private void slo(final Operand operand) {
        this.illegal("slo");
    }

    private void rla(final Operand operand) {
        this.illegal("rla");
    }

    private void sre(final Operand operand) {
        this.illegal("sre");
    }

    private void rra(final Operand operand) {
        this.illegal("rra");
    }

    private void sax(final Operand operand) {
        this.illegal("sax");
    }

    private void ahx(final Operand operand) {
        this.illegal("ahx");
    }

    private void lax(final Operand operand) {
        this.illegal("lax");
    }

    private void dcp(final Operand operand) {
        this.illegal("dcp");
    }

    private void isc(final Operand operand) {
        this.illegal("isc");
    }

    private void bit(final Operand operand) {
        this.setFlagZ((operand.data() & this.a) == 0);
        this.setFlagN(this.getBit(7, operand.data()));
        this.setFlagV(this.getBit(6, operand.data()));
    }

    private void sty(final Operand operand) {
        this.memory.write(operand.address(), this.y);
    }

    private void asl(final Operand operand) {
        var shifted = operand.data() << 1;

        if (operand.mode() == ACC) {
            this.setA(shifted);
        } else {
            this.memory.write(operand.address(), shifted);
        }

        this.setFlagC(this.getBit(7, operand.data()));
        this.setFlagZ(shifted == 0);
        this.setFlagN(this.getBit(7, shifted));
    }

    private void rol(final Operand operand) {
        var rotated = (operand.data() << 1) | this.getFlagC();

        if (operand.mode() == ACC) {
            this.setA(rotated);
        } else {
            this.memory.write(operand.address(), rotated);
        }

        this.setFlagC(this.getBit(7, operand.data()));
        this.setFlagZ(rotated == 0);
        this.setFlagN(this.getBit(7, rotated));
    }

    private void lsr(final Operand operand) {
        var shifted = operand.data() >> 1;

        if (operand.mode() == ACC) {
            this.setA(shifted);
        } else {
            this.memory.write(operand.address(), shifted);
        }

        this.setFlagC(this.getBit(0, operand.data()));
        this.setFlagZ(shifted == 0);
        this.setFlagN(this.getBit(7, shifted));
    }

    private void ror(final Operand operand) {
        var rotated = (operand.data() >> 1) | (this.getFlagC() << 7);

        if (operand.mode() == ACC) {
            this.setA(rotated);
        } else {
            this.memory.write(operand.address(), rotated);
        }

        this.setFlagC(this.getBit(0, operand.data()));
        this.setFlagZ(rotated == 0);
        this.setFlagN(this.getBit(7, rotated));
    }

    private void stx(final Operand operand) {
        this.memory.write(operand.address(), this.x);
    }

    private void dec(final Operand operand) {
        var res = operand.data() - 1;
        this.memory.write(operand.address(), res);
        this.setFlagZ(res == 0);
        this.setFlagN(this.getBit(7, res));
    }

    private void inc(final Operand operand) {
        var res = operand.data() + 1;
        this.memory.write(operand.address(), res);
        this.setFlagZ(res == 0);
        this.setFlagN(this.getBit(7, res));
    }

    private void php(final Operand operand) {
        this.push(this.p | 0x30); // bit4 and bit5 high
    }

    private void clc(final Operand operand) {
        this.setFlagC(false);
    }

    private void plp(final Operand operand) {
        this.setP(this.pop());
    }

    private void sec(final Operand operand) {
        this.setFlagC(true);
    }

    private void pha(final Operand operand) {
        this.push(this.a);
    }

    private void cli(final Operand operand) {
        this.setFlagI(false);
    }

    private void pla(final Operand operand) {
        this.setA(this.pop());
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void sei(final Operand operand) {
        this.setFlagI(true);
    }

    private void dey(final Operand operand) {
        this.setY(this.y - 1);
        this.setFlagZ(this.y == 0);
        this.setFlagN(this.getBit(7, this.y));
    }

    private void tya(final Operand operand) {
        this.setA(this.y);
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void tay(final Operand operand) {
        this.setY(this.a);
        this.setFlagZ(this.y == 0);
        this.setFlagN(this.getBit(7, this.y));
    }

    private void clv(final Operand operand) {
        this.setFlagV(false);
    }

    private void iny(final Operand operand) {
        this.setY(this.y + 1);
        this.setFlagZ(this.y == 0);
        this.setFlagN(this.getBit(7, this.y));
    }

    private void cld(final Operand operand) {
        this.setFlagD(false);
    }

    private void inx(final Operand operand) {
        this.setX(this.x + 1);
        this.setFlagZ(this.x == 0);
        this.setFlagN(this.getBit(7, this.x));
    }

    private void sed(final Operand operand) {
        this.setFlagD(true);
    }

    private void txa(final Operand operand) {
        this.setA(this.x);
        this.setFlagZ(this.a == 0);
        this.setFlagN(this.getBit(7, this.a));
    }

    private void txs(final Operand operand) {
        this.setSP(this.x);
    }

    private void tax(final Operand operand) {
        this.setX(this.a);
        this.setFlagZ(this.x == 0);
        this.setFlagN(this.getBit(7, this.x));
    }

    private void tsx(final Operand operand) {
        this.setX(this.sp);
        this.setFlagZ(this.x == 0);
        this.setFlagN(this.getBit(7, this.x));
    }

    private void dex(final Operand operand) {
        this.setX(this.x - 1);
        this.setFlagZ(this.x == 0);
        this.setFlagN(this.getBit(7, this.x));
    }

    private void anc(final Operand operand) {
        this.illegal("anc");
    }

    private void alr(final Operand operand) {
        this.illegal("alr");
    }

    private void arr(final Operand operand) {
        this.illegal("arr");
    }

    private void xaa(final Operand operand) {
        this.illegal("xaa");
    }

    private void tas(final Operand operand) {
        this.illegal("tas");
    }

    private void las(final Operand operand) {
        this.illegal("las");
    }

    private void axs(final Operand operand) {
        this.illegal("shy");
    }

    private void jmp(final Operand operand) {
        this.setPC(operand.address());
    }

    private void shy(final Operand operand) {
        this.illegal("shy");
    }

    private void shx(final Operand operand) {
        this.illegal("shx");
    }

    private boolean isDifferentPage(int a, int b) {
        return (a & 0xFF00) != (b & 0xFF00);
    }

    private Operand absolute() {
        var address = this.readWord(this.pc + 1);
        return new Operand(this.memory.read(address), address, ABS);
    }

    private Operand absoluteX() {
        var address = this.readWord(this.pc + 1) + this.x;

        if (this.isDifferentPage(address - this.x, address)) {
            this.cycles++;
        }

        return new Operand(this.memory.read(address), address, ABX);
    }

    private Operand absoluteY() {
        var address = this.readWord(this.pc + 1) + this.y;

        if (this.isDifferentPage(address - this.y, address)) {
            this.cycles++;
        }

        return new Operand(this.memory.read(address), address, ABY);
    }

    private Operand accumulator() {
        return new Operand(this.a, 0, AddressMode.ACC);
    }

    private Operand immediate() {
        var address = this.pc + 1;
        return new Operand(this.memory.read(address), address, AddressMode.IMD);
    }

    private Operand implied() {
        return new Operand(0, 0, AddressMode.IMP);
    }

    private Operand indexedIndirect() {
        var address = this.readWordBug(this.memory.read(this.pc + 1) + this.x);
        return new Operand(this.memory.read(address), address, IDX);
    }

    private Operand indirect() {
        var address = this.readWordBug(this.memory.read(this.pc + 1));
        return new Operand(this.memory.read(address), address, IND);
    }

    private Operand indirectIndexed() {
        var address = this.readWordBug(this.memory.read(this.pc + 1)) + this.y;

        if (this.isDifferentPage(address - this.y, address)) {
            this.cycles++;
        }

        return new Operand(this.memory.read(address), address, INX);
    }

    private Operand relative() {
        var offset = this.memory.read(this.pc + 1);
        var address = this.pc + 2 + offset;

        if (offset >= 0x80) {
            address -= 0x100;
        }

        return new Operand(this.memory.read(address), address, REL);
    }

    private Operand zeroPage() {
        var address = this.memory.read((this.pc + 1) & 0xFF);
        return new Operand(this.memory.read(address), address, AddressMode.ZPG);
    }

    private Operand zeroPageX() {
        var address = (zeroPage().data() + this.x) & 0xFF;
        return new Operand(this.memory.read(address), address, AddressMode.ZPX);
    }

    private Operand zeroPageY() {
        var address = (zeroPage().data() + this.y) & 0xFF;
        return new Operand(this.memory.read(address), address, AddressMode.ZPY);
    }

    private Operand getOperand(final AddressMode mode) {
        return switch (mode) {
            case ABS -> this.absolute();
            case ABX -> this.absoluteX();
            case ABY -> this.absoluteY();
            case ACC -> this.accumulator();
            case IMD -> this.immediate();
            case IMP -> this.implied();
            case IDX -> this.indexedIndirect();
            case IND -> this.indirect();
            case INX -> this.indirectIndexed();
            case REL -> this.relative();
            case ZPG -> this.zeroPage();
            case ZPX -> this.zeroPageX();
            case ZPY -> this.zeroPageY();
        };
    }

    private AddressMode getMode(int opcode) {
        return modePerOpcode[opcode];
    }

    private void setFlagN(int n) {
        this.setFlagN(n > 0);
    }

    private void setFlagN(boolean n) {
        var previous = this.p;

        this.p = n ? this.setBit(7, this.p) : this.clsBit(7, this.p);

        this.notifyRegisterChange(Register.P, previous, this.p);
    }

    private int getFlagN() {
        return this.getBit(7, this.p);
    }

    private void setFlagV(int v) {
        this.setFlagV(v > 0);
    }

    private void setFlagV(boolean v) {
        var previous = this.p;

        this.p = v ? this.setBit(6, this.p) : this.clsBit(6, this.p);

        this.notifyRegisterChange(Register.P, previous, this.p);
    }

    private int getFlagV() {
        return this.getBit(6, this.p);
    }

    private void setFlagD(boolean d) {
        var previous = this.p;

        this.p = d ? this.setBit(3, this.p) : this.clsBit(3, this.p);

        this.notifyRegisterChange(Register.P, previous, this.p);
    }

    private void setFlagI(boolean i) {
        var previous = this.p;

        this.p = i ? this.setBit(2, this.p) : this.clsBit(2, this.p);

        this.notifyRegisterChange(Register.P, previous, this.p);
    }

    private int getFlagI() {
        return this.getBit(2, this.p);
    }

    private void setFlagZ(boolean z) {
        var previous = this.p;

        this.p = z ? this.setBit(1, this.p) : this.clsBit(1, this.p);

        this.notifyRegisterChange(Register.P, previous, this.p);
    }

    private int getFlagZ() {
        return this.getBit(1, this.p);
    }

    private void setFlagC(int c) {
        this.setFlagC(c > 0);
    }

    private void setFlagC(boolean c) {
        var previous = this.p;

        this.p = c ? this.setBit(0, this.p) : this.clsBit(0, this.p);

        this.notifyRegisterChange(Register.P, previous, this.p);
    }

    private int getFlagC() {
        return this.getBit(0, this.p);
    }

    private int setBit(int nth, int value) {
        return value | (1 << nth);
    }

    private int clsBit(int nth, int value) {
        return value & ~(1 << nth);
    }

    private int getBit(int nth, int from) {
        return (from & (1 << nth)) >> nth;
    }

    private void setA(final int a) {
        var previous = this.a;
        this.a = a & 0xFF;
        this.notifyRegisterChange(Register.A, previous, this.a);
    }

    private void setX(final int x) {
        var previous = this.x;
        this.x = x & 0xFF;
        this.notifyRegisterChange(Register.X, previous, this.x);
    }

    private void setY(final int y) {
        var previous = this.y;
        this.y = y & 0xFF;
        this.notifyRegisterChange(Register.Y, previous, this.y);
    }

    private void setSP(final int sp) {
        var previous = this.sp;
        this.sp = sp & 0xFF;
        this.notifyRegisterChange(Register.SP, previous, this.sp);
    }

    private void setP(final int p) {
        var previous = this.p;
        this.p = p;
        this.notifyRegisterChange(Register.P, previous, this.p);
    }

    private void notifyRegisterChange(final Register register, final int previousValue, final int newValue) {
        this.listeners.forEach(l -> l.onRegisterChange(register, previousValue, newValue));
    }

    private void notifyStep(final int[] instruction, final int address, final int data, final AddressMode mode) {
        this.listeners.forEach(l -> l.onStep(instruction, address, data, mode, this.cycles));
    }

    private void notifyReset() {
        this.listeners.forEach(l -> l.onReset(this.cycles));
    }
}
