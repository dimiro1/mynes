package com.github.dimiro1.mynes.debug;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns bytes back into 6502.
 * <p>
 * All 256 opcodes, including the ones no assembler will emit. A debugger that stopped at the first
 * undocumented byte would be useless on exactly the games worth debugging -- and the CPU runs them,
 * so pretending they are not instructions would only move the confusion.
 * <p>
 * The mnemonics are the ones nestest's log uses, because that log is what this is tested against:
 * 8990 real instructions with their operands already formatted. That fixes two spellings that go
 * both ways elsewhere -- {@code ISB} rather than ISC, and {@code KIL} rather than JAM -- and it is
 * worth more than the preference it overrules.
 */
public final class Disassembler {
    /**
     * How an instruction says where its operand is, which is the only thing that decides how many
     * bytes it takes and how they are printed.
     */
    public enum Mode {
        IMPLIED(1),
        ACCUMULATOR(1),
        IMMEDIATE(2),
        ZERO_PAGE(2),
        ZERO_PAGE_X(2),
        ZERO_PAGE_Y(2),
        ABSOLUTE(3),
        ABSOLUTE_X(3),
        ABSOLUTE_Y(3),
        INDIRECT(3),
        INDEXED_INDIRECT(2),
        INDIRECT_INDEXED(2),
        RELATIVE(2),

        /**
         * BRK, which is its own mode for one reason: it is two bytes long.
         * <p>
         * The byte after the opcode is not used for anything, and most disassemblers therefore call
         * BRK one byte. The hardware disagrees -- it pushes PC+2 -- so the instruction after a BRK
         * really does start two bytes along, and a disassembler that said otherwise would put every
         * line after it at the wrong address. The byte is skipped when printing and counted when
         * moving on.
         */
        BREAK(2);

        private final int length;

        Mode(final int length) {
            this.length = length;
        }

        public int length() {
            return length;
        }
    }

    /**
     * Somewhere to read bytes from, so that this works against a running machine and against a copy
     * of one without knowing the difference.
     * <p>
     * Whatever is passed must not have side effects -- {@code MMU::peek}, never {@code MMU::read}.
     * Disassembling through a real read would clear $2002 and clock the controller ports, and on an
     * MMC3 cartridge it would drive the scanline counter from the debugger rather than from the
     * game.
     */
    @FunctionalInterface
    public interface Memory {
        int read(int address);
    }

    /**
     * One instruction.
     *
     * @param address where it starts.
     * @param bytes   the whole instruction, so a listing can show what it was made of.
     * @param text    the 6502, illegal opcodes marked with a leading {@code *} the way nestest's
     *                log marks them.
     */
    public record Line(int address, int[] bytes, String text) {
        /**
         * The bytes as a listing shows them: {@code "4C F5 C5"}.
         */
        public String hex() {
            var out = new StringBuilder(bytes.length * 3);

            for (var value : bytes) {
                if (!out.isEmpty()) {
                    out.append(' ');
                }

                out.append(String.format("%02X", value & 0xFF));
            }

            return out.toString();
        }
    }

    /**
     * The whole instruction set, one entry per opcode, in the order the opcodes run.
     * <p>
     * Eight characters each and always eight, so that the grid stays a grid and a wrong entry shows
     * up as a wrong shape: a {@code *} or a space, the mnemonic, a space, the mode. Kept as text
     * rather than as two parallel arrays because two arrays of 256 are two chances to shift one of
     * them by one and no chance of noticing.
     */
    private static final String[] TABLE = {
            /* 00 */ " BRK brk", " ORA izx", "*KIL imp", "*SLO izx",
            /* 04 */ "*NOP zpg", " ORA zpg", " ASL zpg", "*SLO zpg",
            /* 08 */ " PHP imp", " ORA imm", " ASL acc", "*ANC imm",
            /* 0C */ "*NOP abs", " ORA abs", " ASL abs", "*SLO abs",
            /* 10 */ " BPL rel", " ORA izy", "*KIL imp", "*SLO izy",
            /* 14 */ "*NOP zpx", " ORA zpx", " ASL zpx", "*SLO zpx",
            /* 18 */ " CLC imp", " ORA aby", "*NOP imp", "*SLO aby",
            /* 1C */ "*NOP abx", " ORA abx", " ASL abx", "*SLO abx",
            /* 20 */ " JSR abs", " AND izx", "*KIL imp", "*RLA izx",
            /* 24 */ " BIT zpg", " AND zpg", " ROL zpg", "*RLA zpg",
            /* 28 */ " PLP imp", " AND imm", " ROL acc", "*ANC imm",
            /* 2C */ " BIT abs", " AND abs", " ROL abs", "*RLA abs",
            /* 30 */ " BMI rel", " AND izy", "*KIL imp", "*RLA izy",
            /* 34 */ "*NOP zpx", " AND zpx", " ROL zpx", "*RLA zpx",
            /* 38 */ " SEC imp", " AND aby", "*NOP imp", "*RLA aby",
            /* 3C */ "*NOP abx", " AND abx", " ROL abx", "*RLA abx",
            /* 40 */ " RTI imp", " EOR izx", "*KIL imp", "*SRE izx",
            /* 44 */ "*NOP zpg", " EOR zpg", " LSR zpg", "*SRE zpg",
            /* 48 */ " PHA imp", " EOR imm", " LSR acc", "*ALR imm",
            /* 4C */ " JMP abs", " EOR abs", " LSR abs", "*SRE abs",
            /* 50 */ " BVC rel", " EOR izy", "*KIL imp", "*SRE izy",
            /* 54 */ "*NOP zpx", " EOR zpx", " LSR zpx", "*SRE zpx",
            /* 58 */ " CLI imp", " EOR aby", "*NOP imp", "*SRE aby",
            /* 5C */ "*NOP abx", " EOR abx", " LSR abx", "*SRE abx",
            /* 60 */ " RTS imp", " ADC izx", "*KIL imp", "*RRA izx",
            /* 64 */ "*NOP zpg", " ADC zpg", " ROR zpg", "*RRA zpg",
            /* 68 */ " PLA imp", " ADC imm", " ROR acc", "*ARR imm",
            /* 6C */ " JMP ind", " ADC abs", " ROR abs", "*RRA abs",
            /* 70 */ " BVS rel", " ADC izy", "*KIL imp", "*RRA izy",
            /* 74 */ "*NOP zpx", " ADC zpx", " ROR zpx", "*RRA zpx",
            /* 78 */ " SEI imp", " ADC aby", "*NOP imp", "*RRA aby",
            /* 7C */ "*NOP abx", " ADC abx", " ROR abx", "*RRA abx",
            /* 80 */ "*NOP imm", " STA izx", "*NOP imm", "*SAX izx",
            /* 84 */ " STY zpg", " STA zpg", " STX zpg", "*SAX zpg",
            /* 88 */ " DEY imp", "*NOP imm", " TXA imp", "*XAA imm",
            /* 8C */ " STY abs", " STA abs", " STX abs", "*SAX abs",
            /* 90 */ " BCC rel", " STA izy", "*KIL imp", "*AHX izy",
            /* 94 */ " STY zpx", " STA zpx", " STX zpy", "*SAX zpy",
            /* 98 */ " TYA imp", " STA aby", " TXS imp", "*TAS aby",
            /* 9C */ "*SHY abx", " STA abx", "*SHX aby", "*AHX aby",
            /* A0 */ " LDY imm", " LDA izx", " LDX imm", "*LAX izx",
            /* A4 */ " LDY zpg", " LDA zpg", " LDX zpg", "*LAX zpg",
            /* A8 */ " TAY imp", " LDA imm", " TAX imp", "*LAX imm",
            /* AC */ " LDY abs", " LDA abs", " LDX abs", "*LAX abs",
            /* B0 */ " BCS rel", " LDA izy", "*KIL imp", "*LAX izy",
            /* B4 */ " LDY zpx", " LDA zpx", " LDX zpy", "*LAX zpy",
            /* B8 */ " CLV imp", " LDA aby", " TSX imp", "*LAS aby",
            /* BC */ " LDY abx", " LDA abx", " LDX aby", "*LAX aby",
            /* C0 */ " CPY imm", " CMP izx", "*NOP imm", "*DCP izx",
            /* C4 */ " CPY zpg", " CMP zpg", " DEC zpg", "*DCP zpg",
            /* C8 */ " INY imp", " CMP imm", " DEX imp", "*AXS imm",
            /* CC */ " CPY abs", " CMP abs", " DEC abs", "*DCP abs",
            /* D0 */ " BNE rel", " CMP izy", "*KIL imp", "*DCP izy",
            /* D4 */ "*NOP zpx", " CMP zpx", " DEC zpx", "*DCP zpx",
            /* D8 */ " CLD imp", " CMP aby", "*NOP imp", "*DCP aby",
            /* DC */ "*NOP abx", " CMP abx", " DEC abx", "*DCP abx",
            /* E0 */ " CPX imm", " SBC izx", "*NOP imm", "*ISB izx",
            /* E4 */ " CPX zpg", " SBC zpg", " INC zpg", "*ISB zpg",
            /* E8 */ " INX imp", " SBC imm", " NOP imp", "*SBC imm",
            /* EC */ " CPX abs", " SBC abs", " INC abs", "*ISB abs",
            /* F0 */ " BEQ rel", " SBC izy", "*KIL imp", "*ISB izy",
            /* F4 */ "*NOP zpx", " SBC zpx", " INC zpx", "*ISB zpx",
            /* F8 */ " SED imp", " SBC aby", "*NOP imp", "*ISB aby",
            /* FC */ "*NOP abx", " SBC abx", " INC abx", "*ISB abx",
    };

    private static final String[] MNEMONICS = new String[256];
    private static final Mode[] MODES = new Mode[256];
    private static final boolean[] ILLEGAL = new boolean[256];

    static {
        for (var opcode = 0; opcode < TABLE.length; opcode++) {
            var entry = TABLE[opcode];

            ILLEGAL[opcode] = entry.charAt(0) == '*';
            MNEMONICS[opcode] = entry.substring(1, 4);
            MODES[opcode] = modeNamed(entry.substring(5));
        }
    }

    private Disassembler() {
    }

    /**
     * How many bytes the instruction at this opcode takes, including the opcode.
     */
    public static int lengthOf(final int opcode) {
        return MODES[opcode & 0xFF].length();
    }

    public static String mnemonicOf(final int opcode) {
        return MNEMONICS[opcode & 0xFF];
    }

    public static Mode modeOf(final int opcode) {
        return MODES[opcode & 0xFF];
    }

    /**
     * Whether this is one of the opcodes the chip does something useful with by accident rather
     * than on purpose.
     */
    public static boolean isIllegal(final int opcode) {
        return ILLEGAL[opcode & 0xFF];
    }

    /**
     * The instruction at an address.
     * <p>
     * Reads wrap at $FFFF rather than running off the end, because an instruction really can
     * straddle the top of the address space and a debugger asked to look at $FFFF should say what
     * is there rather than throw.
     */
    public static Line at(final Memory memory, final int address) {
        var start = address & 0xFFFF;
        var opcode = memory.read(start) & 0xFF;
        var length = lengthOf(opcode);
        var bytes = new int[length];

        for (var i = 0; i < length; i++) {
            bytes[i] = memory.read((start + i) & 0xFFFF) & 0xFF;
        }

        return new Line(start, bytes, text(start, bytes, opcode));
    }

    /**
     * The instruction somebody already has the bytes of.
     * <p>
     * For a tracer, which is handed the opcode and its operands by {@link
     * com.github.dimiro1.mynes.CPUEventListener} before the instruction runs and has no reason to
     * go back to memory for them -- and a positive reason not to, since the bytes it was given are
     * the ones the CPU is about to execute and a second look could be a look at a different bank.
     *
     * @param address where it starts.
     * @param bytes   the whole instruction, opcode first. Not checked against the length the opcode
     *                implies: what the caller saw is what ran.
     */
    public static Line of(final int address, final int[] bytes) {
        var start = address & 0xFFFF;

        return new Line(start, bytes, text(start, bytes, bytes[0] & 0xFF));
    }

    /**
     * A run of instructions, each starting where the last one ended.
     */
    public static List<Line> from(final Memory memory, final int address, final int count) {
        var lines = new ArrayList<Line>(count);
        var at = address & 0xFFFF;

        for (var i = 0; i < count; i++) {
            var line = at(memory, at);

            lines.add(line);
            at = (at + line.bytes().length) & 0xFFFF;
        }

        return lines;
    }

    // ================================================================================== internals

    private static String text(final int address, final int[] bytes, final int opcode) {
        var mnemonic = (ILLEGAL[opcode] ? "*" : "") + MNEMONICS[opcode];
        var operand = bytes.length > 1 ? bytes[1] : 0;
        var word = bytes.length > 2 ? operand | (bytes[2] << 8) : operand;

        return mnemonic + switch (MODES[opcode]) {
            case IMPLIED, BREAK -> "";
            case ACCUMULATOR -> " A";
            case IMMEDIATE -> " #$" + byteHex(operand);
            case ZERO_PAGE -> " $" + byteHex(operand);
            case ZERO_PAGE_X -> " $" + byteHex(operand) + ",X";
            case ZERO_PAGE_Y -> " $" + byteHex(operand) + ",Y";
            case ABSOLUTE -> " $" + wordHex(word);
            case ABSOLUTE_X -> " $" + wordHex(word) + ",X";
            case ABSOLUTE_Y -> " $" + wordHex(word) + ",Y";
            case INDIRECT -> " ($" + wordHex(word) + ")";
            case INDEXED_INDIRECT -> " ($" + byteHex(operand) + ",X)";
            case INDIRECT_INDEXED -> " ($" + byteHex(operand) + "),Y";

            // The address branched to rather than the offset stored, which is what the reader is
            // actually asking. A branch is relative to the instruction *after* it, so the two bytes
            // of the branch itself are part of the sum, and the offset is signed.
            case RELATIVE -> " $" + wordHex((address + 2 + (byte) operand) & 0xFFFF);
        };
    }

    private static String byteHex(final int value) {
        return String.format("%02X", value & 0xFF);
    }

    private static String wordHex(final int value) {
        return String.format("%04X", value & 0xFFFF);
    }

    private static Mode modeNamed(final String code) {
        return switch (code) {
            case "imp" -> Mode.IMPLIED;
            case "acc" -> Mode.ACCUMULATOR;
            case "imm" -> Mode.IMMEDIATE;
            case "zpg" -> Mode.ZERO_PAGE;
            case "zpx" -> Mode.ZERO_PAGE_X;
            case "zpy" -> Mode.ZERO_PAGE_Y;
            case "abs" -> Mode.ABSOLUTE;
            case "abx" -> Mode.ABSOLUTE_X;
            case "aby" -> Mode.ABSOLUTE_Y;
            case "ind" -> Mode.INDIRECT;
            case "izx" -> Mode.INDEXED_INDIRECT;
            case "izy" -> Mode.INDIRECT_INDEXED;
            case "rel" -> Mode.RELATIVE;
            case "brk" -> Mode.BREAK;
            default -> throw new IllegalStateException("no such addressing mode: " + code);
        };
    }
}
