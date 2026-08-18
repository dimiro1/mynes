package com.github.dimiro1.mynes.cpu;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * One case from a Tom Harte SingleStepTests opcode file.
 * <p>
 * The JSON shape is:
 * <pre>
 * {
 *   "name": "b1 71 8b",
 *   "initial": {"pc": 9023, "s": 240, "a": 47, "x": 162, "y": 170, "p": 170,
 *               "ram": [[9023, 177], [9024, 113], ...]},
 *   "final":   {"pc": 9025, ...},
 *   "cycles":  [[9023, 177, "read"], [9024, 113, "read"], ...]
 * }
 * </pre>
 *
 * @param name     the case name; the first token is the opcode in hex.
 * @param initial  the machine state before the instruction runs.
 * @param expected the machine state after the instruction runs ({@code "final"} in the JSON,
 *                 which is not a usable Java identifier).
 * @param cycles   one entry per CPU cycle, describing the bus access of that cycle.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record HarteCase(
        String name,
        State initial,
        @JsonProperty("final") State expected,
        List<Cycle> cycles
) {

    /**
     * A machine state: the registers plus every byte of memory the case cares about.
     *
     * @param pc  the program counter.
     * @param s   the stack pointer.
     * @param a   the accumulator.
     * @param x   the x index register.
     * @param y   the y index register.
     * @param p   the status register.
     * @param ram {@code [address, value]} pairs. The initial and final states always list the
     *            same addresses, so the final state doubles as the complete set of memory the
     *            instruction is allowed to have modified.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    record State(int pc, int s, int a, int x, int y, int p, List<int[]> ram) {
    }

    /**
     * The bus access performed on one CPU cycle. Serialised as a three element array.
     *
     * @param address the address on the bus.
     * @param value   the byte transferred.
     * @param type    either {@code "read"} or {@code "write"}.
     */
    @JsonFormat(shape = JsonFormat.Shape.ARRAY)
    record Cycle(int address, int value, String type) {
        boolean isRead() {
            return "read".equals(type);
        }
    }
}
