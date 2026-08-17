package com.github.dimiro1.mynes;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the bit and byte helpers.
 * <p>
 * These are used by nearly every other class, so a wrong mask here surfaces somewhere else
 * entirely: a broken {@code ensureNibble} made {@link Cart} read every mapper number as zero,
 * which looked like an unrelated cartridge bug.
 */
public class ByteUtilsTests {

    @ParameterizedTest
    @CsvSource({
            "0x0000, 0x00",
            "0x00FF, 0xFF",
            "0xABCD, 0xCD",
            "0x1234, 0x34",
            "0xFF00, 0x00",
    })
    void getLow(final int value, final int expected) {
        assertEquals(expected, ByteUtils.getLow(value));
    }

    @ParameterizedTest
    @CsvSource({
            "0x0000, 0x00",
            "0x00FF, 0x00",
            "0xABCD, 0xAB",
            "0x1234, 0x12",
            "0xFF00, 0xFF",
    })
    void getHigh(final int value, final int expected) {
        assertEquals(expected, ByteUtils.getHigh(value));
    }

    @ParameterizedTest
    @CsvSource({
            "0x00, 0xABCD, 0xAB00",
            "0xEF, 0xABCD, 0xABEF",
            "0x1FF, 0xABCD, 0xABFF",
    })
    void setLow(final int low, final int value, final int expected) {
        assertEquals(expected, ByteUtils.setLow(low, value));
    }

    @ParameterizedTest
    @CsvSource({
            "0x00, 0xABCD, 0x00CD",
            "0xEF, 0xABCD, 0xEFCD",
            "0x1FF, 0xABCD, 0xFFCD",
    })
    void setHigh(final int high, final int value, final int expected) {
        assertEquals(expected, ByteUtils.setHigh(high, value));
    }

    @ParameterizedTest
    @CsvSource({
            "0x00, 0x00, 0x0000",
            "0xAB, 0xCD, 0xABCD",
            "0xFF, 0xFF, 0xFFFF",
            "0x1AB, 0x1CD, 0xABCD",
    })
    void joinBytes(final int high, final int low, final int expected) {
        assertEquals(expected, ByteUtils.joinBytes(high, low));
    }

    @ParameterizedTest
    @CsvSource({
            "0x00, 0x0",
            "0x0F, 0x0",
            "0x10, 0x1",
            "0xA5, 0xA",
            "0xF0, 0xF",
            "0xFF, 0xF",
    })
    void getHighNibble(final int value, final int expected) {
        assertEquals(expected, ByteUtils.getHighNibble(value));
    }

    @ParameterizedTest
    @CsvSource({
            "0x00, 0x0",
            "0x0F, 0xF",
            "0x10, 0x0",
            "0xA5, 0x5",
            "0xFF, 0xF",
    })
    void getLowNibble(final int value, final int expected) {
        assertEquals(expected, ByteUtils.getLowNibble(value));
    }

    @ParameterizedTest
    @CsvSource({
            "0x0, 0x0, 0x00",
            "0xA, 0x5, 0xA5",
            "0xF, 0xF, 0xFF",
            "0xFA, 0xF5, 0xA5",
    })
    void joinNibbles(final int high, final int low, final int expected) {
        assertEquals(expected, ByteUtils.joinNibbles(high, low));
    }

    @ParameterizedTest
    @CsvSource({
            "0x00, 0x00",
            "0x0F, 0x0F",
            "0xF0, 0x00",
            "0xFF, 0x0F",
    })
    void ensureNibble(final int value, final int expected) {
        assertEquals(expected, ByteUtils.ensureNibble(value));
    }

    @ParameterizedTest
    @CsvSource({
            "0, 0, 0",
            "0, 1, 1",
            "1, 0, 2",
            "1, 1, 3",
            "2, 2, 0",
    })
    void joinBits(final int high, final int low, final int expected) {
        assertEquals(expected, ByteUtils.joinBits(high, low));
    }

    @ParameterizedTest
    @CsvSource({
            "0x0000, 0x00",
            "0x00FF, 0xFF",
            "0xFFFF, 0xFF",
            "0x1234, 0x34",
    })
    void ensureByte(final int value, final int expected) {
        assertEquals(expected, ByteUtils.ensureByte(value));
    }

    @ParameterizedTest
    @CsvSource({
            "0x0000, 0x0000",
            "0xFFFF, 0xFFFF",
            "0x1FFFF, 0xFFFF",
            "0x12345, 0x2345",
    })
    void ensureWord(final int value, final int expected) {
        assertEquals(expected, ByteUtils.ensureWord(value));
    }

    @Test
    void setAndClearEveryBit() {
        for (var bit = 0; bit < 8; bit++) {
            assertEquals(1 << bit, ByteUtils.setBit(bit, 0x00), "setting bit " + bit);
            assertEquals(0xFF - (1 << bit), ByteUtils.clearBit(bit, 0xFF), "clearing bit " + bit);
            assertEquals(1, ByteUtils.getBit(bit, 0xFF), "reading set bit " + bit);
            assertEquals(0, ByteUtils.getBit(bit, 0x00), "reading clear bit " + bit);
        }
    }

    @Test
    void getBitReturnsOneNotTheMask() {
        // A common slip is returning the masked value, which is 1 << nth rather than 1.
        assertEquals(1, ByteUtils.getBit(7, 0x80));
        assertEquals(1, ByteUtils.getBit(6, 0x40));
    }

    @ParameterizedTest
    @CsvSource({
            "true,  3, 0x00, 0x08",
            "true,  3, 0x08, 0x08",
            "false, 3, 0xFF, 0xF7",
            "false, 3, 0xF7, 0xF7",
    })
    void setOrClearBitIf(
            final boolean condition,
            final int bit,
            final int original,
            final int expected
    ) {
        assertEquals(expected, ByteUtils.setOrClearBitIf(condition, bit, original));
    }

    @Test
    void isDifferentPage() {
        assertFalse(ByteUtils.isDifferentPage(0x0000, 0x00FF));
        assertTrue(ByteUtils.isDifferentPage(0x00FF, 0x0100));
        assertFalse(ByteUtils.isDifferentPage(0xC123, 0xC1FF));
        assertTrue(ByteUtils.isDifferentPage(0xC1FF, 0xC200));
    }
}
