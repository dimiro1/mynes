package com.github.dimiro1.mynes.ui.music;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.midi.MidiFile;
import com.github.dimiro1.mynes.ui.sound.Notes;

import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/**
 * The music a game is playing, written down as it plays.
 * <p>
 * The Sound tab says what note each voice is on and the keyboards say where; this is the same
 * reading kept rather than watched, so that a tune can be taken away and looked at in something
 * that knows about music. What it is <b>not</b> is a transcription: nothing here works out a key,
 * a time signature or where the bars are, because none of that is in the chip -- what is in the
 * chip is which voice held which note for how many frames, and that is exactly what comes out.
 * <p>
 * <b>Once per frame, on the emulation thread.</b> Four times a second, which is what everything
 * else in the front end reads the machine at, would miss most of a melody: a note at 150bpm in
 * semiquavers is a tenth of a second long. A frame is the rate a game's own music driver runs at,
 * so a frame is the rate that loses nothing -- the same argument {@code PadPolling} makes for
 * counting polls, and the same place in the loop.
 * <p>
 * <b>Three voices, and the reasons the other two are out are different.</b> The two pulses and the
 * triangle have a pitch and it is the note they are playing. The noise has one only in short mode,
 * which seven cartridges' worth of measurement put at about one and a half per cent of frames --
 * and its part is percussion, which would need a drum to be chosen for it, which would be this
 * program inventing something the game never said. The DMC is playing a recording of something,
 * and what pitch that comes out at is a fact about the bytes rather than about the chip.
 * <p>
 * <b>A tick is a frame.</b> A game's tempo is not written down anywhere in it, so any bar line this
 * put in would be a guess -- what it can be exact about is time, so it is: the tempo is set to make
 * one tick last one frame on the console that played it, and a quarter note is twenty four frames
 * because something has to be. The timing is a recording; the bar lines are furniture.
 * <p>
 * <b>The nearest semitone, and no pitch bends.</b> The chip's periods are integers, so most notes
 * come out a few cents off -- see {@code Notes.centsOff}, and the Sound tab makes a point of it.
 * Bending each note to match would be truer to what was heard and would make the file worse to
 * work with: the bend range is the receiving synth's opinion rather than anything in the file, and
 * a page of bends is what a musician has to delete before they can read the tune. What is here is
 * the note that was meant.
 */
public final class MusicRecorder {
    /**
     * How many ticks make a quarter note. Chosen so a quarter is twenty four frames, which is
     * 0.4 seconds and about 150bpm -- a tempo that looks like a tempo, which is as much as can
     * honestly be claimed for it.
     */
    private static final int TICKS_PER_QUARTER = 24;

    private static final int MICROS_PER_SECOND = 1_000_000;

    /**
     * The voices with a note. See the class comment for why the other two are not here.
     */
    private static final APUChannel[] PARTS = {
            APUChannel.PULSE_1, APUChannel.PULSE_2, APUChannel.TRIANGLE,
    };

    /**
     * Which General MIDI instrument each part is given.
     * <p>
     * A square lead for the pulses and a synth bass for the triangle, which is what those voices
     * are doing rather than what they sound like: nothing in General MIDI sounds like a 2A03, and a
     * file that opened on a grand piano would be harder to follow than one that opened on something
     * shaped like the part.
     */
    private static final Map<APUChannel, Integer> INSTRUMENTS = Map.of(
            APUChannel.PULSE_1, 80,     // Lead 1 (square)
            APUChannel.PULSE_2, 80,
            APUChannel.TRIANGLE, 38);   // Synth Bass 1

    /**
     * The loudest a voice's envelope goes, which is what a velocity is scaled from.
     */
    private static final int FULL_VOLUME = 15;

    private static final int FULL_VELOCITY = 127;

    /**
     * The triangle has no volume of its own -- it is on or it is off -- so it is written down at
     * one level rather than at whatever the envelope of a channel it does not have would say.
     */
    private static final int TRIANGLE_VELOCITY = 100;

    private final MidiFile file = new MidiFile(TICKS_PER_QUARTER);
    private final Map<APUChannel, MidiFile.Track> tracks = new EnumMap<>(APUChannel.class);

    /**
     * The key each part is holding, or {@link Notes#NO_KEY} for a part that is silent.
     */
    private final Map<APUChannel, Integer> sounding = new EnumMap<>(APUChannel.class);

    private long tick;

    /**
     * Whether a note has ever been written down. Its own flag rather than a question put to the
     * tracks, which are never empty: each carries an instrument and the first carries the tempo,
     * and neither of those is something a game played.
     */
    private boolean played;

    /**
     * @param region which console this is, since a frame is 16.6ms on one and 20ms on the other and
     *               a recording that got that wrong would come out at the wrong speed.
     */
    public MusicRecorder(final Region region) {
        var channel = 0;

        for (var part : PARTS) {
            var track = file.track(part.label());

            tracks.put(part, track);
            sounding.put(part, Notes.NO_KEY);
            track.program(0, channel++, INSTRUMENTS.get(part));
        }

        tracks.get(PARTS[0]).tempo(0, microsPerQuarter(region));
    }

    /**
     * A frame has finished. Called on the thread clocking the machine, at the boundary.
     */
    public void frame(final APU apu) {
        for (var part : PARTS) {
            var voice = apu.voice(part);
            var key = keyOf(voice);
            var was = sounding.get(part);

            if (key == was) {
                continue;
            }

            var track = tracks.get(part);
            var channel = channelOf(part);

            if (was != Notes.NO_KEY) {
                track.noteOff(tick, channel, was);
            }

            if (key != Notes.NO_KEY) {
                track.noteOn(tick, channel, key, velocityOf(part, voice));
                played = true;
            }

            sounding.put(part, key);
        }

        tick++;
    }

    /**
     * Closes whatever is still sounding and writes the file.
     * <p>
     * A note left open would play until the end of the file in some players and be dropped by
     * others, which is two different wrong answers rather than one.
     */
    public void writeTo(final Path path) throws IOException {
        for (var part : PARTS) {
            var key = sounding.get(part);

            if (key != Notes.NO_KEY) {
                tracks.get(part).noteOff(tick, channelOf(part), key);
                sounding.put(part, Notes.NO_KEY);
            }
        }

        file.write(path);
    }

    /**
     * How many frames have been written down, which is what a status line counts.
     */
    public long frames() {
        return tick;
    }

    /**
     * Whether anything at all was played, so that a caller can say so rather than writing a file
     * with three empty parts in it.
     */
    public boolean isEmpty() {
        return !played;
    }

    // ================================================================================== internals

    /**
     * Which note this voice is on, or none.
     * <p>
     * Through {@code pitch()} rather than {@code hertz()}, which is the same call the Sound tab and
     * the dashboard make, so that three places cannot come to disagree about what a voice is
     * playing. A key outside a piano is dropped rather than clamped: the pulses run up past 12kHz
     * where the sweep unit silences them, and a note nobody could hear is not one to write down.
     */
    private static int keyOf(final APU.VoiceState voice) {
        if (!voice.playing()) {
            return Notes.NO_KEY;
        }

        var key = Notes.keyOf(voice.pitch());

        return key >= Notes.LOWEST_KEY && key <= Notes.HIGHEST_KEY ? key : Notes.NO_KEY;
    }

    private static int velocityOf(final APUChannel part, final APU.VoiceState voice) {
        if (part == APUChannel.TRIANGLE) {
            return TRIANGLE_VELOCITY;
        }

        return Math.max(1, voice.volume() * FULL_VELOCITY / FULL_VOLUME);
    }

    /**
     * One MIDI channel per part, so that a player can give each its own instrument. Channel 9 is
     * the percussion one and none of these is percussion, which is the one number to stay off.
     */
    private static int channelOf(final APUChannel part) {
        for (var i = 0; i < PARTS.length; i++) {
            if (PARTS[i] == part) {
                return i;
            }
        }

        return 0;
    }

    /**
     * How long a quarter note lasts on this console, so that one tick lasts one frame.
     */
    private static int microsPerQuarter(final Region region) {
        var frameMicros = MICROS_PER_SECOND / framesPerSecond(region);

        return (int) Math.round(frameMicros * TICKS_PER_QUARTER);
    }

    private static double framesPerSecond(final Region region) {
        return MICROS_PER_SECOND * 1000.0 / region.frameNanos();
    }
}
