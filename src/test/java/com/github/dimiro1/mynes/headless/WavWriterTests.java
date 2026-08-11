package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.APU;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioSystem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The sound, written down.
 * <p>
 * The header is checked by reading it back with {@link AudioSystem}, which is the JDK's own parser
 * and knows nothing about how it was written. That needs no sound device: reading a WAV file is
 * format code, and only playing one wants a mixer.
 */
class WavWriterTests {
    @TempDir
    private Path directory;

    private static short[] ramp(final int count) {
        var samples = new short[count];

        for (var i = 0; i < count; i++) {
            samples[i] = (short) (i * 37 - 16384);
        }

        return samples;
    }

    private Path write(final short[] samples, final int count) throws IOException {
        var path = directory.resolve("audio.wav");

        try (var writer = new WavWriter(path)) {
            writer.write(samples, count);
        }

        return path;
    }

    @Test
    void theHeaderSaysWhatTheJdkExpects() throws Exception {
        var path = write(ramp(1000), 1000);

        try (var stream = AudioSystem.getAudioInputStream(path.toFile())) {
            var format = stream.getFormat();

            assertEquals(APU.SAMPLE_RATE, (int) format.getSampleRate());
            assertEquals(16, format.getSampleSizeInBits());
            assertEquals(1, format.getChannels());
            assertEquals(1000, stream.getFrameLength());
        }
    }

    @Test
    void everySampleSurvivesTheRoundTrip() throws Exception {
        var samples = ramp(500);
        var path = write(samples, samples.length);

        try (var stream = AudioSystem.getAudioInputStream(path.toFile())) {
            var bytes = stream.readAllBytes();
            var read = new short[samples.length];

            for (var i = 0; i < read.length; i++) {
                read[i] = (short) ((bytes[i * 2] & 0xFF) | (bytes[i * 2 + 1] << 8));
            }

            assertArrayEquals(samples, read);
        }
    }

    @Test
    void samplesAreWrittenLittleEndian() throws Exception {
        var path = write(new short[]{0x1234}, 1);
        var bytes = Files.readAllBytes(path);

        assertEquals(0x34, bytes[44] & 0xFF);
        assertEquals(0x12, bytes[45] & 0xFF);
    }

    @Test
    void onlyTheSamplesAskedForAreWritten() throws Exception {
        var path = write(ramp(100), 10);

        try (var stream = AudioSystem.getAudioInputStream(path.toFile())) {
            assertEquals(10, stream.getFrameLength());
        }
    }

    /**
     * A run of a game that never makes a noise still has to leave a file the next thing along can
     * open, which is the whole reason the lengths are patched on the way out rather than guessed on
     * the way in.
     */
    @Test
    void aRunWithNoSoundStillWritesAValidFile() throws Exception {
        var path = directory.resolve("silent.wav");

        try (var writer = new WavWriter(path)) {
            assertEquals(0, writer.samples());
        }

        try (var stream = AudioSystem.getAudioInputStream(path.toFile())) {
            assertEquals(0, stream.getFrameLength());
        }

        assertEquals(44, Files.size(path));
    }

    @Test
    void writingOverALongerFileLeavesNoneOfItBehind() throws Exception {
        var path = directory.resolve("audio.wav");
        Files.write(path, new byte[100_000]);

        try (var writer = new WavWriter(path)) {
            writer.write(ramp(10), 10);
        }

        assertEquals(44 + 20, Files.size(path));
    }
}
