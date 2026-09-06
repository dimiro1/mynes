package com.github.dimiro1.mynes.midi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * A standard MIDI file, written from notes that came from anywhere.
 * <p>
 * Beside the console rather than inside it, for the reason {@code mynes-patch} and
 * {@code mynes-archive} are: the format says nothing at all about what is in it. It is a container
 * from 1983 for note numbers and times, and a writer that could see an {@code APU} would sooner or
 * later be handed one. What joins the two together is the front end, which watches the chip and
 * calls the methods below.
 * <p>
 * <b>Written by hand rather than through {@code javax.sound.midi}.</b> The JDK has a perfectly good
 * one, and it is in {@code java.desktop} -- which is a window toolkit, and which nothing on the
 * headless side of this program is allowed to want. The format that has to be produced here is a
 * header chunk and a track chunk of delta-timed events, which is about a hundred lines; a
 * dependency on the whole of AWT to avoid writing them would be the wrong way round.
 * <p>
 * Format 1, which is the multi-track one: every track shares a clock and a player shows them as
 * separate parts. Format 0 would flatten them into one and lose which voice played what, which is
 * most of the point of recording a chip that has one part per voice.
 */
public final class MidiFile {
    private static final byte[] HEADER = {'M', 'T', 'h', 'd'};
    private static final byte[] TRACK = {'M', 'T', 'r', 'k'};

    /**
     * Format 1: several tracks, one clock.
     */
    private static final int FORMAT = 1;

    private static final int META = 0xFF;
    private static final int META_TRACK_NAME = 0x03;
    private static final int META_TEMPO = 0x51;
    private static final int META_END = 0x2F;

    private static final int NOTE_ON = 0x90;
    private static final int PROGRAM_CHANGE = 0xC0;

    /**
     * How many of a track's ticks make a quarter note. A fact about the file rather than about the
     * music: what it decides is how finely a time can be written down.
     */
    private final int ticksPerQuarter;

    private final List<Track> tracks = new ArrayList<>();

    public MidiFile(final int ticksPerQuarter) {
        this.ticksPerQuarter = ticksPerQuarter;
    }

    /**
     * A part, which a player will show on a staff of its own.
     */
    public Track track(final String name) {
        var track = new Track(name);

        tracks.add(track);

        return track;
    }

    public void write(final Path path) throws IOException {
        try (var out = Files.newOutputStream(path)) {
            write(out);
        }
    }

    public void write(final OutputStream out) throws IOException {
        out.write(HEADER);
        writeInt(out, 6);
        writeShort(out, FORMAT);
        writeShort(out, tracks.size());
        writeShort(out, ticksPerQuarter);

        for (var track : tracks) {
            var body = track.bytes();

            out.write(TRACK);
            writeInt(out, body.length);
            out.write(body);
        }
    }

    /**
     * One part of the file: a list of things that happened, each at a tick.
     * <p>
     * Times are absolute here and turned into the deltas the format wants only at
     * {@link #bytes()}, which is what lets a caller put things down in whatever order it discovers
     * them -- a note's end is known long after its beginning.
     */
    public static final class Track {
        /**
         * One thing at one tick. Kept as the bytes that follow the delta time, since nothing here
         * ever looks at an event again.
         */
        private record Event(long tick, int order, byte[] message) {
        }

        private final String name;
        private final List<Event> events = new ArrayList<>();

        private Track(final String name) {
            this.name = name;
        }

        /**
         * How long a quarter note lasts, in microseconds. Belongs on the first track by convention,
         * and a player reads it from wherever it is.
         */
        public void tempo(final long tick, final int microsPerQuarter) {
            add(tick, (byte) META, (byte) META_TEMPO, (byte) 3,
                    (byte) (microsPerQuarter >> 16),
                    (byte) (microsPerQuarter >> 8),
                    (byte) microsPerQuarter);
        }

        /**
         * Which of the general MIDI instruments this channel should be played with.
         */
        public void program(final long tick, final int channel, final int program) {
            add(tick, (byte) (PROGRAM_CHANGE | (channel & 0x0F)), (byte) (program & 0x7F));
        }

        public void noteOn(final long tick, final int channel, final int key, final int velocity) {
            add(tick, (byte) (NOTE_ON | (channel & 0x0F)), (byte) (key & 0x7F),
                    (byte) (velocity & 0x7F));
        }

        /**
         * Sent as a note-on at velocity zero, which every player has understood as a note-off since
         * the format was young and which is a byte shorter under running status.
         */
        public void noteOff(final long tick, final int channel, final int key) {
            add(tick, (byte) (NOTE_ON | (channel & 0x0F)), (byte) (key & 0x7F), (byte) 0);
        }

        private void add(final long tick, final byte... message) {
            // The order a caller put two events down at the same tick is the order they are
            // written in, which is what keeps a note-off ahead of the note-on that replaces it.
            events.add(new Event(tick, events.size(), message));
        }

        private byte[] bytes() {
            var out = new ByteArrayOutputStream();

            try {
                if (name != null) {
                    var text = name.getBytes(StandardCharsets.US_ASCII);

                    writeVariable(out, 0);
                    out.write(META);
                    out.write(META_TRACK_NAME);
                    writeVariable(out, text.length);
                    out.write(text);
                }

                var sorted = new ArrayList<>(events);

                sorted.sort((a, b) -> a.tick() == b.tick()
                        ? Integer.compare(a.order(), b.order())
                        : Long.compare(a.tick(), b.tick()));

                var last = 0L;

                for (var event : sorted) {
                    writeVariable(out, event.tick() - last);
                    out.write(event.message());
                    last = event.tick();
                }

                writeVariable(out, 0);
                out.write(META);
                out.write(META_END);
                out.write(0);
            } catch (IOException impossible) {
                // A ByteArrayOutputStream cannot fail, and its write throws anyway.
                throw new IllegalStateException(impossible);
            }

            return out.toByteArray();
        }
    }

    // ================================================================================== internals

    /**
     * The format's variable length quantity: seven bits at a time, most significant first, with the
     * top bit set on every byte but the last.
     */
    private static void writeVariable(final OutputStream out, final long value) throws IOException {
        var buffer = value & 0x7F;
        var rest = value >> 7;

        while (rest > 0) {
            buffer <<= 8;
            buffer |= (rest & 0x7F) | 0x80;
            rest >>= 7;
        }

        while (true) {
            out.write((int) (buffer & 0xFF));

            if ((buffer & 0x80) == 0) {
                return;
            }

            buffer >>= 8;
        }
    }

    private static void writeInt(final OutputStream out, final int value) throws IOException {
        out.write(value >> 24);
        out.write(value >> 16);
        out.write(value >> 8);
        out.write(value);
    }

    private static void writeShort(final OutputStream out, final int value) throws IOException {
        out.write(value >> 8);
        out.write(value);
    }
}
