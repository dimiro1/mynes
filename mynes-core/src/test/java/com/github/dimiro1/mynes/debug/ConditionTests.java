package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPU;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConditionTests {
    /**
     * A machine standing still, so that every test below says out loud which register it is about.
     */
    private static final CPU.State STATE = new CPU.State(0x42, 0x10, 0x00, 0xFD, 0xC000, 0x24, 7);

    /**
     * Memory that answers with the low byte of the address, so a test can name any cell and know
     * what is in it without setting it up.
     */
    private static final Disassembler.Memory MEMORY = address -> address & 0xFF;

    @Test
    void aRegisterIsComparedAgainstALiteral() {
        assertTrue(holds("a == $42"));
        assertFalse(holds("a == $43"));
    }

    @Test
    void everyRegisterCanBeNamed() {
        assertTrue(holds("a == $42"));
        assertTrue(holds("x == $10"));
        assertTrue(holds("y == 0"));
        assertTrue(holds("sp == $FD"));
        assertTrue(holds("p == $24"));
        assertTrue(holds("pc == $C000"));
    }

    /**
     * Half the 6502 documentation in the world calls the stack pointer S, and somebody reading one
     * of those pages should not have to find out which half this is.
     */
    @Test
    void theStackPointerAnswersToBothOfItsNames() {
        assertTrue(holds("s == $FD"));
        assertEquals("sp == $FD", Condition.parse("s == $FD").text());
    }

    @Test
    void everyComparisonMeansWhatItSays() {
        assertTrue(holds("x == $10"));
        assertTrue(holds("x != $11"));
        assertTrue(holds("x < $11"));
        assertTrue(holds("x <= $10"));
        assertTrue(holds("x > $0F"));
        assertTrue(holds("x >= $10"));

        assertFalse(holds("x != $10"));
        assertFalse(holds("x < $10"));
        assertFalse(holds("x > $10"));
    }

    /**
     * {@code <} is a prefix of {@code <=}, so a scan that took the short one first would read
     * {@code x <= 5} as "x less than =5" and fail on the operand rather than on the operator.
     */
    @Test
    void theTwoCharacterComparisonsAreNotMistakenForTheirFirstHalves() {
        assertEquals("x <= $05", Condition.parse("x<=5").text());
        assertEquals("x >= $05", Condition.parse("x>=5").text());
        assertEquals("x != $05", Condition.parse("x!=5").text());
    }

    @Test
    void aCellOfMemoryIsATerm() {
        assertTrue(holds("[$0342] == $42"));
        assertFalse(holds("[$0342] == $43"));
    }

    /**
     * Terms on both sides, which is what makes {@code a == x} sayable -- and it costs nothing over
     * having terms at all.
     */
    @Test
    void bothSidesAreTerms() {
        assertTrue(holds("a == [$0342]"));
        assertTrue(holds("a > x"));
        assertFalse(holds("y > x"));
    }

    @Test
    void aNumberIsDecimalUnlessItSaysOtherwise() {
        assertTrue(holds("a == 66"));
        assertTrue(holds("a == $42"));
        assertTrue(holds("a == 0x42"));
    }

    @Test
    void spacingIsOptional() {
        assertTrue(holds("a==$42"));
        assertTrue(holds("   a   ==   $42   "));
    }

    /**
     * One spelling in the listing, whatever was typed, so that two ways of writing the same
     * condition cannot look like two different conditions in the points panel.
     */
    @Test
    void aConditionIsListedTheOneWayRound() {
        assertEquals("a == $42", Condition.parse("A==66").text());
        assertEquals("[$0300] != $00", Condition.parse("[768]!=0").text());
        assertEquals("pc >= $C000", Condition.parse("PC>=0xC000").text());
    }

    @Test
    void nonsenseIsRefusedWithSomethingWorthReading() {
        assertTrue(message("a").contains("compares nothing"));
        assertTrue(message("a == ").contains("missing one side"));
        assertTrue(message("q == 1").contains("neither a number nor one of the registers"));
        assertTrue(message("a = 1").contains("compares nothing"),
                "one equals sign is not a comparison, and guessing which was meant is worse");
    }

    private static boolean holds(final String text) {
        return Condition.parse(text).holds(STATE, MEMORY);
    }

    private static String message(final String text) {
        return assertThrows(IllegalArgumentException.class, () -> Condition.parse(text))
                .getMessage();
    }
}
