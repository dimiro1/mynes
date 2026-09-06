package com.github.dimiro1.mynes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the chip says about its own five voices.
 * <p>
 * The point of these is the arithmetic rather than the plumbing. A period is not a frequency and
 * the two channels that look alike do not turn one into the other the same way: a pulse's sequencer
 * is eight steps of two CPU cycles and the triangle's is thirty-two of one, so the same period
 * written to both is an octave apart -- which is why game music is written with the bass a period
 * lower rather than an octave lower, and why anybody reading a period out of a register needs this
 * to tell them what they are listening to.
 */
class APUVoiceStateTests {
    /**
     * $FD, which is what a period of 253 comes out as: 1789773 / (16 * 254) is 440.4Hz, an A above
     * middle C to within two cents. Written across $4002 and $4003 the way a game writes it.
     */
    private static final int A440_PERIOD = 253;

    /**
     * A pulse playing A440 at full volume, with the length counter loaded so it keeps playing.
     */
    private static APU playingA(final int register) {
        var apu = new APU(line -> { }, line -> { });

        apu.write(0x4015, 0x0F);
        apu.write(register, 0xBF);              // 50% duty, halt, constant volume 15
        apu.write(register + 2, A440_PERIOD & 0xFF);
        apu.write(register + 3, (A440_PERIOD >> 8) & 7);

        return apu;
    }

    @Test
    void aPulsePlayingAnAIsAnA() {
        var voice = playingA(0x4000).voice(APUChannel.PULSE_1);

        assertEquals(APUChannel.PULSE_1, voice.channel());
        assertTrue(voice.playing());
        assertEquals(A440_PERIOD, voice.period());
        assertEquals(440, voice.hertz(), 1);
        assertEquals(15, voice.volume());
        assertTrue(voice.constant());
        assertEquals(2, voice.duty(), "50%");
        assertEquals(-1, voice.address(), "a pulse has no sample address");
    }

    /**
     * The same period on the triangle, whose sequencer is twice as long and clocked twice as often,
     * so it comes out an octave lower.
     */
    @Test
    void theTrianglePlaysTheSamePeriodAnOctaveLower() {
        var apu = new APU(line -> { }, line -> { });

        apu.write(0x4015, 0x0F);
        apu.write(0x4008, 0xFF);                // control set, linear counter loaded
        apu.write(0x400A, A440_PERIOD & 0xFF);
        apu.write(0x400B, (A440_PERIOD >> 8) & 7);

        var voice = apu.voice(APUChannel.TRIANGLE);

        assertEquals(220, voice.hertz(), 1);
        assertEquals(-1, voice.volume(), "the triangle has no volume control at all");
        assertTrue(voice.loop(), "$4008 bit 7 is the control bit, which halts the length counter");
    }

    /**
     * A PAL machine's processor is slower, so the same period is a lower note -- which is the whole
     * reason the frequency is worked out in the chip rather than in a window that has no idea what
     * it is plugged into.
     */
    @Test
    void thePitchFollowsTheConsole() {
        var pal = new APU(line -> { }, line -> { }, Region.PAL);

        pal.write(0x4015, 0x0F);
        pal.write(0x4000, 0xBF);
        pal.write(0x4002, A440_PERIOD & 0xFF);
        pal.write(0x4003, (A440_PERIOD >> 8) & 7);

        assertEquals(409, pal.voice(APUChannel.PULSE_1).hertz(), 1);
    }

    /**
     * A period the sweep unit mutes has no note to name, and saying 55kHz would be worse than
     * saying nothing.
     */
    @Test
    void aPeriodTooLowToPlayHasNoPitch() {
        var apu = new APU(line -> { }, line -> { });

        apu.write(0x4015, 0x0F);
        apu.write(0x4000, 0xBF);
        apu.write(0x4002, 0x04);
        apu.write(0x4003, 0x00);

        assertEquals(0, apu.voice(APUChannel.PULSE_1).hertz());
    }

    @Test
    void theNoiseSaysWhichOfItsTwoSequencesIsRunning() {
        var apu = new APU(line -> { }, line -> { });

        apu.write(0x4015, 0x0F);
        apu.write(0x400C, 0x1A);
        apu.write(0x400E, 0x04);

        assertFalse(apu.voice(APUChannel.NOISE).shortMode());
        assertEquals(-1, apu.voice(APUChannel.NOISE).duty(), "the noise has no duty cycle");

        apu.write(0x400E, 0x84);
        assertTrue(apu.voice(APUChannel.NOISE).shortMode());
    }

    /**
     * Where the sample is up to rather than where it started, which is the question anybody watching
     * one play is asking.
     */
    @Test
    void theDMCSaysWhereItIsReadingAndHowMuchIsLeft() {
        var apu = new APU(line -> { }, line -> { });

        apu.write(0x4012, 0x40);                // $C000 + 64 * 64
        apu.write(0x4013, 0x02);                // 2 * 16 + 1 bytes
        apu.write(0x4015, 0x10);

        var voice = apu.voice(APUChannel.DMC);

        assertTrue(voice.playing());
        assertEquals(0xD000, voice.address());
        assertEquals(33, voice.bytesLeft());
        assertEquals(0, voice.length(), "the DMC has no length counter");
    }

    /**
     * The meters, which cost the mixer nothing until somebody asks for them.
     */
    @Test
    void nothingIsMeasuredUntilAMeterAsksForIt() {
        var apu = playingA(0x4000);
        var peaks = new int[APUChannel.values().length];

        for (var i = 0; i < 20_000; i++) {
            apu.tick();
        }

        apu.peaks(peaks);
        assertEquals(0, peaks[APUChannel.PULSE_1.ordinal()], "nobody was looking");

        apu.setPeakTracking(true);

        for (var i = 0; i < 20_000; i++) {
            apu.tick();
        }

        apu.peaks(peaks);
        assertEquals(15, peaks[APUChannel.PULSE_1.ordinal()], "full volume, which is what it plays");

        apu.peaks(peaks);
        assertEquals(15, peaks[APUChannel.PULSE_1.ordinal()], "and reading them does not empty them");

        apu.clearPeaks();
        apu.peaks(peaks);
        assertEquals(0, peaks[APUChannel.PULSE_1.ordinal()], "which is a thing to be asked for");
    }

    /**
     * The noise's number is a shift rate rather than a pitch, and in one of its two modes it does
     * divide down into one.
     * <p>
     * In the usual mode the register runs a sequence 32767 steps long and what comes out is hiss
     * with no pitch at all. Tap it six bits along instead of one and the sequence is 93 steps, which
     * repeats fast enough to be heard -- and 4811.2Hz for the shortest period is the first entry of
     * the table every reference prints for this channel, which is what makes it a check rather than
     * a restatement of the code.
     *
     * @see <a href="https://www.nesdev.org/wiki/APU_Noise">NESdev: APU noise</a>
     */
    @Test
    void theNoiseHasAPitchOnlyInShortMode() {
        var apu = new APU(line -> { }, line -> { });

        apu.write(0x4015, 0x0F);
        apu.write(0x400C, 0x3F);
        apu.write(0x400E, 0x00);                // long mode, the shortest period
        apu.write(0x400F, 0x08);

        var hiss = apu.voice(APUChannel.NOISE);

        assertFalse(hiss.shortMode());
        assertEquals(0, hiss.pitch(), "a sequence of 32767 steps is not a note");
        assertTrue(hiss.hertz() > 0, "though the register is being clocked all the same");

        apu.write(0x400E, 0x80);                // the same period, short mode

        var tone = apu.voice(APUChannel.NOISE);

        assertTrue(tone.shortMode());
        assertEquals(hiss.hertz(), tone.hertz(), 0.01, "the rate is the rate either way");
        assertEquals(4811.2, tone.pitch(), 0.1, "and 93 steps of it is the note that comes out");
    }

    /**
     * The DMC never has one, whatever it is playing: the pitch of a sample is a fact about the bytes
     * in it rather than about the chip.
     */
    @Test
    void theDMCNeverHasAPitch() {
        var apu = new APU(line -> { }, line -> { });

        apu.write(0x4015, 0x1F);
        apu.write(0x4010, 0x0F);

        assertEquals(0, apu.voice(APUChannel.DMC).pitch());
    }

    /**
     * A voice somebody has switched off still moves its meter, which is what tells "this is playing
     * and you cannot hear it" from "this is not playing".
     */
    @Test
    void aMutedVoiceStillHasALevel() {
        var apu = playingA(0x4000);
        var peaks = new int[APUChannel.values().length];

        apu.setPeakTracking(true);
        apu.setChannelMuted(APUChannel.PULSE_1, true);

        for (var i = 0; i < 20_000; i++) {
            apu.tick();
        }

        apu.peaks(peaks);
        assertEquals(15, peaks[APUChannel.PULSE_1.ordinal()]);
    }
}
