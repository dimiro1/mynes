package com.github.dimiro1.mynes.archive;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * A zip file, unpacked into memory and then asked what is in it.
 * <p>
 * Read once and kept, rather than reopened per question, because the two questions a caller has are
 * asked together: "is the thing I want in here" and, when it is not, "then what is". Both are
 * answered off one pass, and a zip holding a cartridge is a couple of megabytes -- small enough that
 * holding it beats decompressing it twice.
 * <p>
 * <strong>Nothing is written to disk.</strong> {@link ZipInputStream} over a
 * {@link ByteArrayInputStream} needs no random access and no temporary file, so a run leaves no
 * unpacked copy of somebody's ROM behind, and the bytes handed back are as free to patch as bytes
 * read straight off a file.
 * <p>
 * <strong>Only files come out.</strong> Directory entries are dropped, and so is anything whose
 * last name segment is empty, {@code .} or {@code ..} -- a caller names files beside the archive
 * after {@link Entry#fileName()}, and those three are not names a file can have.
 *
 * @see <a href="https://pkware.cachefly.net/webdocs/casestudies/APPNOTE.TXT">PKWARE: the .ZIP file
 *      format specification</a>
 */
public final class Archive {
    /**
     * The first four bytes of the first entry's header, which is where a zip starts when it holds
     * anything.
     */
    private static final byte[] LOCAL_HEADER = {'P', 'K', 3, 4};

    /**
     * An empty zip is nothing but its end-of-central-directory record, so it begins differently.
     * Recognised on purpose: "there is nothing in this zip" is a far better answer to give somebody
     * than "this is not a zip".
     */
    private static final byte[] END_OF_DIRECTORY = {'P', 'K', 5, 6};

    /**
     * How much a zip is allowed to expand to before this gives up on it.
     * <p>
     * The largest NES cartridge anybody has dumped is a couple of megabytes and the largest NES 2.0
     * can describe is well under this, so no real archive comes near it. It is here because a zip
     * declares its compressed size and not its real one, and forty kilobytes of zeroes deflate to
     * nothing at all: without a budget, a file that fits in an email can be an
     * {@link OutOfMemoryError}. Counted across every entry rather than per entry, since a thousand
     * small bombs are the same bomb.
     */
    private static final int BUDGET = 64 * 1024 * 1024;

    private static final int CHUNK = 64 * 1024;

    /**
     * One file out of the archive: the name it goes by inside, and everything it holds.
     *
     * @param name  the whole of the name the archive stores, folders and all, which is what to show
     *              somebody being asked to pick between two of these.
     * @param bytes what it holds, decompressed.
     */
    public record Entry(String name, byte[] bytes) {
        /**
         * The last segment of {@link #name()}, which is what the file would be called once unpacked.
         * <p>
         * Split on the backslash as well as the slash. The format says the separator is a slash, but
         * archives written by DOS tools carry backslashes, and one left in a name that is then
         * resolved against a folder is a separator again on Windows and part of the filename
         * everywhere else -- the kind of difference that only shows up on somebody else's computer.
         */
        public String fileName() {
            return fileNameOf(name);
        }
    }

    private final List<Entry> files;

    private Archive(final List<Entry> files) {
        this.files = files;
    }

    /**
     * The last segment of an entry's name: see {@link Entry#fileName()}.
     * <p>
     * Static as well, because a caller that remembered which entry it opened remembers the name and
     * not the {@link Entry} -- and it has to reach the same answer as the entry did, or a game's
     * saves land somewhere else the second time it is opened.
     */
    public static String fileNameOf(final String name) {
        var cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));

        return cut < 0 ? name : name.substring(cut + 1);
    }

    /**
     * Whether these bytes are a zip, by the four they begin with.
     * <p>
     * By the content rather than by the name, because the name is the one thing here nobody can rely
     * on: a cartridge downloaded as {@code game.zip.nes} is still a zip, and a zip renamed on the way
     * through a mail server is still a zip. The cost of being wrong is a clear error either way,
     * since the caller is about to hand the bytes to something that will check them again.
     */
    public static boolean looksLikeOne(final byte[] bytes) {
        return startsWith(bytes, LOCAL_HEADER) || startsWith(bytes, END_OF_DIRECTORY);
    }

    /**
     * Unpacks the whole archive into memory.
     *
     * @param bytes    the file, which is expected to be a zip: see {@link #looksLikeOne}.
     * @param filename what to call it in the message if it will not open. Never read as a path.
     * @throws InvalidArchiveException if it is not a zip, or is one that has been cut short, or
     *                                 expands to more than this is willing to hold.
     */
    public static Archive open(final byte[] bytes, final String filename) {
        // Checked here rather than left to the reader below, which does not check it: ZipInputStream
        // hunts for the next entry's signature and answers null when it runs out of file, so a
        // cartridge handed to it comes back as an archive holding nothing at all. An empty zip is a
        // real thing and has to keep meaning that, so the two cannot be told apart afterwards.
        if (!looksLikeOne(bytes)) {
            throw new InvalidArchiveException(filename, "it does not begin like one");
        }

        var files = new ArrayList<Entry>();
        var budget = BUDGET;

        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (!isFile(entry)) {
                    continue;
                }

                var content = read(zip, budget, filename, entry.getName());

                budget -= content.length;
                files.add(new Entry(entry.getName(), content));
            }
        } catch (IOException e) {
            // A zip that begins like one and then is not: a download that stopped half way, an
            // entry whose compressed data is corrupt, or a signature that turns out to be four
            // bytes of something else. All the same answer to the caller.
            throw new InvalidArchiveException(filename, e.getMessage() == null
                    ? e.getClass().getSimpleName() : e.getMessage());
        }

        return new Archive(List.copyOf(files));
    }

    /**
     * Every file in it, in the order the archive lists them.
     */
    public List<Entry> files() {
        return files;
    }

    /**
     * The files whose names end in one of these extensions, given without their dots and matched
     * without regard to case -- a dump named {@code GAME.NES} is the same cartridge as one named
     * {@code game.nes}.
     */
    public List<Entry> endingIn(final String... extensions) {
        var wanted = new ArrayList<Entry>();

        for (var file : files) {
            var name = file.fileName().toLowerCase(Locale.ROOT);

            for (var extension : extensions) {
                if (name.endsWith("." + extension.toLowerCase(Locale.ROOT))) {
                    wanted.add(file);
                    break;
                }
            }
        }

        return List.copyOf(wanted);
    }

    /**
     * Whether this entry is a file somebody could have unpacked, rather than a folder or a name no
     * file could carry. See the note on the class about why the last three are refused.
     */
    private static boolean isFile(final ZipEntry entry) {
        if (entry.isDirectory()) {
            return false;
        }

        var name = fileNameOf(entry.getName());

        return !name.isEmpty() && !name.equals(".") && !name.equals("..");
    }

    /**
     * The whole of one entry, refusing to go past what is left of the budget.
     * <p>
     * Read in chunks rather than through {@code readAllBytes} because the size the entry declares is
     * not to be trusted -- it is absent entirely when the writer used a data descriptor, and it is
     * whatever a hostile file says otherwise. What comes out of the stream is the only measure.
     */
    private static byte[] read(
            final ZipInputStream zip, final int budget, final String filename, final String entry)
            throws IOException {
        var content = new ByteArrayOutputStream();
        var chunk = new byte[CHUNK];

        for (var read = zip.read(chunk); read >= 0; read = zip.read(chunk)) {
            if (content.size() + read > budget) {
                throw new InvalidArchiveException(filename,
                        entry + " unpacks to more than the " + (BUDGET / (1024 * 1024))
                                + "MB this is willing to hold");
            }

            content.write(chunk, 0, read);
        }

        return content.toByteArray();
    }

    private static boolean startsWith(final byte[] bytes, final byte[] magic) {
        if (bytes.length < magic.length) {
            return false;
        }

        for (var i = 0; i < magic.length; i++) {
            if (bytes[i] != magic[i]) {
                return false;
            }
        }

        return true;
    }
}
