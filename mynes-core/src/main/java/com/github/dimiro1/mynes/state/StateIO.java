package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.ByteUtils;

import java.io.ByteArrayOutputStream;

/**
 * One list of fields, read or written depending on which way this is pointing.
 * <p>
 * Every part of the console has a single {@code serialize(StateIO)} rather than a reader and a
 * writer, and the assignment form is what makes that work:
 * <pre>
 *     a = io.u8(a);
 *     pc = io.u16(pc);
 *     io.bytes(oam);
 * </pre>
 * Saving, {@link #u8} writes the byte and hands the same value straight back, so the assignment is
 * a no-op. Loading, it ignores what it was given and returns what it read. A field can therefore
 * only be forgotten in both directions at once -- which the divergence test catches, and which a
 * mismatched pair of read and write lists would not.
 * <p>
 * The other half of the bargain is what happens at the end of a chunk. A read past it returns
 * <em>the value that was passed in</em>, so a field that a shorter chunk never mentions keeps
 * whatever it already held: the power-on default on a freshly built machine, and its current value
 * on a running one. That is what makes a field appended to a chunk loadable from a file written
 * before it existed, and it is a good deal better than the zero a plain stream would give -- zero
 * being actively wrong for a noise channel's shift register, a DMC's bit counter, or an MMC1's
 * control register.
 * <p>
 * Multi-byte values are big-endian, because that is what reads correctly in a hex dump.
 */
public final class StateIO {

    /**
     * Where a write goes. Null when reading.
     */
    private final ByteArrayOutputStream buffer;

    /**
     * Where a read comes from. Null when writing.
     */
    private final byte[] payload;

    private int position;

    private StateIO(final ByteArrayOutputStream buffer, final byte[] payload) {
        this.buffer = buffer;
        this.payload = payload;
    }

    /**
     * A fresh buffer to write one chunk into.
     * <p>
     * Buffered rather than streamed because a chunk carries its own length, and the only way to
     * know that is to have finished it.
     */
    public static StateIO writing() {
        return new StateIO(new ByteArrayOutputStream(), null);
    }

    /**
     * Reads one chunk's payload.
     */
    public static StateIO reading(final byte[] payload) {
        return new StateIO(null, payload);
    }

    /**
     * Which way this is pointing.
     * <p>
     * For the two or three places that genuinely have to know: a wire whose level is a function of
     * other state gets recomputed after a load rather than restored, so there is nothing for the
     * saving side to do.
     */
    public boolean saving() {
        return buffer != null;
    }

    /**
     * What was written, once the chunk is finished.
     */
    public byte[] written() {
        return buffer.toByteArray();
    }

    // ============================================================================ single values

    public boolean bool(final boolean value) {
        return u8(value ? 1 : 0) != 0;
    }

    public int u8(final int value) {
        if (saving()) {
            buffer.write(ByteUtils.ensureByte(value));

            return value;
        }

        return remaining() < 1 ? value : next();
    }

    public int u16(final int value) {
        if (saving()) {
            buffer.write(ByteUtils.getHigh(value));
            buffer.write(ByteUtils.getLow(value));

            return value;
        }

        // The high byte first, so joinBytes reads it back in the order it is written.
        return remaining() < 2 ? value : ByteUtils.joinBytes(next(), next());
    }

    public int u32(final int value) {
        if (saving()) {
            for (var shift = 24; shift >= 0; shift -= 8) {
                buffer.write(ByteUtils.ensureByte(value >> shift));
            }

            return value;
        }

        if (remaining() < 4) {
            return value;
        }

        var read = 0;

        for (var i = 0; i < 4; i++) {
            read = read << 8 | next();
        }

        return read;
    }

    public long u64(final long value) {
        if (saving()) {
            for (var shift = 56; shift >= 0; shift -= 8) {
                buffer.write(ByteUtils.ensureByte((int) (value >> shift)));
            }

            return value;
        }

        if (remaining() < 8) {
            return value;
        }

        var read = 0L;

        for (var i = 0; i < 8; i++) {
            read = read << 8 | next();
        }

        return read;
    }

    /**
     * A double, bit for bit.
     * <p>
     * {@code doubleToRawLongBits} rather than {@code doubleToLongBits}, so the round trip is exact
     * by construction rather than by an argument about whether a NaN can ever get in here.
     */
    public double f64(final double value) {
        return Double.longBitsToDouble(u64(Double.doubleToRawLongBits(value)));
    }

    /**
     * One constant of an enum, as its ordinal.
     * <p>
     * <strong>The declaration order of the constants is part of the file format.</strong> Nothing
     * stops a tidying pass from reordering them, and nothing here would notice; writing the names
     * instead would cost three lines and buy a robustness that a bookmark does not need. An
     * ordinal that names no constant leaves the value alone, which is the same rule as running off
     * the end of a chunk.
     */
    public <E extends Enum<E>> E enumeration(final E value, final Class<E> type) {
        var ordinal = u8(value.ordinal());
        var constants = type.getEnumConstants();

        return ordinal >= 0 && ordinal < constants.length ? constants[ordinal] : value;
    }

    // ==================================================================================== arrays

    /**
     * A byte array, filled in place. A chunk that runs out part way leaves the rest of it alone.
     */
    public void bytes(final byte[] array) {
        if (saving()) {
            buffer.write(array, 0, array.length);

            return;
        }

        var count = Math.min(array.length, remaining());

        System.arraycopy(payload, position, array, 0, count);
        position += count;
    }

    /**
     * An {@code int[]} whose every element holds one byte, which is how the console stores most of
     * its memories.
     */
    public void bytes(final int[] array) {
        if (saving()) {
            for (var value : array) {
                buffer.write(ByteUtils.ensureByte(value));
            }

            return;
        }

        var count = Math.min(array.length, remaining());

        for (var i = 0; i < count; i++) {
            array[i] = Byte.toUnsignedInt(payload[position + i]);
        }

        position += count;
    }

    /**
     * An {@code int[]} whose every element holds two bytes. The framebuffer, whose entries run to
     * 511 once the emphasis bits are in them.
     */
    public void words(final int[] array) {
        if (saving()) {
            for (var value : array) {
                buffer.write(ByteUtils.getHigh(value));
                buffer.write(ByteUtils.getLow(value));
            }

            return;
        }

        var count = Math.min(array.length, remaining() / 2);

        for (var i = 0; i < count; i++) {
            array[i] = ByteUtils.joinBytes(
                    Byte.toUnsignedInt(payload[position + i * 2]),
                    Byte.toUnsignedInt(payload[position + i * 2 + 1]));
        }

        position += count * 2;
    }

    /**
     * A hole where a field used to be.
     * <p>
     * Deleting a field from the middle of a chunk would shift every field after it, and a state
     * written by an older build would then be read one field out of step -- silently, because the
     * bytes are all still there and all still the right length. Writing the hole back as zeroes
     * keeps both directions byte-aligned, at the cost of carrying the corpse.
     * <p>
     * Only for fields removed from the <em>middle</em>. A field removed from the end needs nothing:
     * the reading side already leaves what it never reaches alone.
     */
    public void skip(final int count) {
        if (saving()) {
            for (var i = 0; i < count; i++) {
                buffer.write(0);
            }

            return;
        }

        position += Math.min(count, remaining());
    }

    /**
     * A {@code long[]}: the two decay tables, which count in dots and in frames.
     */
    public void longs(final long[] array) {
        if (saving()) {
            for (var value : array) {
                u64(value);
            }

            return;
        }

        var count = Math.min(array.length, remaining() / 8);

        for (var i = 0; i < count; i++) {
            array[i] = u64(array[i]);
        }
    }

    // ================================================================================= internals

    private int remaining() {
        return payload.length - position;
    }

    private int next() {
        return Byte.toUnsignedInt(payload[position++]);
    }
}
