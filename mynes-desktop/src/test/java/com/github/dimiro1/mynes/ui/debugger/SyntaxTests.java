package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.Disassembler;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SyntaxTests {
    @Test
    void theTokensConcatenateBackToTheLine() {
        for (var line : List.of("LDA $05A4,X", "JMP ($FFFC)", "LDA ($20),Y", "ASL A", "*NOP $12,X",
                "BNE $809E", "AND #$7F", "RTS")) {
            assertEquals(line, Syntax.tokens(line).stream()
                    .map(Syntax.Token::text)
                    .collect(Collectors.joining()));
        }
    }

    @Test
    void anImmediateIsAValueAndAnOperandAddressIsAPlace() {
        assertEquals(
                List.of(Syntax.Kind.MATH, Syntax.Kind.TEXT, Syntax.Kind.IMMEDIATE),
                kinds("AND #$7F"));
        assertEquals(
                List.of(Syntax.Kind.DATA, Syntax.Kind.TEXT, Syntax.Kind.ADDRESS),
                kinds("STA $0778"));
    }

    @Test
    void indexRegistersAndBracketsAreTheirOwnPieces() {
        assertEquals(
                List.of(
                        Syntax.Kind.DATA, Syntax.Kind.TEXT,
                        Syntax.Kind.PUNCTUATION, Syntax.Kind.ADDRESS,
                        Syntax.Kind.PUNCTUATION, Syntax.Kind.PUNCTUATION,
                        Syntax.Kind.REGISTER),
                kinds("LDA ($20),Y"));
    }

    @Test
    void theAccumulatorIsARegisterRatherThanAHexDigit() {
        assertEquals(List.of(Syntax.Kind.MATH, Syntax.Kind.TEXT, Syntax.Kind.REGISTER),
                kinds("ASL A"));
    }

    @Test
    void anIllegalOpcodeIsMarkedWhateverItIsCalled() {
        assertEquals(Syntax.Kind.ILLEGAL, Syntax.classify("*NOP"));
        assertEquals(Syntax.Kind.ILLEGAL, Syntax.classify("*SBC"));
    }

    @Test
    void controlFlowDataAndArithmeticAreToldApart() {
        assertEquals(Syntax.Kind.FLOW, Syntax.classify("JSR"));
        assertEquals(Syntax.Kind.FLOW, Syntax.classify("BEQ"));
        assertEquals(Syntax.Kind.DATA, Syntax.classify("STX"));
        assertEquals(Syntax.Kind.DATA, Syntax.classify("PHA"));
        assertEquals(Syntax.Kind.MATH, Syntax.classify("CMP"));
        assertEquals(Syntax.Kind.MISC, Syntax.classify("SEI"));
        assertEquals(Syntax.Kind.MISC, Syntax.classify("NOP"));
    }

    /**
     * Every documented mnemonic the disassembler can print lands in a class that is not the
     * catch-all, so that a new one cannot quietly come out grey.
     */
    @Test
    void everyDocumentedMnemonicHasAClass() {
        var flagsAndNop = List.of("CLC", "SEC", "CLI", "SEI", "CLV", "CLD", "SED", "NOP");

        for (var opcode = 0; opcode < 256; opcode++) {
            if (Disassembler.isIllegal(opcode)) {
                continue;
            }

            var mnemonic = Disassembler.mnemonicOf(opcode);
            var kind = Syntax.classify(mnemonic);

            if (kind == Syntax.Kind.MISC) {
                assertEquals(true, flagsAndNop.contains(mnemonic), mnemonic + " fell through");
            }
        }
    }

    private static List<Syntax.Kind> kinds(final String line) {
        return Syntax.tokens(line).stream().map(Syntax.Token::kind).toList();
    }
}
