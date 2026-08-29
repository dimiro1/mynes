package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPU;

import java.util.List;
import java.util.Locale;

/**
 * What has to be true before a breakpoint stops the machine.
 * <p>
 * A comparison and nothing else -- {@code a == $10}, {@code [$0300] > 5}, {@code x != y}. Not an
 * expression language, and deliberately not: the question a conditional breakpoint answers is
 * "this address, but only on the pass where something is so", and every arithmetic operator added
 * to it would be another spelling to get wrong at the moment somebody is already confused about
 * their game. Anything more particular belongs in a watchpoint, or in reading the trace.
 * <p>
 * Both sides are terms, so the right hand one may be a register or a byte of memory rather than a
 * number. That falls out of having terms at all, and {@code a == x} is worth the nothing it costs.
 * <p>
 * <b>Memory is read through {@code peek}.</b> A condition is asked once per pass through its
 * address, and asking it through a real read would clear $2002, clock the controller ports and
 * drive MMC3's scanline counter -- so a breakpoint that never fired would still have changed the
 * game it was set on. {@link Disassembler.Memory} is the seam that says so, which is why it is the
 * one used here rather than a second interface of the same shape.
 */
public final class Condition {
    /**
     * How the two sides are compared. Longest first, because {@code <} is a prefix of {@code <=}
     * and a scan that took the short one would leave an {@code =} nobody can parse.
     */
    private enum Comparison {
        EQUAL("=="),
        NOT_EQUAL("!="),
        AT_MOST("<="),
        AT_LEAST(">="),
        LESS("<"),
        MORE(">");

        private final String symbol;

        Comparison(final String symbol) {
            this.symbol = symbol;
        }

        boolean holds(final int left, final int right) {
            return switch (this) {
                case EQUAL -> left == right;
                case NOT_EQUAL -> left != right;
                case AT_MOST -> left <= right;
                case AT_LEAST -> left >= right;
                case LESS -> left < right;
                case MORE -> left > right;
            };
        }
    }

    /**
     * One side of the comparison.
     */
    private sealed interface Term {
        int valueIn(CPU.State cpu, Disassembler.Memory memory);

        /**
         * How the term is spelled back in a listing, which is not always how it was typed: a point
         * somebody set as {@code A==16} lists as {@code a == $10}, so that two ways of writing the
         * same condition cannot look like two different conditions.
         */
        String text();
    }

    private record Register(String name) implements Term {
        @Override
        public int valueIn(final CPU.State cpu, final Disassembler.Memory memory) {
            return switch (name) {
                case "a" -> cpu.a();
                case "x" -> cpu.x();
                case "y" -> cpu.y();
                case "sp" -> cpu.sp();
                case "p" -> cpu.p();
                default -> cpu.pc();
            };
        }

        @Override
        public String text() {
            return name;
        }
    }

    private record Literal(int value) implements Term {
        @Override
        public int valueIn(final CPU.State cpu, final Disassembler.Memory memory) {
            return value;
        }

        @Override
        public String text() {
            return value > 0xFF
                    ? String.format("$%04X", value)
                    : String.format("$%02X", value);
        }
    }

    private record Cell(int address) implements Term {
        @Override
        public int valueIn(final CPU.State cpu, final Disassembler.Memory memory) {
            return memory.read(address) & 0xFF;
        }

        @Override
        public String text() {
            return String.format("[$%04X]", address);
        }
    }

    /**
     * The registers a term may name. {@code s} is accepted for the stack pointer because half the
     * 6502 documentation in the world spells it that way, and lists back as {@code sp}.
     */
    private static final List<String> REGISTERS = List.of("a", "x", "y", "sp", "p", "pc");

    private final Term left;
    private final Comparison comparison;
    private final Term right;

    private Condition(final Term left, final Comparison comparison, final Term right) {
        this.left = left;
        this.comparison = comparison;
        this.right = right;
    }

    /**
     * Reads one.
     *
     * @param text a comparison, as in {@code a == $10}. Whitespace anywhere or nowhere.
     * @throws IllegalArgumentException with a message worth showing somebody, since every caller of
     *                                  this is standing in front of one.
     */
    public static Condition parse(final String text) {
        var trimmed = text.trim();

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "a condition is a comparison, as in \"a == $10\".");
        }

        for (var comparison : Comparison.values()) {
            var at = indexOfOperator(trimmed, comparison.symbol);

            if (at < 0) {
                continue;
            }

            return new Condition(
                    term(trimmed.substring(0, at), trimmed),
                    comparison,
                    term(trimmed.substring(at + comparison.symbol.length()), trimmed));
        }

        throw new IllegalArgumentException(
                "\"" + trimmed + "\" compares nothing. The comparisons are ==, !=, <, <=, > and"
                        + " >=, as in \"a == $10\".");
    }

    /**
     * Whether the machine is in the state this describes.
     *
     * @param cpu    the registers as they stand.
     * @param memory somewhere to read a byte from without side effects -- {@code MMU::peek}.
     */
    public boolean holds(final CPU.State cpu, final Disassembler.Memory memory) {
        return comparison.holds(left.valueIn(cpu, memory), right.valueIn(cpu, memory));
    }

    /**
     * The condition as a listing shows it, which is the canonical spelling rather than the typed
     * one.
     */
    public String text() {
        return left.text() + " " + comparison.symbol + " " + right.text();
    }

    @Override
    public String toString() {
        return text();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof Condition that && text().equals(that.text());
    }

    @Override
    public int hashCode() {
        return text().hashCode();
    }

    // ================================================================================== internals

    /**
     * Where an operator starts, ignoring the {@code =} of a {@code !=}, {@code <=} or {@code >=}
     * that a shorter symbol would otherwise match the wrong half of.
     * <p>
     * Only {@code <} and {@code >} can go wrong that way, and only against their own two-character
     * forms, which the enum's order has already tried first. So the check is that the character
     * after a one-character operator is not an {@code =}.
     */
    private static int indexOfOperator(final String text, final String symbol) {
        var at = text.indexOf(symbol);

        if (at < 0 || symbol.length() > 1) {
            return at;
        }

        return at + 1 < text.length() && text.charAt(at + 1) == '=' ? -1 : at;
    }

    private static Term term(final String word, final String whole) {
        var trimmed = word.trim().toLowerCase(Locale.ROOT);

        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(
                    "\"" + whole.trim() + "\" is missing one side of its comparison.");
        }

        if (trimmed.equals("s")) {
            return new Register("sp");
        }

        if (REGISTERS.contains(trimmed)) {
            return new Register(trimmed);
        }

        if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
            return new Cell(number(trimmed.substring(1, trimmed.length() - 1), whole) & 0xFFFF);
        }

        return new Literal(number(trimmed, whole));
    }

    /**
     * A number, in whichever of the three ways an address is written around here.
     * <p>
     * Decimal unless it says otherwise, which is the rule the rest of the command line keeps. The
     * hex forms are the ones somebody reading a disassembly will type without thinking about it.
     */
    private static int number(final String word, final String whole) {
        var trimmed = word.trim();

        try {
            if (trimmed.startsWith("$")) {
                return Integer.parseInt(trimmed.substring(1), 16);
            }

            if (trimmed.startsWith("0x")) {
                return Integer.parseInt(trimmed.substring(2), 16);
            }

            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "\"" + trimmed + "\" in \"" + whole.trim() + "\" is neither a number nor one of"
                            + " the registers " + String.join(", ", REGISTERS) + ".");
        }
    }
}
