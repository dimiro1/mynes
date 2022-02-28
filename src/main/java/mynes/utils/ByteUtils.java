package mynes.utils;

/**
 * Utility class with several methods to easily manipulate bits and bytes.
 */
public class ByteUtils {
    /**
     * Adds n to the lower bytes of value, returns the modified value.
     */
    public static int add2Low(final int value, final int n) {
        return joinBytes(getHigh(value), getLow(value) + n);
    }

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
     * Swaps the lower 4bits with the higher 4bits.
     * e.g: 1000 0001 -> 0001 1000
     */
    public static int swapNibble(final int value) {
        return ensureByte(value) << 4 | ensureByte(value) >> 4;
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
     * Returns true if the nth bit of the value is high.
     */
    public static boolean isBitHigh(int nth, int value) {
        return getBit(nth, value) > 0;
    }

    /**
     * Returns true if the nth bit of the value is low.
     */
    public static boolean isBitLow(int nth, int value) {
        return getBit(nth, value) == 0;
    }

    /**
     * Returns the signed value.
     */
    public static int unsignedToSigned(final int value) {
        return (byte) value;
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
