package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.PPU;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Everything the machine did to its hardware, written down with the beam position it did it at.
 * <p>
 * {@link Tracer}'s sibling, and the other half of the same trade. That one writes every instruction
 * and answers "what did the program do"; this one writes only the accesses that are the machine
 * being <em>told</em> something -- the PPU registers, the sound chip, the mapper and the two
 * interrupts -- and answers <b>where in the frame each one landed</b>. Which is a question nothing
 * else here can answer: a {@code watch} stops the machine, so a write the NMI handler makes dozens
 * of times a frame costs a stop and a resume for each one, and a trace of the same 200 frames is a
 * gigabyte to grep.
 * <p>
 * <b>This does not arm the machine.</b> {@link Debugger#setEventSink} rides on hooks the bus
 * already carries, so a game being logged is a game running at full speed -- which it has to be,
 * since which scanline a game writes $2005 on is a question about a game whose main loop is
 * finishing on time.
 *
 * <pre>
 * # frame line  dot   event         address value   pc
 *      64   23   82   ppu-write     $2005     $00   $80FF
 *      64  241   19   nmi           $FFFA      --   $8057
 * </pre>
 *
 * Fixed columns and one record a line, so the file reads as a table -- and seven whitespace
 * separated fields on every line whatever happened, which is why the event is written as
 * {@link Debugger.EventKind#id()} rather than as its label: {@code grep '\$2005'} and
 * {@code awk '$2 == 167 {print $5}'} both work on it. The single {@code #} header is the only line
 * that is not a record, and is marked the way the REPL's own scripts mark a comment. A number
 * wider than its column pushes the rest of the line right rather than being cut, which keeps a
 * long run honest at the cost of its alignment.
 * <p>
 * Three columns are worth a word. <b>The frame</b> is not in what the sink is handed and is asked
 * of the PPU here, because "the same line every frame" and "one frame in six" are the two answers
 * this file exists to tell apart. <b>The dot</b> is good to within two and no better: the machine
 * is clocked a CPU cycle at a time and three dots go past in one, so it is the last of the three
 * the cycle covered. And <b>the pc</b> is past the instruction that made the access, by however
 * long that instruction was, since the operand bytes have already been fetched -- it is an
 * orientation rather than an answer, and {@code watch} is what answers exactly.
 * <p>
 * Everything here happens on the thread clocking the machine, which is the thread the sink is
 * called on, and nothing here synchronises.
 */
public final class EventTracer implements Debugger.EventSink, Closeable {
    /**
     * How much to hold before touching the disk. The same as {@link Tracer}'s and for the same
     * reason, even though this writes a hundredth as much: a program waiting out the picture with
     * reads switched on polls $2002 every seven cycles, and that is the case worth sizing for.
     */
    private static final int BUFFER = 1 << 16;

    /**
     * Where each column ends, for the three that are right aligned, and where it starts for the
     * three that are not. One set of constants for the header and the records both, so the two
     * cannot drift apart.
     */
    private static final int FRAME_END = 7;
    private static final int LINE_END = 12;
    private static final int DOT_END = 17;
    private static final int EVENT_START = 20;
    private static final int ADDRESS_START = 34;
    private static final int VALUE_END = 47;
    private static final int PC_START = 50;

    /**
     * What an interrupt has instead of a byte. An interrupt reads and writes nothing, and a zero
     * there would be a byte somebody could believe.
     */
    private static final String NO_VALUE = "--";

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final Writer out;
    private final PPU ppu;

    /**
     * How many records to write before going quiet, or 0 for as many as it takes.
     */
    private final long limit;

    /**
     * Reused rather than allocated per event, which is safe for the reason nothing here
     * synchronises: one thread clocks the machine and this is only ever called from it.
     */
    private final StringBuilder line = new StringBuilder(64);

    private long records;
    private boolean full;
    private boolean closed;

    /**
     * The first failure writing, or null. Kept rather than thrown, for {@link Tracer}'s reason:
     * {@link #onEvent} cannot throw through a bus write, and a disk that filled up half way through
     * is worth telling somebody about at the end rather than losing.
     */
    private IOException failure;

    private EventTracer(final Writer out, final PPU ppu, final long limit) {
        this.out = out;
        this.ppu = ppu;
        this.limit = limit;
    }

    /**
     * Opens one on a file, replacing whatever was there, and writes the header.
     *
     * @param path  where to write it. Parent directories are made.
     * @param ppu   the machine's PPU, for the frame column -- which is the column that turns a pile
     *              of writes into "every frame, on line 167".
     * @param limit how many records to write before going quiet, or 0 for no limit. One that has
     *              reached its limit closes the file and answers {@link #isFull()}; it does not
     *              take itself off the debugger, because whoever put it there is the only one who
     *              knows the debugger exists.
     */
    public static EventTracer to(final Path path, final PPU ppu, final long limit)
            throws IOException {

        var parent = path.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        var tracer = new EventTracer(
                new BufferedWriter(
                        new OutputStreamWriter(
                                Files.newOutputStream(path), StandardCharsets.US_ASCII),
                        BUFFER),
                ppu,
                limit);

        tracer.header();

        return tracer;
    }

    @Override
    public void onEvent(
            final Debugger.EventKind kind,
            final int address,
            final int value,
            final int scanline,
            final int dot,
            final int pc
    ) {
        if (closed) {
            return;
        }

        line.setLength(0);
        rightTo(Long.toString(ppu.getFrame()), FRAME_END);
        rightTo(Integer.toString(scanline), LINE_END);
        rightTo(Integer.toString(dot), DOT_END);
        leftAt(kind.id(), EVENT_START);
        pad(ADDRESS_START);
        word(address);
        rightTo(value < 0 ? NO_VALUE : byteHex(value), VALUE_END);
        pad(PC_START);
        word(pc);
        line.append('\n');

        write();
    }

    /**
     * How many records have been written.
     */
    public long records() {
        return records;
    }

    /**
     * Whether it wrote all it was asked for and stopped. The file is closed by then, so what was
     * written is on disk.
     */
    public boolean isFull() {
        return full;
    }

    /**
     * The first failure writing, or null if there was none.
     */
    public IOException failure() {
        return failure;
    }

    /**
     * Closes the file. Doing it twice is not an error, which is what lets a session close one that
     * already stopped itself at its limit.
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }

        closed = true;
        out.close();
    }

    // ================================================================================== internals

    /**
     * The one line in the file that is not a record, marked as a comment so that anything reading
     * the rest can drop it on the character rather than on the line number.
     */
    private void header() {
        line.setLength(0);
        line.append('#');
        rightTo("frame", FRAME_END);
        rightTo("line", LINE_END);
        rightTo("dot", DOT_END);
        leftAt("event", EVENT_START);
        leftAt("address", ADDRESS_START);
        rightTo("value", VALUE_END);
        leftAt("pc", PC_START);
        line.append('\n');

        // Through append rather than through write, because the header is furniture rather than
        // something anybody asked for: it is not a record and it does not count against the limit.
        // Counting it would make "events on x.log 1" a file with a heading and nothing in it.
        append();
    }

    /**
     * One record, counted, and the file closed if that was the last one asked for.
     */
    private void write() {
        if (!append()) {
            return;
        }

        records++;

        if (limit > 0 && records >= limit) {
            full = true;

            try {
                close();
            } catch (IOException e) {
                remember(e);
            }
        }
    }

    /**
     * Puts the line on the disk. False if it did not get there, after which nothing more will be
     * written.
     */
    private boolean append() {
        try {
            out.append(line);

            return true;
        } catch (IOException e) {
            remember(e);

            return false;
        }
    }

    /**
     * Quietly, and once: a disk that has filled up will fill up again on the next write, and a
     * million copies of one message is not a better report than one.
     */
    private void remember(final IOException e) {
        if (failure == null) {
            failure = e;
        }

        closed = true;

        try {
            out.close();
        } catch (IOException ignored) {
            // Already failing, and the first exception is the one worth keeping.
        }
    }

    private void rightTo(final String text, final int column) {
        pad(column - text.length());
        line.append(text);
    }

    private void leftAt(final String text, final int column) {
        pad(column);
        line.append(text);
    }

    private void pad(final int to) {
        while (line.length() < to) {
            line.append(' ');
        }
    }

    private void word(final int value) {
        line.append('$')
                .append(HEX[(value >> 12) & 0x0F])
                .append(HEX[(value >> 8) & 0x0F])
                .append(HEX[(value >> 4) & 0x0F])
                .append(HEX[value & 0x0F]);
    }

    /**
     * A byte, as the value column spells it. A string rather than appended in place like the
     * address beside it, because the column is right aligned and so has to know how wide it is
     * before it starts -- and because a few hundred of these a frame is nothing next to the write
     * that follows them.
     */
    private static String byteHex(final int value) {
        return "$" + HEX[(value >> 4) & 0x0F] + HEX[value & 0x0F];
    }
}
