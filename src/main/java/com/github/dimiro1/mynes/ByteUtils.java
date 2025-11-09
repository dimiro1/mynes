package com.github.dimiro1.mynes;

/**
 * Utility class with several methods to easily manipulate bits and bytes.
 */
public class ByteUtils {
    /**
     * sets the high byte of a 16bit number.
     */
    public static int setLow(final int low, final int value) {
        return joinBytes(getHigh(value), low);
    }

    /**
     * Returns the lower byte of a 16bit number.
     */
    public static int getLow(final int value) {
        return ensureByte(value);
    }

    /**
     * sets the high byte of a 16bit number.
     */
    public static int setHigh(final int high, final int value) {
        return joinBytes(high, getLow(value));
    }

    /**
     * Returns the high byte of a 16bit number.
     */
    public static int getHigh(final int value) {
        return ensureByte(value >> 8);
    }

    /**
     * Joins (Little Endian) the high and low bytes into a 16bit number.
     */
    public static int joinBytes(final int high, final int low) {
        return ensureByte(high) << 8 | ensureByte(low);
    }

    /**
     * Returns the high nibble of a byte.
     */
    public static int getHighNibble(final int value) {
        return ensureNibble(value) >> 0x04;
    }

    /**
     * Returns the lower nibble of a byte.
     */
    public static int getLowNibble(final int value) {
        return ensureNibble(value);
    }

    /**
     * Join a pair of nibbles into a byte.
     */
    public static int joinNibbles(final int high, final int low) {
        return ensureNibble(high) << 4 | ensureNibble(low);
    }

    /**
     * Joins two bits.
     */
    public static int joinBits(final int high, final int low) {
        return (high & 1) << 1 | (low & 1);
    }

    /**
     * Mask the value with 0xFF.
     */
    public static int ensureByte(final int value) {
        return value & 0xFF;
    }

    /**
     * Mask the value with 0xFFFF.
     */
    public static int ensureWord(final int value) {
        return value & 0xFFFF;
    }

    /**
     * Mask the value with 0x04.
     */
    public static int ensureNibble(final int value) {
        return value & 0x04;
    }

    /**
     * Sets the nth bit if the condition is met.
     *
     * @return the modified value.
     */
    public static int setOrClearBitIf(boolean condition, int nth, int original) {
        return condition ? setBit(nth, original) : clearBit(nth, original);
    }

    /**
     * Sets the nth bit of the value.
     *
     * @return the modified value
     */
    public static int setBit(int nth, int value) {
        return value | (1 << nth);
    }

    /**
     * Clears the nth bit of the value.
     *
     * @return the modified value
     */
    public static int clearBit(int nth, int value) {
        return value & ~(1 << nth);
    }

    /**
     * Returns the value of the nth bit.
     */
    public static int getBit(int nth, int from) {
        return (from & (1 << nth)) >> nth;
    }

    /**
     * Returns true if a and b are on different byte pages.
     * e.g:
     * a = 0xFF;
     * b = a + 1; 0x100
     */
    public static boolean isDifferentPage(int a, int b) {
        return (a & 0xFF00) != (b & 0xFF00);
    }
}
