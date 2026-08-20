package com.github.dimiro1.mynes.cheat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The scramble, checked against codes somebody else decoded first.
 * <p>
 * Three of the four below are worked examples out of the two references the class names, which is the
 * only honest way to test a bit shuffle: an implementation checked against its own arithmetic is
 * checked against nothing, and every one of these would still pass if two of the shifts had been
 * swapped for each other. {@code SXIOPO} is the fourth and is the one worth keeping if the others ever
 * go -- it is the Super Mario Bros. infinite lives code, and it is $AD over $91D9 because $AD is
 * {@code LDA abs} and it is being written over the {@code DEC abs} that takes the life.
 */
class GameGenieCodeTests {

    @Test
    void theSixLetterExampleDecodesToTheAddressAndValueItIsPublishedWith() {
        var code = GameGenieCode.decode("GOSSIP");

        assertEquals(0xD1DD, code.address());
        assertEquals(0x14, code.value());
        assertEquals(GameGenieCode.NO_COMPARE, code.compare());
        assertFalse(code.hasCompare());
    }

    @Test
    void theEightLetterExampleAlsoCarriesTheByteTheCartridgeMustAnswerWith() {
        var code = GameGenieCode.decode("ZEXPYGLA");

        assertEquals(0x94A7, code.address());
        assertEquals(0x02, code.value());
        assertEquals(0x03, code.compare());
        assertTrue(code.hasCompare());
    }

    @Test
    void theInfiniteLivesCodeWritesAnLdaOverTheDecThatTakesOne() {
        var code = GameGenieCode.decode("SXIOPO");

        assertEquals(0x91D9, code.address());
        assertEquals(0xAD, code.value());
    }

    /**
     * The spare bit, pinned. Bit 3 of the third letter is read by nothing, so these two spellings are
     * one code -- which is why there is no {@code encode} to hand the letters back.
     */
    @Test
    void theThirdLettersTopBitIsNotReadAtAll() {
        var published = GameGenieCode.decode("GOSSIP");
        var withTheSpareBitClear = GameGenieCode.decode("GOISIP");

        assertEquals(published.address(), withTheSpareBitClear.address());
        assertEquals(published.value(), withTheSpareBitClear.value());
    }

    @Test
    void aCodeCanBeTypedInLowerCaseAndComesBackInUpper() {
        var code = GameGenieCode.decode("sxiopo");

        assertEquals("SXIOPO", code.text());
        assertEquals(GameGenieCode.decode("SXIOPO"), code, "so a list finds one it already holds");
    }

    @Test
    void surroundingSpaceIsNotPartOfTheCode() {
        assertEquals(GameGenieCode.decode("SXIOPO"), GameGenieCode.decode("  SXIOPO\t"));
    }

    /**
     * Fifteen address bits and a $8000 base is the whole of what the cartridge port carries, so there
     * is no code for anywhere else -- not for cartridge RAM at $6000, and not for the console's own
     * memory. Every one of the 65536 six letter codes lands in PRG ROM.
     */
    @Test
    void everyCodeThereIsAddressesProgramRom() {
        var alphabet = "APZLGITYEOXUKSVN";

        for (var a : alphabet.toCharArray()) {
            for (var b : alphabet.toCharArray()) {
                for (var c : alphabet.toCharArray()) {
                    var code = GameGenieCode.decode("" + a + b + c + a + b + c);

                    assertTrue(
                            code.address() >= 0x8000 && code.address() <= 0xFFFF,
                            code.text() + " landed outside PRG ROM, at $"
                                    + Integer.toHexString(code.address()));
                    assertTrue(code.value() >= 0 && code.value() <= 0xFF);
                }
            }
        }
    }

    @Test
    void aLetterThatIsNotOneOfTheSixteenIsRefused() {
        // B, C, D and R are the ones people reach for, and none of them is in the alphabet.
        var thrown = assertThrows(
                InvalidGameGenieCodeException.class, () -> GameGenieCode.decode("GOSSIB"));

        assertTrue(thrown.getMessage().contains("APZLGITYEOXUKSVN"), thrown.getMessage());
    }

    @Test
    void aCodeThatIsTheWrongLengthIsRefused() {
        assertThrows(InvalidGameGenieCodeException.class, () -> GameGenieCode.decode("GOSSI"));
        assertThrows(InvalidGameGenieCodeException.class, () -> GameGenieCode.decode("GOSSIPA"));
        assertThrows(InvalidGameGenieCodeException.class, () -> GameGenieCode.decode(""));
        assertThrows(InvalidGameGenieCodeException.class, () -> GameGenieCode.decode(null));
    }

    @Test
    void aSixLetterCodeAnswersWithItsOwnByteWhateverTheCartridgeSaid() {
        var code = GameGenieCode.decode("SXIOPO");

        assertEquals(0xAD, code.substitute(0xCE));
        assertEquals(0xAD, code.substitute(0x00));
    }

    @Test
    void anEightLetterCodeLeavesTheCartridgeAloneWhenTheComparisonFails() {
        var code = GameGenieCode.decode("ZEXPYGLA");

        assertEquals(0x02, code.substitute(0x03), "the bank it was written for");
        assertEquals(0x7B, code.substitute(0x7B), "and any other bank is untouched");
    }

    @Test
    void aCodeSaysWhatItDoesWhenItIsPrinted() {
        assertEquals("SXIOPO -> $91D9 = $AD", GameGenieCode.decode("SXIOPO").toString());
        assertEquals(
                "ZEXPYGLA -> $94A7 = $02 if $03", GameGenieCode.decode("ZEXPYGLA").toString());
    }
}
