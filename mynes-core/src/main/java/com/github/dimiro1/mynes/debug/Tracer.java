package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPUEventListener;
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
 * Every instruction the CPU runs, written down as it runs.
 * <p>
 * The format is nestest's, down to the column, because that log is already this emulator's answer
 * key: {@code NesTestTests} walks 8990 of its lines against a running machine, so a trace taken here
 * and a log taken from another emulator can be put side by side with {@code diff} and the first line
 * that differs is the disagreement. A prettier format would have thrown that away for nothing.
 *
 * <pre>
 * C000  4C F5 C5  JMP $C5F5                       A:00 X:00 Y:00 P:24 SP:FD PPU:  0, 21 CYC:7
 * </pre>
 *
 * The one column nestest has that this does not is the {@code = 00} it appends to an operand, saying
 * what the address held. That is a read, and a tracer that performed one would be changing the
 * machine it is describing -- $2002's flag cleared, a controller clocked, MMC3's counter driven --
 * so it is left out rather than faked.
 * <p>
 * The {@code PPU:} column is where the beam is <em>as the opcode is fetched</em>. A log that prints
 * three times its cycle count is answering the same question one CPU cycle earlier, at the boundary
 * before the fetch, because {@code NES.tick()} clocks the PPU before the CPU. Both are true; the
 * consequence is that a diff against another emulator's log belongs on the CPU columns, which do
 * line up exactly.
 * <p>
 * <b>The file is not small.</b> A frame is around thirty thousand instructions and a line is around
 * ninety bytes, so a second of NTSC is a hundred and sixty megabytes and this is not a thing to
 * leave switched on across a run. That is what the limit is for. The line is built by hand rather
 * than through {@code String.format} for the same reason it is worth capping: at a million and a
 * half instructions a second the formatter is most of the cost of tracing.
 * <p>
 * Everything here happens on the thread clocking the machine, which is the thread
 * {@link CPUEventListener} is called on, and nothing here synchronises.
 */
public final class Tracer implements CPUEventListener, Closeable {
    /**
     * How much to hold before touching the disk. Large, because the whole point of the thing is
     * that it writes constantly.
     */
    private static final int BUFFER = 1 << 16;

    /**
     * nestest's columns: where the instruction's bytes start, where the disassembly starts, and
     * where the registers do.
     */
    private static final int BYTES_COLUMN = 6;
    private static final int TEXT_COLUMN = 16;
    private static final int REGISTERS_COLUMN = 48;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private final Writer out;
    private final PPU ppu;

    /**
     * How many lines to write before going quiet, or 0 for as many as it takes.
     */
    private final long limit;

    /**
     * Reused rather than allocated per instruction, which is safe for the reason nothing here
     * synchronises: one thread clocks the machine and this is only ever called from it.
     */
    private final StringBuilder line = new StringBuilder(96);

    /**
     * One array per instruction length, so that handing the bytes to the disassembler allocates
     * nothing. The {@code Line} it comes back with is read and dropped in the same statement.
     */
    private final int[][] instruction = {new int[1], new int[2], new int[3]};

    private long lines;
    private boolean full;
    private boolean closed;

    /**
     * The first failure writing, or null. Kept rather than thrown: {@link #onStep} cannot throw
     * through the CPU, and a disk that filled up half way through a trace is worth telling somebody
     * about at the end rather than losing.
     */
    private IOException failure;

    private Tracer(final Writer out, final PPU ppu, final long limit) {
        this.out = out;
        this.ppu = ppu;
        this.limit = limit;
    }

    /**
     * Opens one on a file, replacing whatever was there.
     *
     * @param path  where to write it. Parent directories are made.
     * @param ppu   the machine's PPU, for the {@code PPU:} column -- where the beam is as the
     *              instruction starts, which is the column that makes a trace worth reading for
     *              anything to do with timing.
     * @param limit how many lines to write before going quiet, or 0 for no limit. A tracer that has
     *              reached its limit closes the file and answers {@link #isFull()}; it does not take
     *              itself off the CPU, because the list of listeners is being walked at the time.
     */
    public static Tracer to(final Path path, final PPU ppu, final long limit) throws IOException {
        var parent = path.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        return new Tracer(
                new BufferedWriter(
                        new OutputStreamWriter(
                                Files.newOutputStream(path), StandardCharsets.US_ASCII),
                        BUFFER),
                ppu,
                limit);
    }

    @Override
    public void onStep(
            final int pc,
            final int a,
            final int x,
            final int y,
            final int p,
            final int sp,
            final int opcode,
            final int operand1,
            final int operand2,
            final int opcodeLength,
            final long cycles
    ) {
        if (closed) {
            return;
        }

        var bytes = instruction[opcodeLength - 1];

        bytes[0] = opcode;

        if (opcodeLength > 1) {
            bytes[1] = operand1;
        }

        if (opcodeLength > 2) {
            bytes[2] = operand2;
        }

        line.setLength(0);
        word(pc);
        pad(BYTES_COLUMN);

        for (var i = 0; i < opcodeLength; i++) {
            if (i > 0) {
                line.append(' ');
            }

            byteHex(bytes[i]);
        }

        pad(TEXT_COLUMN);

        // The bytes the CPU is about to run rather than a second look at memory, which would be a
        // look through whatever bank happens to be switched in by the time anybody reads the file.
        line.append(Disassembler.of(pc, bytes).text());
        pad(REGISTERS_COLUMN);

        line.append("A:");
        byteHex(a);
        line.append(" X:");
        byteHex(x);
        line.append(" Y:");
        byteHex(y);
        line.append(" P:");
        byteHex(p);
        line.append(" SP:");
        byteHex(sp);
        line.append(" PPU:");
        right(ppu.getScanline(), 3);
        line.append(',');
        right(ppu.getDot(), 3);
        line.append(" CYC:").append(cycles).append('\n');

        write();
    }

    /**
     * How many lines have been written.
     */
    public long lines() {
        return lines;
    }

    /**
     * Whether it wrote all it was asked for and stopped. The file is closed by then, so the lines
     * that were written are on disk.
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
     * Closes the file. Doing it twice is not an error, which is what lets a session close a tracer
     * that already stopped itself at its limit.
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

    private void write() {
        try {
            out.append(line);
            lines++;

            if (limit > 0 && lines >= limit) {
                full = true;
                close();
            }
        } catch (IOException e) {
            if (failure == null) {
                failure = e;
            }

            // Quietly, and once: a disk that has filled up will fill up again on the next
            // instruction, and a million copies of one message is not a better report than one.
            closed = true;

            try {
                out.close();
            } catch (IOException ignored) {
                // Already failing, and the first exception is the one worth keeping.
            }
        }
    }

    private void pad(final int to) {
        while (line.length() < to) {
            line.append(' ');
        }
    }

    private void right(final int value, final int width) {
        var text = Integer.toString(value);

        for (var i = text.length(); i < width; i++) {
            line.append(' ');
        }

        line.append(text);
    }

    private void word(final int value) {
        byteHex(value >> 8);
        byteHex(value);
    }

    private void byteHex(final int value) {
        line.append(HEX[(value >> 4) & 0x0F]).append(HEX[value & 0x0F]);
    }
}
