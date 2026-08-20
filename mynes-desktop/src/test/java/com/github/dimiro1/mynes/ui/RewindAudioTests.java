package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The sound of the last few seconds, ready to be played backwards.
 * <p>
 * Two properties matter and neither is obvious from the shape of the class. The samples have to come
 * back in exactly the reverse of the order they went in, across frame boundaries as well as within
 * them -- a ring that reversed each frame but handed the frames over oldest-first would sound like
 * the game stuttering rather than rewinding. And a frame has to be <em>taken</em> rather than read,
 * because the states it belongs to are discarded as the rewind passes them and sound played twice
 * would be sound the picture never showed.
 */
class RewindAudioTests {

    /**
     * Somewhere for {@code take} to put what it hands back. Bigger than any number of frames these
     * tests ask for, so a short answer is the ring running out rather than the buffer filling up.
     */
    private final short[] out = new short[4096];

    /**
     * Frames of easily recognisable sound: frame 1 is 100, 101, 102 and so on, so a sample says both
     * which frame it came from and where in it.
     */
    private static short[] frame(final int number, final int length) {
        var samples = new short[length];

        for (var i = 0; i < length; i++) {
            samples[i] = (short) (number * 100 + i);
        }

        return samples;
    }

    private static void capture(final RewindAudio audio, final int number, final int length) {
        audio.capture(frame(number, length), length);
    }

    @Test
    void aFrameComesBackBackwards() {
        var audio = new RewindAudio(4);
        capture(audio, 1, 3);

        assertEquals(3, audio.take(1, out));
        assertArrayEquals(new short[]{102, 101, 100}, first(3));
    }

    /**
     * The whole point: two frames asked for at once are one continuous run of sound in reverse, not
     * two reversed frames in forward order.
     */
    @Test
    void severalFramesComeBackAsOneRunOfSoundInReverse() {
        var audio = new RewindAudio(4);
        capture(audio, 1, 3);
        capture(audio, 2, 3);

        assertEquals(6, audio.take(2, out));
        assertArrayEquals(new short[]{202, 201, 200, 102, 101, 100}, first(6));
    }

    @Test
    void aFrameIsGoneOnceItHasBeenTaken() {
        var audio = new RewindAudio(4);
        capture(audio, 1, 2);
        capture(audio, 2, 2);

        audio.take(1, out);

        assertEquals(1, audio.size(), "the newest went with the state it belonged to");
        assertEquals(2, audio.take(1, out));
        assertArrayEquals(new short[]{101, 100}, first(2));
        assertEquals(0, audio.take(1, out), "and there is nothing left");
    }

    /**
     * A silent frame still takes its place in the ring. Skipping it would put every frame after it
     * one out of step with the states, and the rewind would play sound from the wrong second.
     */
    @Test
    void aSilentFrameStillCountsAsAFrame() {
        var audio = new RewindAudio(4);
        capture(audio, 1, 2);
        audio.capture(new short[0], 0);
        capture(audio, 3, 2);

        assertEquals(3, audio.size());
        assertEquals(4, audio.take(3, out), "three frames, four samples between them");
        assertArrayEquals(new short[]{301, 300, 101, 100}, first(4));
    }

    @Test
    void theOldestFrameIsDroppedWhenItIsFull() {
        var audio = new RewindAudio(2);
        capture(audio, 1, 1);
        capture(audio, 2, 1);
        capture(audio, 3, 1);

        assertEquals(2, audio.size());
        assertEquals(2, audio.take(9, out), "asking for more than it kept is not an error");
        assertArrayEquals(new short[]{300, 200}, first(2));
    }

    /**
     * The ring is written round and round, so the interesting case is the one where a frame is taken
     * from a slot that has been reused since -- which the arithmetic has to walk backwards through
     * without falling off the front of the array.
     */
    @Test
    void itKeepsWorkingOnceItHasWrappedSeveralTimes() {
        var audio = new RewindAudio(3);

        for (var i = 1; i <= 20; i++) {
            capture(audio, i, 2);
        }

        assertEquals(6, audio.take(3, out));
        assertArrayEquals(
                new short[]{2001, 2000, 1901, 1900, 1801, 1800},
                first(6),
                "the last three frames, newest first and each one backwards");
    }

    @Test
    void aRingNobodyFedHandsBackNothing() {
        assertEquals(0, new RewindAudio(4).take(1, out));
    }

    private short[] first(final int count) {
        var head = new short[count];

        System.arraycopy(out, 0, head, 0, count);

        return head;
    }
}
