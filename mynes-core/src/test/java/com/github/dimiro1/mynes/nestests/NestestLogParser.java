package com.github.dimiro1.mynes.nestests;

import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Parser for nestest.log files.
 * <p>
 * The nestest ROM generates a log file with the following format for each instruction:
 * <pre>
 * C000  4C F5 C5  JMP $C5F5                       A:00 X:00 Y:00 P:24 SP:FD PPU:  0,  0 CYC:7
 * </pre>
 * <p>
 * This parser extracts the CPU state (PC, registers, flags, cycles) from each line
 * to enable cycle-accurate validation of CPU emulation.
 */
public class NestestLogParser {

    /**
     * Compiled regex pattern for parsing nestest log lines.
     * <p>
     * Pattern breakdown:
     * <ul>
     *   <li>PC: 4 hex digits at start</li>
     *   <li>Opcode + operands: 1-3 hex bytes</li>
     *   <li>Human-readable instruction (ignored)</li>
     *   <li>Registers: A, X, Y, P, SP (2 hex digits each)</li>
     *   <li>PPU info (ignored)</li>
     *   <li>CYC: decimal cycle count</li>
     * </ul>
     */
    private static final Pattern LOG_PATTERN = Pattern.compile(
            "^(?<pc>\\w{4}) {2}" +                           // Program counter
                    "(?<op>\\w{2}) (?<op1>\\w{2})? ?(?<op2>\\w{2})? " + // Opcode and operands
            "(.+) " +                                      // Human-readable instruction (ignored)
            "A:(?<a>\\w{2}) " +                           // Accumulator
            "X:(?<x>\\w{2}) " +                           // X register
            "Y:(?<y>\\w{2}) " +                           // Y register
            "P:(?<p>\\w{2}) " +                           // Processor status
            "SP:(?<sp>\\w{2})" +                          // Stack pointer
            "(.+)" +                                       // PPU info (ignored)
            "CYC:(?<cyc>\\d+)$",                          // Cycle count
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Represents a single CPU state entry from the nestest log.
     * <p>
     * Contains the complete CPU state at the moment an instruction is about to execute,
     * including program counter, instruction bytes, registers, flags, and cycle count.
     *
     * @param pc program counter
     * @param opcode the opcode byte
     * @param operand1 first operand byte (0 if not present)
     * @param operand2 second operand byte (0 if not present)
     * @param opcodeLength instruction length in bytes (1-3)
     * @param a accumulator register
     * @param x X index register
     * @param y Y index register
     * @param p processor status flags
     * @param sp stack pointer
     * @param cycle total CPU cycle count
     */
    public record Entry(
            int pc,
            int opcode,
            int operand1,
            int operand2,
            int opcodeLength,
            int a,
            int x,
            int y,
            int p,
            int sp,
            long cycle
    ) {
        /**
         * Custom equality check that only compares operands that are part of the instruction.
         * <p>
         * For example, a 1-byte instruction won't compare operand1 or operand2 values.
         *
         * @param o the object to compare
         * @return true if the entries represent the same CPU state
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Entry entry = (Entry) o;

            // Always compare base CPU state
            boolean result = pc == entry.pc
                    && a == entry.a
                    && x == entry.x
                    && y == entry.y
                    && p == entry.p
                    && sp == entry.sp
                    && cycle == entry.cycle
                    && opcode == entry.opcode
                    && opcodeLength == entry.opcodeLength;

            // Only compare operands if they're part of the instruction
            if (opcodeLength >= 2) {
                result = result && (operand1 == entry.operand1);
            }

            if (opcodeLength >= 3) {
                result = result && (operand2 == entry.operand2);
            }

            return result;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pc, opcode, operand1, operand2, opcodeLength, a, x, y, p, sp, cycle);
        }

        @Override
        public @NotNull String toString() {
            List<String> instruction = new ArrayList<>();

            // Build instruction bytes string based on length
            instruction.add(String.format("%02X", opcode));

            if (opcodeLength >= 2) {
                instruction.add(String.format("%02X", operand1));
            }

            if (opcodeLength >= 3) {
                instruction.add(String.format("%02X", operand2));
            }

            return String.format(
                    "Entry[pc=%04X, instruction=[%s], a=%02X, x=%02X, y=%02X, p=%02X, sp=%02X, cycle=%d]",
                    this.pc, String.join(", ", instruction), this.a, this.x, this.y, this.p, this.sp, this.cycle
            );
        }
    }

    /**
     * Parses a nestest log file into a list of CPU state entries.
     * <p>
     * Each line of the log file represents one instruction execution and is parsed
     * into an Entry containing the complete CPU state at that moment.
     *
     * @param log input stream of the nestest.log file
     * @return list of parsed CPU state entries
     * @throws NestestLogParseException if any line cannot be parsed
     */
    public static List<Entry> parse(final InputStream log) {
        var reader = new BufferedReader(new InputStreamReader(log));
        return reader.lines()
                .map(NestestLogParser::parseLine)
                .collect(Collectors.toList());
    }

    /**
     * Parses a single line from the nestest log.
     *
     * @param line the log line to parse
     * @return the parsed Entry
     * @throws NestestLogParseException if the line cannot be parsed
     */
    private static Entry parseLine(final String line) {
        try {
            var matcher = LOG_PATTERN.matcher(line);
            if (!matcher.matches()) {
                throw new NestestLogParseException(line);
            }

            // Parse required fields
            var pc = Integer.parseInt(matcher.group("pc"), 16);
            var a = Integer.parseInt(matcher.group("a"), 16);
            var x = Integer.parseInt(matcher.group("x"), 16);
            var y = Integer.parseInt(matcher.group("y"), 16);
            var p = Integer.parseInt(matcher.group("p"), 16);
            var sp = Integer.parseInt(matcher.group("sp"), 16);
            var cycle = Long.parseLong(matcher.group("cyc"));
            var opcode = Integer.parseInt(matcher.group("op"), 16);

            // Parse optional operands
            var operand1 = 0;
            var opcodeLength = 1;
            if (matcher.group("op1") != null) {
                operand1 = Integer.parseInt(matcher.group("op1"), 16);
                opcodeLength++;
            }

            var operand2 = 0;
            if (matcher.group("op2") != null) {
                operand2 = Integer.parseInt(matcher.group("op2"), 16);
                opcodeLength++;
            }

            return new Entry(pc, opcode, operand1, operand2, opcodeLength, a, x, y, p, sp, cycle);
        } catch (NumberFormatException e) {
            throw new NestestLogParseException(line, e);
        }
    }
}
