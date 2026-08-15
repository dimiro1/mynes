package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.APU;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Writes the APU's output to a WAV file as it arrives.
 * <p>
 * Streaming rather than collecting, because the amount of sound a run makes is decided by how long
 * the run is: a second of it is 88 kilobytes, so an hour would be three hundred megabytes of heap
 * held for no reason other than that nobody had written it down yet. The only thing kept in memory
 * is the byte buffer the samples are turned into, reused between calls -- the same trade
 * {@link com.github.dimiro1.mynes.ui.AudioOutput} makes on its way to the sound card.
 * <p>
 * A RIFF header has to say how long the sound is, and nothing knows that until the run ends, so the
 * two lengths go down as zeroes and are patched in {@link #close()}. That is why this writes to a
 * {@link RandomAccessFile} rather than to a stream.
 */
public final class WavWriter implements Closeable {
    private static final int HEADER_BYTES = 44;
    private static final int BITS_PER_SAMPLE = 16;
    private static final int CHANNELS = 1;
    private static final int BYTES_PER_SAMPLE = CHANNELS * BITS_PER_SAMPLE / 8;

    /**
     * Where the two lengths a RIFF header carries live, once the header is written.
     */
    private static final int RIFF_SIZE_OFFSET = 4;
    private static final int DATA_SIZE_OFFSET = 40;

    private final RandomAccessFile file;

    private byte[] bytes = new byte[0];
    private long samples;

    public WavWriter(final Path path) throws IOException {
        this.file = new RandomAccessFile(path.toFile(), "rw");

        // A file being written over may be longer than the one replacing it.
        file.setLength(0);
        writeHeader(0);
    }

    public long samples() {
        return samples;
    }

    /**
     * Appends samples.
     *
     * @param source signed sixteen bit samples at {@link APU#SAMPLE_RATE}.
     * @param count  how many of them to take from the front of {@code source}.
     */
    public void write(final short[] source, final int count) throws IOException {
        if (count <= 0) {
            return;
        }

        if (bytes.length < count * BYTES_PER_SAMPLE) {
            bytes = new byte[count * BYTES_PER_SAMPLE];
        }

        for (var i = 0; i < count; i++) {
            // Little endian, which is what RIFF means by "WAVE".
            bytes[i * 2] = (byte) source[i];
            bytes[i * 2 + 1] = (byte) (source[i] >> 8);
        }

        file.write(bytes, 0, count * BYTES_PER_SAMPLE);
        samples += count;
    }

    /**
     * Patches the lengths into the header and closes the file.
     */
    @Override
    public void close() throws IOException {
        try {
            var dataBytes = samples * BYTES_PER_SAMPLE;

            file.seek(RIFF_SIZE_OFFSET);
            writeIntLE((int) (HEADER_BYTES - 8 + dataBytes));

            file.seek(DATA_SIZE_OFFSET);
            writeIntLE((int) dataBytes);
        } finally {
            file.close();
        }
    }

    private void writeHeader(final int dataBytes) throws IOException {
        file.writeBytes("RIFF");
        writeIntLE(HEADER_BYTES - 8 + dataBytes);
        file.writeBytes("WAVE");

        file.writeBytes("fmt ");
        writeIntLE(16);                                     // the size of this chunk
        writeShortLE((short) 1);                            // PCM, uncompressed
        writeShortLE((short) CHANNELS);
        writeIntLE(APU.SAMPLE_RATE);
        writeIntLE(APU.SAMPLE_RATE * BYTES_PER_SAMPLE);     // bytes a second
        writeShortLE((short) BYTES_PER_SAMPLE);             // bytes in one sample across channels
        writeShortLE((short) BITS_PER_SAMPLE);

        file.writeBytes("data");
        writeIntLE(dataBytes);
    }

    private void writeIntLE(final int value) throws IOException {
        file.write(value);
        file.write(value >> 8);
        file.write(value >> 16);
        file.write(value >> 24);
    }

    private void writeShortLE(final short value) throws IOException {
        file.write(value);
        file.write(value >> 8);
    }
}
