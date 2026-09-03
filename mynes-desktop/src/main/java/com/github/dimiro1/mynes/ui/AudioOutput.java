package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.APU;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.Arrays;

/**
 * The sound card, as the emulation loop sees it: somewhere to put the samples the APU has
 * finished with.
 * <p>
 * One line, opened once and held for as long as a machine is running, and touched from the
 * emulation thread and no other -- same rule as the NES itself. {@link EmulatorRunner} drains the
 * APU once a frame and hands what it got straight to {@link #write}.
 * <p>
 * A machine with no sound device is not an error. {@link #open()} says so in the log and
 * everything after it does nothing, which is what lets the test suite and a headless CI run the
 * whole emulator without one.
 *
 * <h2>Two clocks, and why they have to be reconciled</h2>
 *
 * The APU makes exactly 44100 samples per second <em>of emulated time</em> and the card plays
 * exactly 44100 samples per second <em>of its own crystal's time</em>, and those are two different
 * seconds. The loop paces the emulated one against the host's monotonic clock, so what is left is
 * the difference between that clock and the card's -- tens of parts per million, which sounds like
 * nothing until it is integrated. At 50ppm the queue between them gains or loses a sample every
 * twenty-seven frames, which is a sixty millisecond cushion emptied or overflowed inside twenty
 * minutes: a click either way.
 * <p>
 * So the queue's own fill level is measured every frame and the samples are resampled by up to half
 * a percent to steer it back to the middle -- dynamic rate control, the same arrangement bsnes
 * uses. Half a percent is eight cents of pitch, which nobody hears, and it is a hundred times more
 * authority than any real crystal pair needs. That last part is the point of keeping it small: a
 * mixer that reports its fill level badly can push this to its stop and the whole cost is half a
 * percent of pitch, where a control loop with real authority would sing.
 * <p>
 * The resampling is linear, which is a gentle time-varying low pass. What it costs is half a
 * decibel at 5kHz at worst and a tenth at 2kHz -- see {@link Resampler}.
 *
 * <h2>What the blocking write is now for</h2>
 *
 * It used to be the pacing: the card drains in real time, so a blocking write could not let the
 * machine run fast. It is not that any more, because the loop's own deadline does that job and two
 * things pacing one loop is what the drift above was made of. What it is now is the backstop --
 * the card is opened at twice the wanted latency and held at one of them, so there is always a whole
 * latency's room above the target and the write never actually blocks; on the transient where it
 * does, a stalled frame is better than dropped samples.
 * <p>
 * Fast forwarding and rewinding do not go through any of this. Both hand frames over faster than
 * the card can play them, so the fill level says "full" every frame and a rate control reading it
 * would squeeze the sound down to nothing; both write what fits and drop the rest, which is why
 * they sound chopped rather than sped up.
 */
public final class AudioOutput {
    private static final Logger logger = System.getLogger("UI");

    /**
     * How much sound the card is kept holding, in milliseconds.
     * <p>
     * The latency dial, and the reason it is a dial: less than this and a frame that runs long
     * leaves the card with nothing to play, which is heard as a click, and much more and a button
     * press is audibly late. Sixty milliseconds is three and a half frames of slack, which absorbs
     * a garbage collection without being late enough to feel.
     */
    public static final int DEFAULT_LATENCY_MS = 60;

    /**
     * The floor, which is about one frame. Below that a single frame that ran long empties the card,
     * so there is no setting down there that is not clicking.
     */
    public static final int MIN_LATENCY_MS = 20;

    /**
     * The ceiling. Past a fifth of a second the delay between a button and its sound is something a
     * player feels rather than something they only measure.
     */
    public static final int MAX_LATENCY_MS = 200;

    private static final int BYTES_PER_SAMPLE = 2;

    /**
     * How far the rate control may bend the sample rate, either way.
     * <p>
     * 0.5%, which is eight cents. See the class comment for why small is the point rather than a
     * compromise.
     */
    private static final double MAX_RATE_ADJUST = 0.005;

    /**
     * Signed sixteen bit, one channel, little endian -- which is what
     * {@link APU#drainSamples(short[])} produces, and what every sound card takes without
     * resampling it first.
     */
    private static final AudioFormat FORMAT =
            new AudioFormat(APU.SAMPLE_RATE, 8 * BYTES_PER_SAMPLE, 1, true, false);

    /**
     * How full the card is meant to be kept: the latency that was asked for, which is half of the
     * buffer the line was opened with.
     * <p>
     * Half rather than most of it, so that the rate control has the same room to push in both
     * directions and so that there is always a whole latency's worth of space above the target --
     * which is what makes the blocking write below a backstop rather than the pacing.
     */
    private int targetSamples;

    /**
     * The line, or null when there is no sound device -- which is the silent-but-running case
     * every method below is written around.
     */
    private SourceDataLine line;

    private final int latencyMs;

    /**
     * The samples, as bytes. Kept between calls rather than allocated per frame: sixty a second
     * of a few kilobytes each is garbage the emulation thread does not need to be making.
     */
    private byte[] bytes = new byte[0];

    /**
     * Where the rate control's output grid has got to. Its own class because it has to be continuous
     * across frames -- see {@link Resampler}.
     */
    private final Resampler resampler = new Resampler();

    /**
     * What it resamples into, on its way to {@link #bytes}. Kept between calls for the reason those
     * are.
     */
    private short[] resampled = new short[0];

    /**
     * What every sample is multiplied by on its way out: the volume, squared, or zero when muted.
     */
    private double gain = 1.0;

    private boolean muted;

    private Volume volume = Volume.defaultVolume();

    /**
     * @param latencyMs how much sound to keep the card holding. Clamped to
     *                  {@link #MIN_LATENCY_MS}..{@link #MAX_LATENCY_MS}, since this arrives from a
     *                  file somebody may have typed a nought too many into.
     */
    public AudioOutput(final int latencyMs) {
        this.latencyMs = Math.clamp(latencyMs, MIN_LATENCY_MS, MAX_LATENCY_MS);
    }

    /**
     * Opens the line. Call once, from the emulation thread, before the first {@link #write}.
     */
    public void open() {
        if (line != null) {
            throw new IllegalStateException("already open");
        }

        var wanted = latencyMs * APU.SAMPLE_RATE / 1000;

        try {
            line = AudioSystem.getSourceDataLine(FORMAT);

            // Twice the latency, so that the wanted amount of sound sits in the middle of it. The
            // mixer is entitled to give a different size, so the target below is taken from what it
            // actually handed over rather than from what was asked for.
            line.open(FORMAT, 2 * wanted * BYTES_PER_SAMPLE);
            line.start();

            // Half of what actually arrived, but never more than was asked for: a mixer that
            // rounded the request up to something enormous would otherwise be held half full, and
            // half of enormous is a button heard a moment after it was pressed. And never zero,
            // because the error term below divides by this -- a NaN ratio produces no samples at
            // all rather than a wrong number of them.
            targetSamples =
                    Math.clamp(line.getBufferSize() / BYTES_PER_SAMPLE / 2L, 1, wanted);

            prime();

            logger.log(Level.INFO, "audio open at " + APU.SAMPLE_RATE
                    + "Hz, holding " + 1000 * targetSamples / APU.SAMPLE_RATE
                    + "ms in a buffer of "
                    + 1000 * (line.getBufferSize() / BYTES_PER_SAMPLE) / APU.SAMPLE_RATE + "ms");
        } catch (LineUnavailableException | IllegalArgumentException | SecurityException e) {
            // No device, no mixer, or no permission to reach one. None of those are a reason not
            // to run the machine.
            line = null;
            logger.log(Level.WARNING, "no audio device available, running silently", e);
        }
    }

    /**
     * Hands the card a run of samples.
     *
     * @param samples the samples, as {@link APU#drainSamples(short[])} left them.
     * @param count   how many of them are real.
     * @param paced   true when this is a frame of ordinary, real-time play, which is the only kind
     *                the card's fill level says anything about: those are rate controlled and
     *                written blocking. False for fast forward and rewind, which write what fits now
     *                and drop the rest.
     */
    public void write(final short[] samples, final int count, final boolean paced) {
        if (line == null || count <= 0) {
            return;
        }

        var written = paced ? rateControlled(samples, count) : pack(samples, count);

        var length = written * BYTES_PER_SAMPLE;

        if (!paced) {
            // Rounded down to a whole sample: half of one would put every sample after it in the
            // wrong byte order, for good.
            length = Math.min(length, line.available() & ~1);
        }

        if (length > 0) {
            line.write(bytes, 0, length);
        }
    }

    /**
     * How much to stretch or squeeze this frame by, to steer the card's queue back to
     * {@link #targetSamples}.
     * <p>
     * Proportional and nothing else, which is the right shape for the job: the queue integrates the
     * rate error all by itself, so a plain proportional term makes a first-order settle with no way
     * to overshoot. Deliberately slow -- at the stop it moves the queue by 220 samples a second, and
     * since the push shrinks with the error the approach is exponential with a time constant of
     * twelve seconds -- because a correction that arrived quickly would arrive as an audible bend in
     * the pitch, and there is nothing here that needs correcting quickly: this is chasing parts per
     * million. Which is also why {@link #prime()} exists rather than letting this fill the queue on
     * its own, since from empty that would take the better part of a minute.
     *
     * @return output samples per input sample, within half a percent of one.
     */
    private double rateRatio() {
        var queued = (line.getBufferSize() - line.available()) / BYTES_PER_SAMPLE;
        var error = Math.clamp((queued - targetSamples) / (double) targetSamples, -1.0, 1.0);

        // Minus: a card holding more than it should is a card being given samples faster than it
        // plays them, and the answer is to hand over fewer of them.
        return 1.0 - MAX_RATE_ADJUST * error;
    }

    /**
     * Steers the queue and packs the result, which is the ordinary path.
     *
     * @return how many samples landed in {@link #bytes}.
     */
    private int rateControlled(final short[] in, final int count) {
        var ratio = rateRatio();
        var capacity = Resampler.outputCapacity(count, ratio);

        if (resampled.length < capacity) {
            resampled = new short[capacity];
        }

        return pack(resampled, resampler.resample(in, count, ratio, resampled));
    }

    /**
     * Writes a run of samples into {@link #bytes} at the volume, little endian.
     * <p>
     * Also the whole of the unpaced path: fast forward and rewind hand over frames the card cannot
     * take at that rate, so there is nothing to steer and the extra is going to be dropped anyway.
     *
     * @return how many were written, which is {@code count}.
     */
    private int pack(final short[] in, final int count) {
        if (bytes.length < count * BYTES_PER_SAMPLE) {
            bytes = new byte[count * BYTES_PER_SAMPLE];
        }

        for (var i = 0; i < count; i++) {
            // No clamping, and it is an invariant rather than an oversight: gain is never above
            // one, so nothing here can leave the range it arrived in.
            var value = (short) Math.round(in[i] * gain);

            bytes[2 * i] = (byte) value;
            bytes[2 * i + 1] = (byte) (value >> 8);
        }

        return count;
    }

    /**
     * Fills the card with a target's worth of silence, and puts the resampler back at the start of
     * a frame.
     * <p>
     * Without this the first frame of a game is 735 samples dropped into an empty buffer, which the
     * card plays and then underruns on -- the click everybody knows as the sound of an emulator
     * starting up. The silence is the cushion the rate control is meant to be steering, so it wants
     * to be there before there is anything to steer.
     */
    private void prime() {
        if (bytes.length < targetSamples * BYTES_PER_SAMPLE) {
            bytes = new byte[targetSamples * BYTES_PER_SAMPLE];
        }

        // Whatever the last frame left in there, over again: a buffer that had held sound would
        // otherwise be written out as a fragment of it.
        Arrays.fill(bytes, 0, targetSamples * BYTES_PER_SAMPLE, (byte) 0);

        resampler.reset();

        line.write(bytes, 0, targetSamples * BYTES_PER_SAMPLE);
    }

    /**
     * Throws away whatever the card has not played yet, and lays down a fresh cushion of silence.
     * <p>
     * For the moments where what is queued has stopped being true: pausing, where it would
     * otherwise play on for a moment after the picture froze, and resuming, where it would be a
     * moment of the past. The fresh cushion is what stops the resume being a click -- the queue is
     * empty at that instant, and the first frame after it is not enough to keep the card fed.
     */
    public void flush() {
        if (line != null) {
            line.flush();
            prime();
        }
    }

    /**
     * Silences the output without stopping it. What is already queued goes on playing, which is at
     * most the latency.
     * <p>
     * Silence rather than nothing, so that the frames still arrive and the queue the rate control is
     * steering goes on being fed -- a mute that stopped writing would let the card run dry and come
     * back with a click.
     */
    public void setMuted(final boolean muted) {
        this.muted = muted;
        this.gain = muted ? 0.0 : volume.gain();
    }

    /**
     * How loud, out of the steps {@link Volume} offers. Takes effect on the next frame; a mute is
     * not lifted by it.
     */
    public void setVolume(final Volume volume) {
        this.volume = volume;
        this.gain = muted ? 0.0 : volume.gain();
    }

    /**
     * Gives the line back. Anything queued is dropped rather than played out: this is called when
     * a machine is being torn down, and finishing the last few frames of its audio is not worth
     * making the event dispatch thread wait for.
     */
    public void close() {
        if (line == null) {
            return;
        }

        line.stop();
        line.flush();
        line.close();
        line = null;

        logger.log(Level.INFO, "audio closed");
    }
}
