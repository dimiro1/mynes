package com.github.dimiro1.mynes.patch;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrowsExactly;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The format, record by record.
 * <p>
 * The patches are built here rather than vendored, because the interesting ones are the malformed
 * ones and no patcher will write those.
 */
class IPSPatchTests {
    private static final String NAME = "hack.ips";

    /**
     * A patch file, assembled the way a patcher assembles one. Every number in the format is big
     * endian, which is what {@link #offset} and the two length writes are being careful about.
     */
    private static final class Builder {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        Builder() {
            bytes.writeBytes("PATCH".getBytes(StandardCharsets.US_ASCII));
        }

        Builder record(final int offset, final int... data) {
            offset(offset);
            bytes.write(data.length >> 8);
            bytes.write(data.length);

            for (var value : data) {
                bytes.write(value);
            }

            return this;
        }

        Builder run(final int offset, final int count, final int value) {
            offset(offset);
            bytes.write(0);
            bytes.write(0);
            bytes.write(count >> 8);
            bytes.write(count);
            bytes.write(value);

            return this;
        }

        byte[] end() {
            bytes.writeBytes("EOF".getBytes(StandardCharsets.US_ASCII));

            return bytes.toByteArray();
        }

        byte[] endTruncatingTo(final int length) {
            end();
            offset(length);

            return bytes.toByteArray();
        }

        private void offset(final int offset) {
            bytes.write(offset >> 16);
            bytes.write(offset >> 8);
            bytes.write(offset);
        }
    }

    private static byte[] image(final int... values) {
        var image = new byte[values.length];

        for (var i = 0; i < values.length; i++) {
            image[i] = (byte) values[i];
        }

        return image;
    }

    private static IPSPatch read(final byte[] bytes) {
        return IPSPatch.read(bytes, NAME);
    }

    @Test
    void aRecordWritesItsBytesWhereItSaysItWill() {
        var patch = read(new Builder().record(2, 0xAA, 0xBB).end());

        assertArrayEquals(
                image(0, 1, 0xAA, 0xBB, 4),
                patch.applyTo(image(0, 1, 2, 3, 4)));
    }

    @Test
    void aRunRepeatsOneByte() {
        var patch = read(new Builder().run(1, 3, 0xFF).end());

        assertArrayEquals(
                image(0, 0xFF, 0xFF, 0xFF, 4),
                patch.applyTo(image(0, 1, 2, 3, 4)));
    }

    @Test
    void recordsAreAppliedInTheOrderTheyWereWritten() {
        var patch = read(new Builder()
                .record(0, 0x11, 0x11, 0x11)
                .record(1, 0x22)
                .end());

        assertArrayEquals(image(0x11, 0x22, 0x11), patch.applyTo(image(0, 0, 0)));
    }

    @Test
    void aRecordPastTheEndOfTheImageGrowsIt() {
        var patch = read(new Builder().record(4, 0xAA, 0xBB).end());

        assertArrayEquals(image(0, 1, 0, 0, 0xAA, 0xBB), patch.applyTo(image(0, 1)));
    }

    @Test
    void aRunPastTheEndOfTheImageGrowsItToo() {
        var patch = read(new Builder().run(2, 2, 0xEE).end());

        assertArrayEquals(image(0, 1, 0xEE, 0xEE), patch.applyTo(image(0, 1)));
    }

    @Test
    void theTruncationExtensionCutsTheImageShort() {
        var patch = read(new Builder().record(0, 0xAA).endTruncatingTo(3));

        assertEquals(3, patch.truncateTo());
        assertArrayEquals(image(0xAA, 1, 2), patch.applyTo(image(0, 1, 2, 3, 4)));
    }

    @Test
    void aTruncationLongerThanTheImageLeavesItAlone() {
        // Truncating is all the extension means, so a length that would grow the file is not one.
        var patch = read(new Builder().record(0, 0xAA).endTruncatingTo(99));

        assertArrayEquals(image(0xAA, 1, 2), patch.applyTo(image(0, 1, 2)));
    }

    @Test
    void aPatchWithoutATruncationSaysSo() {
        assertEquals(IPSPatch.NO_TRUNCATION, read(new Builder().record(0, 0xAA).end()).truncateTo());
    }

    @Test
    void theImageItIsHandedIsLeftAlone() {
        var original = image(0, 1, 2, 3);
        var patch = read(new Builder().record(1, 0xAA).end());

        patch.applyTo(original);

        assertArrayEquals(image(0, 1, 2, 3), original);
    }

    @Test
    void thePatchCanBeAppliedMoreThanOnce() {
        var patch = read(new Builder().record(1, 0xAA).run(3, 2, 0xBB).end());

        assertArrayEquals(patch.applyTo(image(0, 1, 2, 3, 4)), patch.applyTo(image(0, 1, 2, 3, 4)));
    }

    @Test
    void aPatchWithNoRecordsChangesNothing() {
        var patch = read(new Builder().end());

        assertEquals(0, patch.records());
        assertEquals(0, patch.bytes());
        assertArrayEquals(image(0, 1, 2), patch.applyTo(image(0, 1, 2)));
    }

    @Test
    void aRunOfNothingIsNotARecord() {
        // Kept, it would be a record that writes no bytes and still grew the image to reach its
        // offset -- which is the one thing a record of zero length must not do.
        var patch = read(new Builder().run(9, 0, 0xFF).end());

        assertEquals(0, patch.records());
        assertArrayEquals(image(0, 1), patch.applyTo(image(0, 1)));
    }

    @Test
    void itCountsWhatItRead() {
        var patch = read(new Builder()
                .record(0, 0xAA, 0xBB)
                .run(8, 16, 0x00)
                .end());

        assertEquals(2, patch.records());
        assertEquals(18, patch.bytes());
        assertEquals(NAME, patch.filename());
    }

    @Test
    void aFileThatDoesNotBeginWithPATCHIsRefused() {
        var refused = assertThrowsExactly(
                InvalidPatchException.class,
                () -> read("NESand so on".getBytes(StandardCharsets.US_ASCII)));

        assertTrue(refused.getMessage().contains(NAME));
    }

    @Test
    void anEmptyFileIsRefused() {
        assertThrowsExactly(InvalidPatchException.class, () -> read(new byte[0]));
    }

    @Test
    void aPatchThatStopsBeforeItsEOFIsRefused() {
        var whole = new Builder().record(0, 0xAA).end();

        assertThrowsExactly(
                InvalidPatchException.class,
                () -> read(Arrays.copyOf(whole, whole.length - 1)));
    }

    @Test
    void aRecordThatRunsOffTheEndIsRefused() {
        var whole = new Builder().record(0, 0xAA, 0xBB, 0xCC).end();

        // Header, offset and length, then one byte of the three the record promised.
        assertThrowsExactly(
                InvalidPatchException.class,
                () -> read(Arrays.copyOf(whole, 5 + 3 + 2 + 1)));
    }

    @Test
    void aRunThatRunsOffTheEndIsRefused() {
        var whole = new Builder().run(0, 4, 0xAA).end();

        // The count arrived; the byte to repeat did not.
        assertThrowsExactly(
                InvalidPatchException.class,
                () -> read(Arrays.copyOf(whole, 5 + 3 + 2 + 2)));
    }

    @Test
    void bytesAfterTheEOFThatAreNotATruncationAreRefused() {
        var whole = new Builder().record(0, 0xAA).endTruncatingTo(3);

        assertThrowsExactly(
                InvalidPatchException.class,
                () -> read(Arrays.copyOf(whole, whole.length - 1)));
    }
}
