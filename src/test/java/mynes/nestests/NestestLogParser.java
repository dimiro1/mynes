package mynes.nestests;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class NestestLogParser {
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

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            Entry entry = (Entry) o;
            var result = pc == entry.pc
                    && a == entry.a
                    && x == entry.x
                    && y == entry.y
                    && p == entry.p
                    && sp == entry.sp
                    && cycle == entry.cycle
                    && opcode == entry.opcode
                    && opcodeLength == entry.opcodeLength;

            if (opcodeLength == 2) {
                result = result && (operand1 == entry.operand1);
            }

            if (opcodeLength == 3) {
                result = result && (operand1 == entry.operand1);
                result = result && (operand2 == entry.operand2);
            }

            return result;
        }

        @Override
        public int hashCode() {
            return Objects.hash(pc, a, x, y, p, sp, cycle, opcode, operand1, operand2, opcodeLength);
        }

        @Override
        public String toString() {
            var instruction = new String[opcodeLength];
            instruction[0] = String.format("%02X", opcode);

            if (opcodeLength == 2) {
                instruction[1] = String.format("%02X", operand1);
            }

            if (opcodeLength == 3) {
                instruction[1] = String.format("%02X", operand1);
                instruction[2] = String.format("%02X", operand2);
            }

            return String.format("Entry[pc=%04X, instruction=[%s], a=%02X, x=%02X, y=%02X, p=%02X, sp=%02X, cycle=%d]",
                    this.pc, String.join(", ", instruction), this.a, this.x, this.y, this.p, this.sp, this.cycle);
        }
    }

    public static List<Entry> parse(final InputStream log) {
        var pattern = Pattern.compile("" +
                        "^(?<pc>[\\w]{4}) {2}" + // program counter
                        "((?<op>[\\w]{2}) (?<op1>[\\w]{2})? (?<op2>[\\w]{2})?) " + // opcode operand1 operand2
                        "(.+) " + // Human readable opcode
                        "A:(?<a>[\\w]{2}) " + // A
                        "X:(?<x>[\\w]{2}) " + // X
                        "Y:(?<y>[\\w]{2}) " + // Y
                        "P:(?<p>[\\w]{2}) " + // P
                        "SP:(?<sp>[\\w]{2})" + // SP
                        "(.+)" + // Ignored PPU
                        "CYC:(?<cyc>[\\d]+)$", // Cycles
                Pattern.CASE_INSENSITIVE
        );
        var reader = new BufferedReader(new InputStreamReader(log));
        return reader.lines().map((line) -> {
            var matcher = pattern.matcher(line);
            if (matcher.matches()) {
                var pc = Integer.parseInt(matcher.group("pc"), 16);
                var a = Integer.parseInt(matcher.group("a"), 16);
                var x = Integer.parseInt(matcher.group("x"), 16);
                var y = Integer.parseInt(matcher.group("y"), 16);
                var p = Integer.parseInt(matcher.group("p"), 16);
                var sp = Integer.parseInt(matcher.group("sp"), 16);
                var cycle = Integer.parseInt(matcher.group("cyc"));
                var op = Integer.parseInt(matcher.group("op"), 16);

                var op1 = 0;
                var opLen = 1;
                if (matcher.group("op1") != null) {
                    op1 = Integer.parseInt(matcher.group("op1"), 16);
                    opLen++;
                }

                var op2 = 0;
                if (matcher.group("op2") != null) {
                    op2 = Integer.parseInt(matcher.group("op2"), 16);
                    opLen++;
                }

                return new Entry(pc, op, op1, op2, opLen, a, x, y, p, sp, cycle);
            }
            throw new RuntimeException(String.format("not able to parse line: %s", line));
        }).collect(Collectors.toList());
    }
}
