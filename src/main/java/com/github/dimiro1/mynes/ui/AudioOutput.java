package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.APU;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * The sound card, as the emulation loop sees it: somewhere to put the samples the APU has
 * finished with.
 * <p>
 * One line, opened once and held for as long as a machine is running, and touched from the
 * emulation thread and no other -- same rule as the NES itself. {@link EmulatorRunner} drains the
 * APU once a frame and hands what it got straight to {@link #write}.
 * <p>
 * At normal speed that write is the second half of the loop's pacing. The line drains at exactly
 * 44100 samples a second of real time and the APU fills it at 44100 samples a second of emulated
 * time, so a blocking write cannot let the machine run fast: whichever clock is slower is the one
 * that paces, and the difference between them lands in the loop's own lag clamp. Fast forwarding
 * turns that off -- there is no way to hand a sound card audio faster than real time -- and what
 * does not fit is dropped, which is why fast forward sounds chopped rather than sped up.
 * <p>
 * A machine with no sound device is not an error. {@link #open()} says so in the log and
 * everything after it does nothing, which is what lets the test suite and a headless CI run the
 * whole emulator without one.
 */
public final class AudioOutput {
    private static final Logger logger = LoggerFactory.getLogger("UI");

    /**
     * How much sound the card is allowed to be holding, counted in frames' worth.
     * <p>
     * The latency dial. Less than this and a frame that takes a moment too long leaves the card
     * with nothing to play, which is heard as a click; much more and a button press is audibly
     * late. Four frames is about 67 milliseconds.
     */
    private static final int BUFFER_FRAMES = 4;

    private static final int BUFFER_SAMPLES = BUFFER_FRAMES * APU.SAMPLE_RATE / 60;

    private static final int BYTES_PER_SAMPLE = 2;

    /**
     * Signed sixteen bit, one channel, little endian -- which is what
     * {@link APU#drainSamples(short[])} produces, and what every sound card takes without
     * resampling it first.
     */
    private static final AudioFormat FORMAT =
            new AudioFormat(APU.SAMPLE_RATE, 8 * BYTES_PER_SAMPLE, 1, true, false);

    /**
     * The line, or null when there is no sound device -- which is the silent-but-running case
     * every method below is written around.
     */
    private SourceDataLine line;

    /**
     * The samples, as bytes. Kept between calls rather than allocated per frame: sixty a second
     * of a few kilobytes each is garbage the emulation thread does not need to be making.
     */
    private byte[] bytes = new byte[BUFFER_SAMPLES * BYTES_PER_SAMPLE];

    private boolean muted;

    /**
     * Opens the line. Call once, from the emulation thread, before the first {@link #write}.
     */
    public void open() {
        if (line != null) {
            throw new IllegalStateException("already open");
        }

        try {
            line = AudioSystem.getSourceDataLine(FORMAT);
            line.open(FORMAT, BUFFER_SAMPLES * BYTES_PER_SAMPLE);
            line.start();

            logger.info("audio open at {}Hz with {}ms of buffer",
                    APU.SAMPLE_RATE, 1000 * BUFFER_SAMPLES / APU.SAMPLE_RATE);
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            // No device, no mixer, or no permission to reach one. None of those are a reason not
            // to run the machine.
            line = null;
            logger.warn("no audio device available, running silently", e);
        }
    }

    /**
     * Hands the card a run of samples.
     *
     * @param samples  the samples, as {@link APU#drainSamples(short[])} left them.
     * @param count    how many of them are real.
     * @param blocking true to wait until they all fit, which is what paces the machine at normal
     *                 speed; false to write what fits now and drop the rest, which is what fast
     *                 forwarding needs.
     */
    public void write(final short[] samples, final int count, final boolean blocking) {
        if (line == null || count <= 0) {
            return;
        }

        var length = count * BYTES_PER_SAMPLE;

        if (bytes.length < length) {
            bytes = new byte[length];
        }

        for (var i = 0; i < count; i++) {
            // Muting writes silence rather than writing nothing, so that the pacing a blocking
            // write does is exactly the same muted as not.
            var sample = muted ? 0 : samples[i];

            bytes[2 * i] = (byte) sample;
            bytes[2 * i + 1] = (byte) (sample >> 8);
        }

        if (!blocking) {
            // Rounded down to a whole sample: half of one would put every sample after it in the
            // wrong byte order, for good.
            length = Math.min(length, line.available() & ~1);
        }

        if (length > 0) {
            line.write(bytes, 0, length);
        }
    }

    /**
     * Throws away whatever the card has not played yet.
     * <p>
     * For the moments where what is queued has stopped being true: pausing, where it would
     * otherwise play on for a tenth of a second after the picture froze, and resuming, where it
     * would be a tenth of a second of the past.
     */
    public void flush() {
        if (line != null) {
            line.flush();
        }
    }

    /**
     * Silences the output without stopping it. What is already queued goes on playing, which is at
     * most a frame or two.
     */
    public void setMuted(final boolean muted) {
        this.muted = muted;
    }

    /**
     * Gives the line back. Anything queued is dropped rather than played out: this is called when
     * a machine is being torn down, and finishing the last tenth of a second of its audio is not
     * worth making the event dispatch thread wait for.
     */
    public void close() {
        if (line == null) {
            return;
        }

        line.stop();
        line.flush();
        line.close();
        line = null;

        logger.info("audio closed");
    }
}
