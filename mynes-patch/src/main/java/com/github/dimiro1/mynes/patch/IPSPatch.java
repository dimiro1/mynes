package com.github.dimiro1.mynes.patch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An IPS patch: read once, applied to as many images as you like.
 * <p>
 * The format is from 1990 and is about as simple as a binary format gets. Five bytes of
 * {@code PATCH}, then records until three bytes of {@code EOF}. A record is a three byte offset and
 * a two byte length, both big endian, followed by that many bytes to write there; a length of zero
 * instead means a run, and is followed by a two byte count and the one byte to repeat. Offsets are
 * counted from the front of the file, header and all.
 * <p>
 * Three things about it are worth knowing before reading the code.
 * <p>
 * <strong>A record may write past the end of the image</strong>, and the file grows to fit. That is
 * how a hack that adds data ships, so it is not an error, and it is why {@link #applyTo} works out
 * the size before it copies anything rather than writing into an array the size of the original.
 * <p>
 * <strong>{@code EOF} is also a legal offset</strong> -- 0x454F46, four and a half megabytes in --
 * and there is nothing in the format to tell a record there from the end of the patch. The end wins,
 * because that is the reading every patcher ever written has taken, and a patch relying on the other
 * one would already be broken everywhere else.
 * <p>
 * <strong>Three bytes after the {@code EOF}</strong> are the truncation extension: the length to cut
 * the patched file down to. It is not in the original description of the format, but it is in Lunar
 * IPS and in everything written since. Growing a file to reach that length is not what it means, so a
 * truncation longer than the image is ignored rather than obeyed.
 *
 * @see <a href="https://zerosoft.zophar.net/ips.php">Zerosoft: the IPS file format</a>
 * @see <a href="https://sneslab.net/wiki/IPS_file_format">SnesLab: IPS file format</a>
 */
public final class IPSPatch {
    private static final byte[] MAGIC = {'P', 'A', 'T', 'C', 'H'};
    private static final byte[] END = {'E', 'O', 'F'};

    private static final int OFFSET_BYTES = 3;
    private static final int LENGTH_BYTES = 2;

    /**
     * What {@link #truncateTo()} answers for a patch that does not ask for a truncation, which is
     * nearly all of them.
     */
    public static final int NO_TRUNCATION = -1;

    /**
     * One record, still in the shape the file wrote it.
     * <p>
     * A run keeps its count and its one byte rather than being expanded when it is read: eight bytes
     * of patch can ask for sixty-four kilobytes of run, and a patch that is nothing but runs would
     * otherwise cost eight thousand times its own size to hold on to.
     *
     * @param data the bytes to write, or null for a run of {@code fill}.
     */
    private record Change(int offset, int length, byte[] data, byte fill) {
    }

    private final String filename;
    private final List<Change> changes;
    private final int bytes;
    private final int truncateTo;

    private IPSPatch(
            final String filename, final List<Change> changes, final int truncateTo) {
        var written = 0;

        for (var change : changes) {
            written += change.length();
        }

        this.filename = filename;
        this.changes = changes;
        this.bytes = written;
        this.truncateTo = truncateTo;
    }

    /**
     * Reads a patch, checking it all the way through.
     * <p>
     * Everything is parsed here rather than while it is being applied, so that a file which is not a
     * patch at all is found out before a single byte of anybody's ROM has been touched.
     *
     * @param bytes    the {@code .ips} file.
     * @param filename what to call it in an error message.
     * @throws InvalidPatchException if it is not a patch, or is one that has been cut short.
     */
    public static IPSPatch read(final byte[] bytes, final String filename) {
        if (bytes.length < MAGIC.length + END.length
                || !Arrays.equals(bytes, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new InvalidPatchException(filename, "it does not begin with PATCH");
        }

        var changes = new ArrayList<Change>();
        var at = MAGIC.length;

        while (true) {
            if (at + END.length > bytes.length) {
                throw new InvalidPatchException(filename, "it stops before its EOF marker");
            }

            if (Arrays.equals(bytes, at, at + END.length, END, 0, END.length)) {
                at += END.length;
                break;
            }

            if (at + OFFSET_BYTES + LENGTH_BYTES > bytes.length) {
                throw new InvalidPatchException(
                        filename, "the record at byte " + at + " runs off the end of it");
            }

            var offset = read(bytes, at, OFFSET_BYTES);
            var length = read(bytes, at + OFFSET_BYTES, LENGTH_BYTES);
            at += OFFSET_BYTES + LENGTH_BYTES;

            if (length == 0) {
                if (at + LENGTH_BYTES + 1 > bytes.length) {
                    throw new InvalidPatchException(
                            filename, "the run at byte " + at + " runs off the end of it");
                }

                var run = read(bytes, at, LENGTH_BYTES);
                var fill = bytes[at + LENGTH_BYTES];
                at += LENGTH_BYTES + 1;

                // A run of nothing is dropped rather than kept, because a kept one would be a record
                // that writes no bytes and still grows the image to reach its offset.
                if (run > 0) {
                    changes.add(new Change(offset, run, null, fill));
                }
            } else {
                if (at + length > bytes.length) {
                    throw new InvalidPatchException(
                            filename, "the record at byte " + at + " runs off the end of it");
                }

                changes.add(
                        new Change(offset, length, Arrays.copyOfRange(bytes, at, at + length),
                                (byte) 0));
                at += length;
            }
        }

        var trailing = bytes.length - at;

        if (trailing != 0 && trailing != OFFSET_BYTES) {
            throw new InvalidPatchException(
                    filename, trailing + " bytes follow its EOF marker, which is neither nothing"
                            + " nor the three of a truncation");
        }

        return new IPSPatch(
                filename,
                List.copyOf(changes),
                trailing == OFFSET_BYTES ? read(bytes, at, OFFSET_BYTES) : NO_TRUNCATION);
    }

    /**
     * Applies the patch, leaving what it was given alone.
     * <p>
     * A copy rather than a rewrite in place because the caller's array is the file it read off disk,
     * and the whole point of patching at load is that the file is not modified. The result is as long
     * as the furthest record reaches, or as long as a truncation asked for, whichever the patch says.
     *
     * @param image the file as it came off disk.
     * @return a new array. The same patch applied to the same image always gives the same bytes.
     */
    public byte[] applyTo(final byte[] image) {
        var size = image.length;

        for (var change : changes) {
            size = Math.max(size, change.offset() + change.length());
        }

        var patched = Arrays.copyOf(image, size);

        // In the order the file wrote them: two records are allowed to overlap, and the format says
        // nothing about which wins beyond the fact that the second one is applied second.
        for (var change : changes) {
            if (change.data() == null) {
                Arrays.fill(patched, change.offset(), change.offset() + change.length(),
                        change.fill());
            } else {
                System.arraycopy(change.data(), 0, patched, change.offset(), change.length());
            }
        }

        return truncateTo == NO_TRUNCATION || truncateTo >= patched.length
                ? patched
                : Arrays.copyOf(patched, truncateTo);
    }

    /**
     * What this patch is called, for the front end that has to report on it.
     */
    public String filename() {
        return filename;
    }

    /**
     * How many records it holds. Zero is a legal patch and a useless one, and telling somebody that
     * is more use than silently changing nothing.
     */
    public int records() {
        return changes.size();
    }

    /**
     * How many bytes those records write, runs counted at their full length.
     */
    public int bytes() {
        return bytes;
    }

    /**
     * What the patched file is to be cut down to, or {@link #NO_TRUNCATION}.
     */
    public int truncateTo() {
        return truncateTo;
    }

    /**
     * A big endian unsigned integer of {@code count} bytes, which is the only way this format spells
     * a number.
     */
    private static int read(final byte[] bytes, final int at, final int count) {
        var value = 0;

        for (var i = 0; i < count; i++) {
            value = (value << 8) | Byte.toUnsignedInt(bytes[at + i]);
        }

        return value;
    }
}
