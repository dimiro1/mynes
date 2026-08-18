package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPU;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Checks the disassembler against nestest's log, which is a reference listing of 8990 real
 * instructions that happens to already be vendored here for the CPU's own tests.
 * <p>
 * Every line carries the bytes it was made from as well as the text they came out as, so this needs
 * no ROM and no machine: each line is its own little memory. That covers 73 mnemonics including ten
 * illegal families, every addressing mode the log reaches, and -- because the byte column says how
 * long each instruction was -- the length table as well.
 */
class DisassemblerTests {
    private static final Path LOG = Path.of("src/test/resources/nestest/nestest.log");

    /**
     * Where each column of the log starts. Fixed width, and the {@code *} that marks an illegal
     * opcode sits in the column immediately before the text rather than inside it.
     */
    private static final int BYTES_FROM = 6;
    private static final int BYTES_TO = 14;
    private static final int ILLEGAL_MARK = 15;
    private static final int TEXT_FROM = 16;
    private static final int TEXT_TO = 48;

    @Test
    void theDisassemblyAgreesWithNestestsLog() throws Exception {
        var lines = Files.readAllLines(LOG);
        var checked = 0;

        for (var line : lines) {
            var address = Integer.parseInt(line.substring(0, 4), 16);
            var bytes = bytesOf(line);

            // Each line is a memory holding exactly its own instruction. Anything outside it would
            // be the disassembler having got the length wrong, which is worth failing on rather
            // than papering over with a zero.
            Disassembler.Memory memory = at -> {
                var offset = at - address;

                if (offset < 0 || offset >= bytes.length) {
                    throw new AssertionError(String.format(
                            "read $%04X while disassembling $%04X, which is %d bytes long: %s",
                            at, address, bytes.length, line));
                }

                return bytes[offset];
            };

            var disassembled = Disassembler.at(memory, address);

            assertEquals(expectedText(line), disassembled.text(), line);
            assertEquals(bytes.length, disassembled.bytes().length, line);

            checked++;
        }

        assertEquals(8991, checked, "the whole log should have been read");
    }

    @Test
    void everyOpcodeHasAMnemonicAndAMode() {
        for (var opcode = 0; opcode < 256; opcode++) {
            var which = String.format("$%02X", opcode);

            assertNotNull(Disassembler.modeOf(opcode), which);
            assertEquals(3, Disassembler.mnemonicOf(opcode).length(), which);
        }
    }

    /**
     * Two tables written from different sources, which is the point of not having the CPU read this
     * one: if they agree on all 256 opcodes then both are probably right, where a single table read
     * twice would only ever agree with itself.
     */
    @Test
    void theLengthsAgreeWithTheCPUsOwnTable() {
        for (var opcode = 0; opcode < 256; opcode++) {
            assertEquals(CPU.lengthOf(opcode), Disassembler.lengthOf(opcode),
                    String.format("$%02X %s", opcode, Disassembler.mnemonicOf(opcode)));
        }
    }

    @Test
    void aBranchIsShownAsTheAddressItGoesTo() {
        // BCC +3 at $C742 lands on $C747: two bytes for the branch itself, then the offset.
        assertEquals("BCC $C747", at(0xC742, 0x90, 0x03).text());

        // And backwards, because the offset is signed.
        assertEquals("BCC $C737", at(0xC742, 0x90, 0xF3).text());
    }

    @Test
    void breakTakesTheByteAfterItEvenThoughItPrintsNothing() {
        var line = at(0xC000, 0x00, 0xEA);

        assertEquals("BRK", line.text());
        assertEquals(2, line.bytes().length, "the instruction after a BRK starts two bytes along");
    }

    @Test
    void anIllegalOpcodeIsMarkedTheWayNestestMarksIt() {
        assertTrue(Disassembler.isIllegal(0x04));
        assertEquals("*NOP $A9", at(0xC6BD, 0x04, 0xA9).text());

        assertFalse(Disassembler.isIllegal(0xEA));
        assertEquals("NOP", at(0xC72D, 0xEA).text());
    }

    @Test
    void aRunOfInstructionsStartsWhereTheLastOneEnded() {
        Disassembler.Memory memory = at -> switch (at) {
            case 0xC000 -> 0xA2;   // LDX #$00
            case 0xC001 -> 0x00;
            case 0xC002 -> 0xEA;   // NOP
            case 0xC003 -> 0x4C;   // JMP $C5F5
            case 0xC004 -> 0xF5;
            case 0xC005 -> 0xC5;
            default -> 0;
        };

        var lines = Disassembler.from(memory, 0xC000, 3);

        assertEquals(0xC000, lines.get(0).address());
        assertEquals(0xC002, lines.get(1).address());
        assertEquals(0xC003, lines.get(2).address());
        assertEquals("JMP $C5F5", lines.get(2).text());
    }

    @Test
    void anInstructionAtTheTopOfMemoryWrapsRatherThanFalling() {
        Disassembler.Memory memory = at -> switch (at) {
            case 0xFFFF -> 0x4C;
            case 0x0000 -> 0x34;
            case 0x0001 -> 0x12;
            default -> 0;
        };

        assertEquals("JMP $1234", Disassembler.at(memory, 0xFFFF).text());
    }

    @Test
    void theBytesAreShownTheWayAListingShowsThem() {
        assertEquals("4C F5 C5", at(0xC000, 0x4C, 0xF5, 0xC5).hex());
        assertEquals("EA", at(0xC000, 0xEA).hex());
    }

    // ================================================================================== internals

    private static Disassembler.Line at(final int address, final int... bytes) {
        return Disassembler.at(what -> bytes[what - address], address);
    }

    private static int[] bytesOf(final String line) {
        var field = line.substring(BYTES_FROM, BYTES_TO).trim().split(" ");
        var bytes = new int[field.length];

        for (var i = 0; i < field.length; i++) {
            bytes[i] = Integer.parseInt(field[i], 16);
        }

        return bytes;
    }

    /**
     * The text nestest printed, with the two things a static disassembler cannot know taken off.
     * <p>
     * The log annotates each line with where the operand actually pointed and what was there --
     * {@code LDA ($80,X) @ 80 = 0200 = 5A} -- which is a running machine's knowledge, not a
     * listing's. Whichever of the two markers comes first ends the part that can be compared.
     */
    private static String expectedText(final String line) {
        var text = line.substring(TEXT_FROM, TEXT_TO);
        var equals = text.indexOf(" = ");
        var at = text.indexOf(" @ ");
        var end = equals < 0 ? at : at < 0 ? equals : Math.min(equals, at);

        if (end >= 0) {
            text = text.substring(0, end);
        }

        return (line.charAt(ILLEGAL_MARK) == '*' ? "*" : "") + text.trim();
    }
}
