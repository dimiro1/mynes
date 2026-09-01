package com.github.dimiro1.mynes;

/**
 * Bit and byte arithmetic, in one place because every chip on the board does it.
 */
public class ByteUtils {
    public static int setLow(final int low, final int value) {
        return joinBytes(getHigh(value), low);
    }

    public static int getLow(final int value) {
        return ensureByte(value);
    }

    public static int setHigh(final int high, final int value) {
        return joinBytes(high, getLow(value));
    }

    public static int getHigh(final int value) {
        return ensureByte(value >> 8);
    }

    public static int joinBytes(final int high, final int low) {
        return ensureByte(high) << 8 | ensureByte(low);
    }

    public static int getHighNibble(final int value) {
        return ensureNibble(value >> 4);
    }

    public static int getLowNibble(final int value) {
        return ensureNibble(value);
    }

    public static int joinNibbles(final int high, final int low) {
        return ensureNibble(high) << 4 | ensureNibble(low);
    }

    public static int joinBits(final int high, final int low) {
        return (high & 1) << 1 | (low & 1);
    }

    public static int ensureByte(final int value) {
        return value & 0xFF;
    }

    public static int ensureWord(final int value) {
        return value & 0xFFFF;
    }

    public static int ensureNibble(final int value) {
        return value & 0x0F;
    }

    public static int setOrClearBitIf(boolean condition, int nth, int original) {
        return condition ? setBit(nth, original) : clearBit(nth, original);
    }

    public static int setBit(int nth, int value) {
        return value | (1 << nth);
    }

    public static int clearBit(int nth, int value) {
        return value & ~(1 << nth);
    }

    public static int getBit(int nth, int from) {
        return (from & (1 << nth)) >> nth;
    }

    /**
     * Whether stepping from {@code a} to {@code b} left the 256 byte page it started in -- $00FF to
     * $0100, say.
     * <p>
     * Worth a name of its own because the 6502 charges a cycle for it: an indexed read that crosses
     * a page fixes up the high byte on an extra cycle, and so does a branch that lands off its own
     * page.
     */
    public static boolean isDifferentPage(int a, int b) {
        return (a & 0xFF00) != (b & 0xFF00);
    }
}
