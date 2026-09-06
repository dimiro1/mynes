package com.github.dimiro1.mynes.ui.music;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.midi.MetaMessage;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Voices turned into notes, and read back by somebody else.
 * <p>
 * The files are read with {@code javax.sound.midi}, which is a wholly separate implementation of
 * the format and is the whole reason these tests are worth anything: a check that parsed the bytes
 * with the code that wrote them would agree with whatever it did. It is only used here -- the writer
 * itself will not have {@code java.desktop}, for the reason {@link
 * com.github.dimiro1.mynes.midi.MidiFile} gives.
 */
class MusicRecorderTests {
    /**
     * $FD, a period of 253, which comes out of a pulse at 440.4Hz -- an A above middle C to within
     * two cents, and MIDI key 69.
     */
    private static final int A440_PERIOD = 253;

    private static final int A440_KEY = 69;

    @TempDir
    private Path directory;

    /**
     * A pulse holding one note for a while comes out as one note held for that long, rather than as
     * a note per frame. Which is the whole of what a recorder has to get right.
     */
    @Test
    void aVoiceHoldingANoteIsOneNote() throws Exception {
        var apu = playingA();
        var recorder = new MusicRecorder(Region.NTSC);

        for (var frame = 0; frame < 30; frame++) {
            recorder.frame(apu);
        }

        var notes = notesOf(write(recorder), "Pulse 1");

        assertEquals(1, notes.size(), "one note, not thirty");
        assertEquals(A440_KEY, notes.getFirst().key());
        assertEquals(0, notes.getFirst().start(), "from the first frame it was recorded on");
        assertEquals(30, notes.getFirst().end(), "to the frame the recording stopped");
    }

    /**
     * A tick is a frame, and the tempo is what makes that true. Checked through the sequence's own
     * idea of how long it is, which is the division and the tempo put back together by somebody
     * else's arithmetic.
     */
    @Test
    void aTickLastsExactlyOneFrame() throws Exception {
        var apu = playingA();
        var recorder = new MusicRecorder(Region.NTSC);

        for (var frame = 0; frame < 600; frame++) {
            recorder.frame(apu);
        }

        var sequence = MidiSystem.getSequence(write(recorder).toFile());

        assertEquals(600, sequence.getTickLength(), "one tick per frame");
        assertEquals(
                600 / 60.0988,
                sequence.getMicrosecondLength() / 1_000_000.0,
                0.01,
                "and six hundred NTSC frames is just under ten seconds");
    }

    /**
     * The other console's frame is 20ms rather than 16.6, so the same six hundred frames are two
     * seconds longer. A recorder that took the region for granted would play a PAL game's music
     * twenty per cent fast.
     */
    @Test
    void aPalRecordingRunsAtPalSpeed() throws Exception {
        var apu = new APU(line -> { }, line -> { }, Region.PAL);
        var recorder = new MusicRecorder(Region.PAL);

        start(apu);

        for (var frame = 0; frame < 600; frame++) {
            recorder.frame(apu);
        }

        var sequence = MidiSystem.getSequence(write(recorder).toFile());

        assertEquals(
                600 / 50.007,
                sequence.getMicrosecondLength() / 1_000_000.0,
                0.01,
                "six hundred PAL frames is twelve seconds");
    }

    /**
     * A note that changes is the old one ending and a new one starting, in that order -- a note-on
     * for the new pitch arriving before the old one's note-off would leave the old note sounding
     * for ever in a player that pairs them up by channel.
     */
    @Test
    void changingPitchEndsOneNoteAndStartsAnother() throws Exception {
        var apu = playingA();
        var recorder = new MusicRecorder(Region.NTSC);

        for (var frame = 0; frame < 10; frame++) {
            recorder.frame(apu);
        }

        // An octave down, which is twice the period.
        var lower = 2 * A440_PERIOD + 1;

        apu.write(0x4002, lower & 0xFF);
        apu.write(0x4003, (lower >> 8) & 7);

        for (var frame = 0; frame < 10; frame++) {
            recorder.frame(apu);
        }

        var notes = notesOf(write(recorder), "Pulse 1");

        assertEquals(2, notes.size());
        assertEquals(A440_KEY, notes.get(0).key());
        assertEquals(10, notes.get(0).end(), "the first ends where the second begins");
        assertEquals(A440_KEY - 12, notes.get(1).key(), "an octave down");
        assertEquals(10, notes.get(1).start());
    }

    /**
     * The noise and the DMC have no part, which is a decision rather than an oversight -- see
     * {@link MusicRecorder}. Three tracks, and they are the three voices with a note.
     */
    @Test
    void thereAreThreePartsAndTheyAreNamed() throws Exception {
        var recorder = new MusicRecorder(Region.NTSC);

        recorder.frame(playingA());

        var sequence = MidiSystem.getSequence(write(recorder).toFile());
        var names = new ArrayList<String>();

        for (var track : sequence.getTracks()) {
            names.add(nameOf(track));
        }

        assertEquals(List.of("Pulse 1", "Pulse 2", "Triangle"), names);
    }

    /**
     * A game whose music has not started is the commonest way to end up with an empty recording, and
     * it is worth being able to say so rather than writing three empty parts.
     */
    @Test
    void aRecordingOfSilenceSaysItIsEmpty() {
        var apu = new APU(line -> { }, line -> { });
        var recorder = new MusicRecorder(Region.NTSC);

        for (var frame = 0; frame < 30; frame++) {
            recorder.frame(apu);
        }

        assertTrue(recorder.isEmpty());
        assertEquals(30, recorder.frames(), "though it did watch thirty frames of it");

        recorder.frame(playingA());
        assertFalse(recorder.isEmpty());
    }

    // ================================================================================== helpers

    /**
     * One note as a player sees it: which key, and the ticks it started and ended on.
     */
    private record Note(int key, long start, long end) {
    }

    private static APU playingA() {
        var apu = new APU(line -> { }, line -> { });

        start(apu);

        return apu;
    }

    /**
     * Pulse 1 holding A440 at full volume, with the length counter loaded so it keeps playing.
     */
    private static void start(final APU apu) {
        apu.write(0x4015, 0x0F);
        apu.write(0x4000, 0xBF);
        apu.write(0x4002, A440_PERIOD & 0xFF);
        apu.write(0x4003, (A440_PERIOD >> 8) & 7);
    }

    private Path write(final MusicRecorder recorder) throws IOException {
        var path = directory.resolve("music.mid");

        recorder.writeTo(path);

        return path;
    }

    private static List<Note> notesOf(final Path path, final String part) throws Exception {
        var sequence = MidiSystem.getSequence(path.toFile());

        for (var track : sequence.getTracks()) {
            if (part.equals(nameOf(track))) {
                return notesOf(track);
            }
        }

        throw new AssertionError("no part called " + part);
    }

    private static List<Note> notesOf(final Track track) {
        var notes = new ArrayList<Note>();
        var open = new java.util.HashMap<Integer, Long>();

        for (var i = 0; i < track.size(); i++) {
            if (!(track.get(i).getMessage() instanceof ShortMessage message)) {
                continue;
            }

            if (message.getCommand() != ShortMessage.NOTE_ON) {
                continue;
            }

            if (message.getData2() > 0) {
                open.put(message.getData1(), track.get(i).getTick());
            } else {
                var started = open.remove(message.getData1());

                if (started != null) {
                    notes.add(new Note(message.getData1(), started, track.get(i).getTick()));
                }
            }
        }

        notes.sort(java.util.Comparator.comparingLong(Note::start));

        return notes;
    }

    private static String nameOf(final Track track) {
        for (var i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof MetaMessage message && message.getType() == 3) {
                return new String(message.getData());
            }
        }

        return null;
    }
}
