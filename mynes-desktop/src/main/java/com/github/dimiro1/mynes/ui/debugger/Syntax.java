package com.github.dimiro1.mynes.ui.debugger;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Cuts one line of 6502 into the pieces a listing colours differently.
 * <p>
 * Nothing here knows about colours or about Swing, which is what lets it be tested without a
 * display and is why the renderer asks it for <em>kinds</em> rather than for paint. The kinds are
 * the ones a reader actually distinguishes when scanning a listing: where control goes, what moves,
 * what is computed, and whether an operand is a value or a place. Eight hues would be noise; four
 * classes of mnemonic and three of operand is what the eye can keep apart.
 * <p>
 * The input is exactly what {@link com.github.dimiro1.mynes.debug.Disassembler} prints, illegal
 * opcodes marked with a leading {@code *} the way nestest's log marks them, and nothing else is
 * ever handed in -- so this is a tokeniser for one known grammar rather than a parser with error
 * cases.
 */
final class Syntax {
    enum Kind {
        /**
         * A jump, a call, a return or a branch: where the program goes next.
         */
        FLOW,

        /**
         * A load, a store, a transfer or a push: bytes moving without changing.
         */
        DATA,

        /**
         * Arithmetic, logic, shifts and compares: bytes changing.
         */
        MATH,

        /**
         * Flag twiddles and NOP, the instructions a listing is usually reading past.
         */
        MISC,

        /**
         * An opcode the chip never documented.
         */
        ILLEGAL,

        /**
         * {@code #$7F}: a value.
         */
        IMMEDIATE,

        /**
         * {@code $0778}: a place.
         */
        ADDRESS,

        /**
         * {@code X}, {@code Y} or {@code A} in an operand.
         */
        REGISTER,

        /**
         * Brackets and commas.
         */
        PUNCTUATION,

        /**
         * The spaces, and anything not recognised.
         */
        TEXT
    }

    record Token(Kind kind, String text) {
    }

    private static final Set<String> FLOW = Set.of(
            "JMP", "JSR", "RTS", "RTI", "BRK",
            "BPL", "BMI", "BVC", "BVS", "BCC", "BCS", "BNE", "BEQ");

    private static final Set<String> DATA = Set.of(
            "LDA", "LDX", "LDY", "STA", "STX", "STY",
            "TAX", "TAY", "TXA", "TYA", "TSX", "TXS",
            "PHA", "PLA", "PHP", "PLP");

    private static final Set<String> MATH = Set.of(
            "ADC", "SBC", "AND", "ORA", "EOR", "BIT",
            "INC", "DEC", "INX", "INY", "DEX", "DEY",
            "ASL", "LSR", "ROL", "ROR", "CMP", "CPX", "CPY");

    private Syntax() {
    }

    /**
     * Which class a mnemonic belongs to, {@code *} included when the opcode is illegal.
     */
    static Kind classify(final String mnemonic) {
        if (mnemonic.startsWith("*")) {
            return Kind.ILLEGAL;
        }

        if (FLOW.contains(mnemonic)) {
            return Kind.FLOW;
        }

        if (DATA.contains(mnemonic)) {
            return Kind.DATA;
        }

        if (MATH.contains(mnemonic)) {
            return Kind.MATH;
        }

        return Kind.MISC;
    }

    /**
     * The line in order, the pieces concatenating back to the input.
     */
    static List<Token> tokens(final String line) {
        var out = new ArrayList<Token>();
        var space = line.indexOf(' ');
        var mnemonic = space < 0 ? line : line.substring(0, space);

        out.add(new Token(classify(mnemonic), mnemonic));

        if (space < 0) {
            return out;
        }

        out.add(new Token(Kind.TEXT, " "));
        operand(line.substring(space + 1), out);

        return out;
    }

    private static void operand(final String text, final List<Token> out) {
        var i = 0;

        while (i < text.length()) {
            var c = text.charAt(i);

            if (c == '#') {
                var end = hexEnd(text, i + 1);

                out.add(new Token(Kind.IMMEDIATE, text.substring(i, end)));
                i = end;
            } else if (c == '$') {
                var end = hexEnd(text, i);

                out.add(new Token(Kind.ADDRESS, text.substring(i, end)));
                i = end;
            } else if (c == 'A' || c == 'X' || c == 'Y') {
                out.add(new Token(Kind.REGISTER, String.valueOf(c)));
                i++;
            } else if (c == '(' || c == ')' || c == ',') {
                out.add(new Token(Kind.PUNCTUATION, String.valueOf(c)));
                i++;
            } else {
                out.add(new Token(Kind.TEXT, String.valueOf(c)));
                i++;
            }
        }
    }

    /**
     * Where the {@code $XX} or {@code $XXXX} starting at {@code from} ends.
     */
    private static int hexEnd(final String text, final int from) {
        var i = from;

        if (i < text.length() && text.charAt(i) == '$') {
            i++;
        }

        while (i < text.length() && Character.digit(text.charAt(i), 16) >= 0) {
            i++;
        }

        return i;
    }
}
