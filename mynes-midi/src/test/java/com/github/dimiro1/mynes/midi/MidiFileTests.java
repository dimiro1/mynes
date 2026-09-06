package com.github.dimiro1.mynes.midi;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Bytes, because that is the whole of what this class is for.
 * <p>
 * A test that read the file back through this same code would agree with whatever it wrote, which
 * is no check at all -- so the numbers below are the ones the specification gives, and the two
 * variable length quantities are the two examples every description of the format uses.
 *
 * @see <a href="https://www.midi.org/specifications">The Standard MIDI File specification</a>
 */
class MidiFileTests {
    @Test
    void theHeaderSaysFormatOneAndHowManyTracksThereAre() throws IOException {
        var file = new MidiFile(96);

        file.track("one");
        file.track("two");

        var bytes = write(file);

        assertArrayEquals(
                new byte[]{'M', 'T', 'h', 'd', 0, 0, 0, 6, 0, 1, 0, 2, 0, 96},
                java.util.Arrays.copyOf(bytes, 14),
                "MThd, six bytes of it, format 1, two tracks, 96 ticks to a quarter");
    }

    /**
     * The two examples the specification gives for its variable length quantity, which is the one
     * piece of arithmetic in the format that is easy to get wrong: 0 is one byte and 0x2000 is
     * three.
     */
    @Test
    void aDeltaTimeIsSevenBitsAtATime() throws IOException {
        assertArrayEquals(new byte[]{0x00}, deltaOf(0));
        assertArrayEquals(new byte[]{0x40}, deltaOf(0x40));
        assertArrayEquals(new byte[]{(byte) 0x81, 0x00}, deltaOf(0x80));
        assertArrayEquals(new byte[]{(byte) 0xC0, 0x00}, deltaOf(0x2000));
        assertArrayEquals(new byte[]{(byte) 0xFF, 0x7F}, deltaOf(0x3FFF));
        assertArrayEquals(new byte[]{(byte) 0x81, (byte) 0x80, 0x00}, deltaOf(0x4000));
    }

    /**
     * Times are absolute on the way in and deltas on the way out, which is what lets a caller put a
     * note's end down long after its beginning.
     */
    @Test
    void aTrackIsWrittenAsTheGapsBetweenThings() throws IOException {
        var file = new MidiFile(96);
        var track = file.track(null);

        track.noteOn(10, 0, 60, 100);
        track.noteOff(30, 0, 60);

        assertArrayEquals(
                new byte[]{
                        10, (byte) 0x90, 60, 100,        // ten ticks in, middle C at velocity 100
                        20, (byte) 0x90, 60, 0,          // twenty later, the same note at zero
                        0, (byte) 0xFF, 0x2F, 0,         // and the end of the track
                },
                bodyOf(file));
    }

    /**
     * Put down in whatever order they were discovered and written in the order they happened,
     * except where two share a tick -- there the order they were put down in is kept, which is what
     * stops a note-off arriving after the note-on that replaced it.
     */
    @Test
    void thingsAtTheSameTickKeepTheOrderTheyWerePutDownIn() throws IOException {
        var file = new MidiFile(96);
        var track = file.track(null);

        track.noteOff(5, 0, 60);
        track.noteOn(5, 0, 62, 90);
        track.noteOn(1, 0, 60, 90);

        assertArrayEquals(
                new byte[]{
                        1, (byte) 0x90, 60, 90,          // the earlier note, though written last
                        4, (byte) 0x90, 60, 0,           // its end
                        0, (byte) 0x90, 62, 90,          // and the one that replaced it, after it
                        0, (byte) 0xFF, 0x2F, 0,
                },
                bodyOf(file));
    }

    @Test
    void aTempoIsThreeBytesOfMicrosecondsPerQuarter() throws IOException {
        var file = new MidiFile(96);

        file.track(null).tempo(0, 500_000);

        assertArrayEquals(
                new byte[]{
                        0, (byte) 0xFF, 0x51, 3, 0x07, (byte) 0xA1, 0x20,
                        0, (byte) 0xFF, 0x2F, 0,
                },
                bodyOf(file),
                "500000 is $07A120, which is the 120bpm every sequencer opens on");
    }

    @Test
    void aNamedTrackSaysSoBeforeAnythingElse() throws IOException {
        var file = new MidiFile(96);

        file.track("Pulse 1");

        assertArrayEquals(
                new byte[]{
                        0, (byte) 0xFF, 0x03, 7, 'P', 'u', 'l', 's', 'e', ' ', '1',
                        0, (byte) 0xFF, 0x2F, 0,
                },
                bodyOf(file));
    }

    // ================================================================================== helpers

    /**
     * The delta the writer produces for a tick, taken off the front of a one event track.
     */
    private static byte[] deltaOf(final long tick) throws IOException {
        var file = new MidiFile(96);

        file.track(null).noteOn(tick, 0, 60, 100);

        var body = bodyOf(file);
        var end = 0;

        while ((body[end] & 0x80) != 0) {
            end++;
        }

        return java.util.Arrays.copyOf(body, end + 1);
    }

    /**
     * What is inside the first track chunk, without its four byte name and four byte length.
     */
    private static byte[] bodyOf(final MidiFile file) throws IOException {
        var bytes = write(file);
        var start = 14 + 8;
        var length = ((bytes[18] & 0xFF) << 24) | ((bytes[19] & 0xFF) << 16)
                | ((bytes[20] & 0xFF) << 8) | (bytes[21] & 0xFF);

        assertEquals(bytes.length, start + length, "the length in the chunk is the length of it");

        return java.util.Arrays.copyOfRange(bytes, start, start + length);
    }

    private static byte[] write(final MidiFile file) throws IOException {
        var out = new ByteArrayOutputStream();

        file.write(out);

        return out.toByteArray();
    }
}
