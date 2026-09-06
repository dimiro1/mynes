package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.IRQHandler;
import com.github.dimiro1.mynes.state.StateIO;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;

/**
 * APU implements the audio unit built into the CPU: the 2A03 of the NTSC NES, or the 2A07 of the
 * PAL one.
 * <p>
 * Five channels -- two pulses, a triangle, a noise generator and a delta modulation channel --
 * each of which is a divider driving a sequencer, gated by counters that a shared frame counter
 * clocks sixty times a second. Nothing here knows about sound cards or sample rates beyond the one
 * it decimates to: {@link #tick()} advances the chip by one CPU cycle and the front end pulls
 * finished samples out with {@link #drainSamples}.
 * <p>
 * {@link #tick()} is called once per CPU cycle, from {@link NES#tick()} and before the CPU's own
 * cycle, so that a $4015 read in a given cycle sees a flag the frame counter raised in that same
 * cycle. The chip's own clock is half of that -- the pulse and noise dividers count APU cycles,
 * which is every other call -- while the triangle and the DMC are clocked at the full CPU rate.
 * <p>
 * The two interrupts it can raise, the frame counter's and the DMC's, go out through
 * {@link IRQHandler} lambdas rather than through a reference to the bus, because they are levels
 * on a wire shared with the cartridge: see {@link BUS#setAPUFrameIRQ}.
 *
 * @see <a href="https://www.nesdev.org/wiki/APU">NESdev: APU</a>
 * @see <a href="https://www.nesdev.org/wiki/APU_Frame_Counter">NESdev: APU frame counter</a>
 */
public class APU {

    /**
     * How many samples a second {@link #drainSamples} hands out.
     * <p>
     * The core owns this rather than the front end: the decimator has to be built around some
     * rate, and having the UI pick it would mean the machine's tuning depended on a menu. 44.1kHz
     * is what every sound card takes without resampling.
     */
    public static final int SAMPLE_RATE = 44_100;

    /**
     * How many samples the ring between the chip and the front end holds, which at 44.1kHz is
     * about a fifth of a second.
     * <p>
     * Far more than the runner needs -- it drains every frame, so about 735 at a time -- because
     * the point of the slack is what happens when nothing drains at all: a test running the
     * machine headless, or a front end that has stopped asking. The oldest samples are dropped in
     * that case, so a ring that is never read costs a fixed amount of memory and nothing else.
     */
    private static final int SAMPLE_RING_SIZE = 8192;

    /**
     * What a sample of 1.0 comes out as. The mixer's two tables add up to just under one at their
     * loudest, so this is the full sixteen bit range with a little left over.
     */
    private static final double OUTPUT_SCALE = 32767.0;

    /**
     * The 32 lengths a write to $4003, $4007, $400B or $400F can load, indexed by the top five
     * bits of the value written.
     * <p>
     * The order looks arbitrary and is not: two tables interleaved. The odd indices are simply
     * 2, 4, 6 up to 30, half frames counted out one by one, with 254 dropped in at index 1 for a
     * note that is as good as held. The even ones are note lengths -- whole, half, quarter and so
     * on -- at two tempos, which is what a music driver actually wants to index.
     */
    static final int[] LENGTH_TABLE = {
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30,
    };

    /**
     * What the two pulse channels come to together, indexed by the sum of their levels.
     * <p>
     * The mixer is not a mixer at all, it is two resistor ladders into one node, and adding a
     * second voice to a loud one moves the output less than adding it to a quiet one. Which is
     * audible: two pulses at full volume are not twice as loud as one, and a chord does not
     * clip. The closed form NESdev gives for the curve is evaluated once here rather than per
     * sample.
     *
     * @see <a href="https://www.nesdev.org/wiki/APU_Mixer">NESdev: APU mixer</a>
     */
    private static final double[] PULSE_TABLE = pulseTable();

    /**
     * What the triangle, the noise channel and the DMC come to together, on the other ladder,
     * indexed by {@code 3 * triangle + 2 * noise + dmc} -- the weights are the relative sizes of
     * the resistors.
     */
    private static final double[] TND_TABLE = tndTable();

    private static double[] pulseTable() {
        var table = new double[31];

        for (var n = 1; n < table.length; n++) {
            table[n] = 95.52 / (8128.0 / n + 100);
        }

        return table;
    }

    private static double[] tndTable() {
        var table = new double[203];

        for (var n = 1; n < table.length; n++) {
            table[n] = 163.67 / (24329.0 / n + 100);
        }

        return table;
    }

    // ---------------------------------------------------------------- frame counter timing
    //
    // The sequence the frame counter steps through is in CPU cycles and differs between the two
    // machines, so it lives on Region rather than here. Everything that reads it goes through the
    // region field below.

    /**
     * How long a $4017 write made on a put cycle takes to reach the sequencer.
     * <p>
     * Three and four are the numbers everyone quotes, and the reason there are two of them is the
     * one thing nobody writes down: the write takes three CPU cycles to cross the chip, and
     * <em>the sequencer is only reset on a get cycle</em>. A write made on a put lands on a get
     * three cycles later and is done; one made on a get lands three cycles later on a put, and has
     * to wait a fourth for the phase to come round.
     * <p>
     * Which way round that pairing goes is the whole of AccuracyCoin's Frame Counter IRQ tests A
     * to D, which bracket each half separately and one cycle either side. Getting it backwards
     * still passes blargg's {@code 4-jitter}, because that measures only the difference.
     */
    private static final int WRITE_DELAY_FROM_PUT = 3;

    /**
     * The same for a write made on a get cycle, which has just missed the phase the reset lands on
     * and waits a cycle for the next.
     */
    private static final int WRITE_DELAY_FROM_GET = 4;

    // ---------------------------------------------------------------- $4015 bits

    private static final int STATUS_PULSE_1 = 0x01;
    private static final int STATUS_PULSE_2 = 0x02;
    private static final int STATUS_TRIANGLE = 0x04;
    private static final int STATUS_NOISE = 0x08;
    private static final int STATUS_DMC = 0x10;
    private static final int STATUS_FRAME_IRQ = 0x40;
    private static final int STATUS_DMC_IRQ = 0x80;

    // ---------------------------------------------------------------- $4017 bits

    private static final int FRAME_MODE_FIVE_STEP = 0x80;
    private static final int FRAME_IRQ_INHIBIT = 0x40;

    private final IRQHandler frameIRQHandler;
    private final IRQHandler dmcIRQHandler;

    /**
     * Which console this is: the frame counter's sequence, the noise periods and the DMC's rates
     * are all counted in CPU cycles, and the CPU cycle is a different length on each.
     */
    private final Region region;

    /**
     * How many CPU cycles go into one output sample. Not a whole number, which is the whole
     * difficulty: see {@link #sample()}. About 40.6 on NTSC and 37.7 on PAL -- a PAL machine makes
     * fewer cycles a second and the sound card still wants 44100 samples out of them.
     */
    private final double cyclesPerSample;

    private final Pulse pulse1 = new Pulse(true);
    private final Pulse pulse2 = new Pulse(false);
    private final Triangle triangle = new Triangle();
    private final Noise noise = new Noise();
    private final DMC dmc = new DMC();

    /**
     * What each voice has been doing, for a meter and a scope to draw, or null while nobody is
     * drawing either.
     * <p>
     * Null is the point of it: {@link #mix()} runs on every CPU cycle, and a machine nobody is
     * watching pays one null check there and nothing else. Not part of a save state -- it belongs
     * to whoever is watching rather than to the machine, the same as the channel mutes.
     */
    private @Nullable Levels levels;
    private final FrameCounter frameCounter = new FrameCounter();

    /**
     * How many CPU cycles the chip has been clocked for. Its parity is what separates an APU cycle
     * from the gap between two, which is the only thing the chip itself uses it for.
     */
    private long cycles;

    /**
     * A $4015 read is waiting to clear the frame counter's interrupt flag.
     * <p>
     * The read does not clear it. What the read does is ask for it to be cleared, and the flag goes
     * out on the next get cycle -- so a read that lands on a put is answered one cycle later, and a
     * read that lands on a get is answered two. AccuracyCoin measures both halves of that with a
     * {@code SLO $4015,X}, whose two reads of the register are consecutive cycles: read on a put and
     * the second read already sees it gone, read on a get and the second read still finds it there.
     * <p>
     * Nothing a game does can tell, which is why this was found in 2024 rather than in 1985.
     */
    private boolean frameIRQClearPending;

    // ---------------------------------------------------------------- the sample pipeline
    //
    // The chip's output changes at 1.79MHz and a sound card wants 44.1kHz, so forty-odd cycles
    // have to become one sample. They are averaged rather than picked from, which is a box filter
    // -- crude as filters go, but it is the difference between a triangle at an ultrasonic period
    // aliasing down into an audible whine and it averaging out to the DC level it really is.
    // Everything below belongs to the emulation thread; nothing here is synchronised.

    private final HighPass highPass90 = new HighPass(90.0);
    private final HighPass highPass440 = new HighPass(440.0);
    private final LowPass lowPass14k = new LowPass(14_000.0);

    private double sampleSum;
    private int sampleCycles;

    /**
     * How many CPU cycles are left before the next sample is due. Fractional, and carried across
     * samples rather than rounded, which is what keeps 44100 samples a second exact over an hour
     * instead of drifting by the third of a cycle that is thrown away each time.
     */
    private double cyclesToNextSample;

    private final short[] sampleRing = new short[SAMPLE_RING_SIZE];
    private int sampleRead;
    private int sampleWrite;
    private int sampleCount;

    /**
     * Which voices are being kept out of the mixer, one bit per {@link APUChannel#ordinal()}.
     * <p>
     * A bitmask rather than five booleans or a set, because {@link #mix()} reads it once per CPU
     * cycle -- 1.79 million times a second -- and what a machine nobody is debugging should pay for
     * it is one load and a bit test per channel. The shift folds away: every call site names a
     * constant.
     */
    private int mutedChannels;

    /**
     * @param frameIRQHandler the frame counter's end of the shared /IRQ line.
     * @param dmcIRQHandler   the DMC's end of the shared /IRQ line.
     */
    public APU(final IRQHandler frameIRQHandler, final IRQHandler dmcIRQHandler) {
        this(frameIRQHandler, dmcIRQHandler, Region.NTSC);
    }

    /**
     * @param frameIRQHandler the frame counter's end of the shared /IRQ line.
     * @param dmcIRQHandler   the DMC's end of the shared /IRQ line.
     * @param region          which console this is.
     */
    public APU(
            final IRQHandler frameIRQHandler,
            final IRQHandler dmcIRQHandler,
            final Region region) {
        this.frameIRQHandler = frameIRQHandler;
        this.dmcIRQHandler = dmcIRQHandler;
        this.region = region;
        this.cyclesPerSample = region.cpuClockHz() / SAMPLE_RATE;
        this.cyclesToNextSample = cyclesPerSample;
    }

    /**
     * Advances the chip by one CPU cycle.
     */
    public void tick() {
        // Before anything else, because this is the edge the cycle starts on rather than work done
        // during it: a read that happened on the previous cycle has to have cleared the flag by the
        // time this cycle's own read of $4015 sees it.
        if (frameIRQClearPending && CPUBus.isGetCycle(cycles)) {
            setFrameIRQFlag(false);
        }

        frameCounter.tick();

        // The pulse and noise dividers are clocked by the APU clock, which is the CPU clock
        // halved. The triangle and the DMC are clocked by the CPU clock itself, which is why the
        // triangle can reach frequencies the pulses cannot.
        triangle.tickTimer();
        dmc.tickTimer();

        if ((cycles & 1) == 0) {
            pulse1.tickTimer();
            pulse2.tickTimer();
            noise.tickTimer();
        }

        cycles++;

        sample();
    }

    /**
     * Advances the chip's parity and nothing else, for a CPU cycle the {@link Overclock} hack has
     * added to the frame.
     *
     * <h2>Why the sound stands still</h2>
     *
     * An overclocked frame is longer in CPU cycles but is still one frame of the game. If the APU
     * ran through the extra lines, everything it counts in cycles would run with them: the frame
     * counter's quarter and half frames -- so every envelope, sweep and length counter -- the five
     * timers, and the decimator that turns 1.79MHz into 44100 samples a second. The music would come
     * out slow, the pitches flat and the frame a thousand samples instead of 735, which a front end
     * pacing itself on a blocking write to the sound card would then run at the wrong speed. Mesen
     * holds its APU on the extra lines for exactly this reason, and so does this.
     *
     * <h2>Why the counter still moves</h2>
     *
     * {@link #cycles} is not only a count of work done; its <em>parity</em> is what
     * {@link CPUBus#isGetCycle} reads, and two chips have to agree about it. The MMU asks that
     * question of the CPU's counter when it starts a DMA cycle and this chip asks it of its own, and
     * the two answers only match because both counters advance once per {@link NES#tick()}. A
     * counter that stood still for 131 scanlines would come back inverted, and a sprite DMA would
     * take 513 cycles where the hardware takes 514.
     * <p>
     * A pending $4015 clear is honoured for the same reason: it is due on the next get cycle, and
     * that cycle may well be one of these.
     *
     * <h2>What the phase costs</h2>
     *
     * A block of held cycles is a gap in the middle of the chip's sequences rather than a stretch of
     * them, so the two one-cycle write delays -- {@code FrameCounter.writeDelay} and
     * {@code DMC.loadDelay} -- land after the block rather than inside it, and a block of odd length
     * moves which CPU cycle the pulse and noise dividers fall on by one. Neither is audible and
     * neither accumulates: the divider keeps its own period, so what shifts is the phase of a
     * waveform and not its frequency.
     */
    public void idle() {
        if (frameIRQClearPending && CPUBus.isGetCycle(cycles)) {
            setFrameIRQFlag(false);
        }

        cycles++;
    }

    /**
     * Takes this cycle's output into the running average, and finishes a sample when one falls
     * due.
     */
    private void sample() {
        sampleSum += mix();
        sampleCycles++;
        cyclesToNextSample--;

        if (cyclesToNextSample > 0) {
            return;
        }

        var averaged = sampleSum / sampleCycles;

        // The same window the sample above was averaged over, so a voice's trace and the mixed one
        // line up sample for sample.
        if (levels != null) {
            levels.emit(sampleCycles);
        }

        sampleSum = 0;
        sampleCycles = 0;
        cyclesToNextSample += cyclesPerSample;

        // Two high passes and a low pass, which between them are the console's own tone: the
        // 90Hz one is the coupling capacitor on the way out (and is what stops the DMC's level
        // sitting as an offset on everything else), the 440Hz one is the rest of the analogue
        // path, and the low pass is what a television could reproduce at all.
        var filtered = lowPass14k.filter(highPass440.filter(highPass90.filter(averaged)));

        emit(clamp(filtered * OUTPUT_SCALE));
    }

    /**
     * @return this cycle's output, 0 to about 1, on the two nonlinear ladders the five channels
     * really share.
     */
    private double mix() {
        var one = pulse1.output();
        var two = pulse2.output();
        var tri = triangle.output();
        var noi = noise.output();
        var delta = dmc.output;

        // Before the mute rather than after, so a voice somebody has switched off still moves its
        // meter and its trace. Null unless one of the two is being drawn, which is the whole of
        // what this costs.
        var watching = levels;

        if (watching != null) {
            watching.take(one, two, tri, noi, delta);
        }

        var pulses = audible(APUChannel.PULSE_1, one) + audible(APUChannel.PULSE_2, two);
        var rest = 3 * audible(APUChannel.TRIANGLE, tri)
                + 2 * audible(APUChannel.NOISE, noi)
                + audible(APUChannel.DMC, delta);

        return PULSE_TABLE[pulses] + TND_TABLE[rest];
    }



    /**
     * A channel's level, or zero if somebody has switched that voice off.
     * <p>
     * Zeroed here, at the mixer, rather than anywhere the channel could notice. Everything upstream
     * runs exactly as it did -- the timer, the sequencer, the envelope, the length counter -- so
     * $4015 answers the same, the interrupts still arrive, and the DMC still stops the CPU to fetch
     * its bytes. And zeroing the <em>level</em> rather than subtracting the channel's contribution
     * is what keeps the ladders honest: a silent voice on a resistor ladder is a voice putting out
     * nothing, which is what the other four are then mixed against.
     */
    private int audible(final APUChannel channel, final int level) {
        return (mutedChannels & (1 << channel.ordinal())) == 0 ? level : 0;
    }

    /**
     * Keeps one voice out of the mixer, or lets it back in.
     * <p>
     * A debug tool rather than a feature of the console -- see {@link APUChannel} for what it does
     * not touch. Off for every channel until somebody asks, and deliberately not part of a save
     * state: it belongs to whoever is listening.
     */
    public void setChannelMuted(final APUChannel channel, final boolean muted) {
        if (muted) {
            mutedChannels |= 1 << channel.ordinal();
        } else {
            mutedChannels &= ~(1 << channel.ordinal());
        }
    }

    public boolean isChannelMuted(final APUChannel channel) {
        return (mutedChannels & (1 << channel.ordinal())) != 0;
    }

    private static short clamp(final double sample) {
        if (sample >= OUTPUT_SCALE) {
            return Short.MAX_VALUE;
        }

        if (sample <= -OUTPUT_SCALE) {
            return Short.MIN_VALUE;
        }

        return (short) sample;
    }

    /**
     * Puts a finished sample in the ring, dropping the oldest one if nothing has drained it.
     * <p>
     * Dropping the oldest rather than refusing the newest is the right way round for sound: what
     * a front end that has fallen behind wants when it comes back is the audio from now, not a
     * fifth of a second of history to play before it.
     */
    private void emit(final short sample) {
        sampleRing[sampleWrite] = sample;
        sampleWrite = (sampleWrite + 1) % SAMPLE_RING_SIZE;

        if (sampleCount == SAMPLE_RING_SIZE) {
            sampleRead = sampleWrite;
        } else {
            sampleCount++;
        }
    }

    /**
     * Takes the finished samples out of the chip.
     * <p>
     * Signed sixteen bit, one channel, at {@link #SAMPLE_RATE}. Called from whichever thread
     * clocks the machine and from no other: the ring is not synchronised, and it does not need to
     * be, because the emulation thread is the only thread that ever touches the NES.
     *
     * @param out where to put them.
     * @return how many were written, which is the smaller of what was waiting and what fits.
     */
    public int drainSamples(final short[] out) {
        var drained = Math.min(out.length, sampleCount);

        for (var i = 0; i < drained; i++) {
            out[i] = sampleRing[sampleRead];
            sampleRead = (sampleRead + 1) % SAMPLE_RING_SIZE;
        }

        sampleCount -= drained;

        return drained;
    }

    /**
     * How many finished samples are waiting to be drained.
     */
    public int availableSamples() {
        return sampleCount;
    }

    /**
     * How many CPU cycles the chip has been driven or held for since power on.
     * <p>
     * The APU's own clock, in the same sense that {@link PPU#getFrame()} is the PPU's: it is what
     * says the chip is being driven at the rate it should be, including through the cycles an OAM
     * DMA transfer holds the CPU off the bus. "Or held", because an {@link #idle()} cycle counts
     * here too -- this is the parity two chips agree on before it is a measure of work done, and it
     * is what keeps {@code run.apuCycles} equal to {@code run.cpuCycles} however long a frame is.
     */
    public long getCycles() {
        return cycles;
    }

    /**
     * The console's reset button, as the audio unit sees it.
     * <p>
     * Everything is silenced the way a $4015 write of zero silences it, the frame counter starts
     * its sequence again, and the triangle's sequencer goes back to the start of its ramp. What
     * $4017 was last set to survives: the button does not reach that latch. Neither does the DMC's
     * output level, quite: all but its bottom bit is cleared, which is the documented behaviour
     * and is what stops a sample that was playing loudly from leaving a step behind it.
     */
    public void reset() {
        writeStatus(0);
        setFrameIRQFlag(false);
        frameCounter.reset();
        triangle.resetSequencer();
        dmc.output &= 1;
    }

    /**
     * Reads or writes the five channels, the frame counter, and the pipeline that turns their
     * output into samples.
     * <p>
     * What is not here is the ring buffer, along with its three indices. That is the queue between
     * this chip and the sound card rather than anything the chip remembers, and it is empty at the
     * moment a state is written anyway -- both drivers drain it at the end of every frame. What
     * <em>is</em> here is everything upstream of it: the box filter's running sum, the fractional
     * count to the next sample, and the three filters' accumulated state, because those decide what
     * the next few hundred samples sound like and dropping them would put a click in.
     * <p>
     * The nested classes are all private and stay that way. They are nestmates, so this can call
     * straight into them without widening anything.
     *
     * @see com.github.dimiro1.mynes.state.SaveState
     */
    public void serialize(final StateIO io) {
        cycles = io.u64(cycles);
        frameIRQClearPending = io.bool(frameIRQClearPending);

        pulse1.serialize(io);
        pulse2.serialize(io);
        triangle.serialize(io);
        noise.serialize(io);
        dmc.serialize(io);
        frameCounter.serialize(io);

        sampleSum = io.f64(sampleSum);
        sampleCycles = io.u16(sampleCycles);
        cyclesToNextSample = io.f64(cyclesToNextSample);

        highPass90.serialize(io);
        highPass440.serialize(io);
        lowPass14k.serialize(io);
    }

    /**
     * Writes one of the registers in $4000-$4017.
     * <p>
     * The whole window arrives here except the four addresses that are not the APU's: $4014 is the
     * PPU's DMA trigger and $4016 the controller strobe, both of which {@link MMU} keeps, while
     * $4015 and $4017 are the APU's own and are handled below.
     *
     * @param address an address in $4000-$4017.
     * @param data    the byte written.
     */
    public void write(final int address, final int data) {
        switch (address) {
            case 0x4000, 0x4001, 0x4002, 0x4003 -> pulse1.write(address & 3, data);
            case 0x4004, 0x4005, 0x4006, 0x4007 -> pulse2.write(address & 3, data);
            case 0x4008, 0x4009, 0x400A, 0x400B -> triangle.write(address & 3, data);
            case 0x400C, 0x400D, 0x400E, 0x400F -> noise.write(address & 3, data);
            case 0x4010, 0x4011, 0x4012, 0x4013 -> dmc.write(address & 3, data);
            case 0x4015 -> writeStatus(data);
            case 0x4017 -> frameCounter.write(data);
            default -> { /* $4014 and $4016 are not the APU's, and neither is $4018-$401F */ }
        }
    }

    /**
     * Reads $4015, which is the only register the APU answers a read with.
     * <p>
     * One bit per channel saying whether its length counter has anything left -- the DMC's says
     * whether it has bytes left to play -- and the two interrupt flags. Reading it acknowledges
     * the frame counter's interrupt and not the DMC's, which is cleared by writing $4015 instead;
     * the asymmetry is the hardware's.
     *
     * @return the status byte.
     */
    public int readStatus() {
        // The whole of what a read does besides handing the byte over, and the reason peekStatus
        // exists: a gauge that read $4015 would be the thing that acknowledged the interrupt, and
        // a game waiting on it would never see it.
        frameIRQClearPending = true;

        return peekStatus();
    }

    /**
     * The same byte without acknowledging anything: which channels have something left to play and
     * which of the two interrupts are up.
     */
    public int peekStatus() {
        var status = 0;

        if (pulse1.lengthCounter.value > 0) {
            status |= STATUS_PULSE_1;
        }
        if (pulse2.lengthCounter.value > 0) {
            status |= STATUS_PULSE_2;
        }
        if (triangle.lengthCounter.value > 0) {
            status |= STATUS_TRIANGLE;
        }
        if (noise.lengthCounter.value > 0) {
            status |= STATUS_NOISE;
        }
        if (dmc.bytesRemaining > 0) {
            status |= STATUS_DMC;
        }
        if (frameCounter.irqFlag) {
            status |= STATUS_FRAME_IRQ;
        }
        if (dmc.irqFlag) {
            status |= STATUS_DMC_IRQ;
        }

        return status;
    }

    /**
     * One voice, as something looking at the chip sees it.
     * <p>
     * Here rather than in the front end because it is the machine's answer rather than a window's
     * question: the five voices are private classes with no getters, and a REPL {@code voices}
     * command wants exactly this. One record for all five rather than five, because what anybody
     * does with them is put them in a table -- so the fields that belong to one channel say so, and
     * are -1 or false everywhere else.
     *
     * @param channel   which voice this is.
     * @param playing   whether it has anything left to play, which is its bit of $4015: a length
     *                  counter above zero, or bytes left for the DMC. Not the same as audible --
     *                  a pulse between notes is still playing.
     * @param length    what is left in the length counter, 0 to 254. Always 0 for the DMC, which
     *                  has none.
     * @param period    the divider period as the registers hold it, which is what a game writes and
     *                  so what a watchpoint would catch.
     * @param hertz     what that period comes out as: the note for the two pulses and the triangle,
     *                  the shift rate for the noise, and the sample rate for the DMC. What the
     *                  period would sound like rather than what is being heard -- a channel between
     *                  notes still says what it is tuned to, which is often the answer to why it
     *                  stopped. Zero only where the period makes no note at all: one the sweep unit
     *                  mutes, or one above hearing.
     * @param level     what the channel is putting into the mixer this instant, 0 to 15 -- or 0 to
     *                  127 for the DMC, whose level is its whole output.
     * @param volume    what the envelope is asking for, 0 to 15, before the sequencer gates it. -1
     *                  for the triangle, which has no volume control at all, and for the DMC.
     * @param constant  whether the volume is the constant one rather than the decaying envelope.
     *                  False where there is no envelope.
     * @param loop      the length counter's halt bit, which the two pulses and the noise share with
     *                  the envelope's loop and the triangle calls its control bit. The DMC's is its
     *                  own loop flag, which restarts the sample rather than holding a counter.
     * @param duty      which of the four pulse duty cycles, 0 to 3. -1 on the other three.
     * @param shortMode whether the noise is tapping its register six bits along rather than one,
     *                  which is the difference between a pitch and a hiss. False elsewhere.
     * @param linear    what is left in the triangle's linear counter, which gates it as well as the
     *                  length counter does and is the usual answer to why one has gone quiet with a
     *                  length still loaded. -1 on the other four, which have none.
     * @param address   where the DMC is reading from now, or -1 elsewhere.
     * @param bytesLeft how much of the sample is left to play, or -1 elsewhere.
     */
    public record VoiceState(
            APUChannel channel,
            boolean playing,
            int length,
            int period,
            double hertz,
            int level,
            int volume,
            boolean constant,
            boolean loop,
            int duty,
            boolean shortMode,
            int linear,
            int address,
            int bytesLeft) {

        /**
         * How many shifts the noise's register takes to come back round in short mode.
         * <p>
         * In its usual mode the sequence is 32767 steps and what comes out is hiss; tap the register
         * six bits along instead of one and it is 93, which repeats fast enough to be heard as a
         * metallic pitch. So the noise is the one voice here whose {@link #hertz} is a rate and
         * whose pitch is that rate divided by something.
         *
         * @see <a href="https://www.nesdev.org/wiki/APU_Noise">NESdev: APU noise</a>
         */
        private static final int SHORT_SEQUENCE = 93;

        /**
         * The frequency anybody would hear as a note, or 0 where there is none to hear.
         * <p>
         * Not the same question as {@link #hertz}, and the difference is the whole reason this
         * exists. For the two pulses and the triangle they are the same number. For the noise
         * {@code hertz} is how fast the shift register is being clocked, and the pitch is that
         * over the length of the sequence it is running round -- so a noise channel in short mode
         * has a note, which is a real thing games use for metallic effects and occasionally for
         * bass, and one in its usual mode has none at all.
         * <p>
         * The DMC never has one: its rate is a sample rate, and what pitch a sample comes out at is
         * a fact about the bytes in it rather than about the chip playing them.
         */
        public double pitch() {
            return switch (channel) {
                case PULSE_1, PULSE_2, TRIANGLE -> hertz;
                case NOISE -> shortMode ? hertz / SHORT_SEQUENCE : 0;
                case DMC -> 0;
            };
        }
    }

    /**
     * What one of the five voices is doing.
     * <p>
     * Reads and works nothing out that the chip does not already hold, so it is safe to ask as
     * often as anybody likes -- and unlike {@link #readStatus()} it acknowledges nothing.
     */
    public VoiceState voice(final APUChannel channel) {
        return switch (channel) {
            case PULSE_1 -> pulse1.state(APUChannel.PULSE_1, region);
            case PULSE_2 -> pulse2.state(APUChannel.PULSE_2, region);
            case TRIANGLE -> triangle.state(region);
            case NOISE -> noise.state(region);
            case DMC -> dmc.state(region);
        };
    }

    /**
     * Starts or stops keeping the loudest each voice has been since the last time anybody asked.
     * <p>
     * Off until something asks, which is the rule the debugger's bus hooks keep: a machine nobody
     * is watching pays one null check on the mixer's hottest line and nothing else. Only ever
     * called on the thread that clocks the chip, which is what keeps that field plain.
     */
    public void setPeakTracking(final boolean tracking) {
        levels = tracking ? new Levels() : null;
    }

    /**
     * The loudest each voice has been since {@link #clearPeaks()} was last called.
     * <p>
     * Taken before the mute is applied, so a voice somebody has switched off still shows a meter:
     * "this is playing and you cannot hear it" is a different answer from "this is not playing",
     * and telling them apart is most of what the mute is for.
     * <p>
     * <b>Reading them does not clear them</b>, which is the same rule the rest of this class keeps
     * for {@code peek}. It has to be: the debugger's stop snapshot reads a machine through exactly
     * the same record as the meters do, and a read that reset would mean whichever of the two
     * looked first quietly emptied the other.
     *
     * @param into filled with one level per {@link APUChannel}, zeroed if nothing is being tracked.
     */
    public void peaks(final int[] into) {
        var watching = levels;

        for (var i = 0; i < into.length; i++) {
            into[i] = watching == null ? 0 : watching.peaks[i];
        }
    }

    /**
     * Starts the peaks counting again, which is how a meter says where its window begins. Whoever
     * is drawing one owns this; anybody else reading them is a bystander.
     */
    public void clearPeaks() {
        var watching = levels;

        if (watching != null) {
            Arrays.fill(watching.peaks, 0);
        }
    }

    /**
     * The last {@code count} samples of one voice on its own, oldest first.
     * <p>
     * What that voice put into the mixer rather than what came out of it: the level its sequencer
     * and envelope produced, before the two ladders make five levels into one and before the mute
     * takes any of them away. Which is the point of drawing them separately -- a square wave, a
     * triangle, a hiss and a sampled drum are recognisable at a glance where their sum is not.
     * <p>
     * Zeroes while nothing is being tracked, and zeroes for however much of {@code count} has not
     * happened yet.
     *
     * @param channel which voice.
     * @param into    where they go, oldest first, starting at zero.
     * @param count   how many, which is normally however many samples were just drained.
     */
    public void trace(final APUChannel channel, final short[] into, final int count) {
        var watching = levels;

        if (watching == null) {
            Arrays.fill(into, 0, count, (short) 0);
            return;
        }

        watching.trace(channel.ordinal(), into, count);
    }

    /**
     * What each voice has been doing, kept only while somebody is watching.
     * <p>
     * One object rather than three fields because the mixer's hottest line tests it once: the
     * peaks, the running sums and the traces are all wanted at the same times and by the same
     * caller, and three null checks there would be three times the price of the one.
     */
    private static final class Levels {
        /**
         * How many samples of each voice are kept. A frame is 735 of them on NTSC and 882 on PAL,
         * so this holds the last whole frame of either with room to spare -- which is what lets a
         * scope ask for exactly the frame that has just been drained and get it.
         */
        private static final int TRACE = 1024;

        private final int[] peaks = new int[APUChannel.values().length];
        private final int[] sums = new int[APUChannel.values().length];
        private final short[][] traces = new short[APUChannel.values().length][TRACE];

        private int write;

        /**
         * One CPU cycle's worth of every voice.
         */
        private void take(
                final int one, final int two, final int tri, final int noi, final int delta) {

            keep(APUChannel.PULSE_1.ordinal(), one);
            keep(APUChannel.PULSE_2.ordinal(), two);
            keep(APUChannel.TRIANGLE.ordinal(), tri);
            keep(APUChannel.NOISE.ordinal(), noi);
            keep(APUChannel.DMC.ordinal(), delta);
        }

        private void keep(final int voice, final int level) {
            sums[voice] += level;

            if (level > peaks[voice]) {
                peaks[voice] = level;
            }
        }

        /**
         * One output sample's worth: each voice averaged over the cycles that went into it, which
         * is the same average the mixer took, so the traces and the mixed one line up.
         */
        private void emit(final int cycles) {
            for (var voice = 0; voice < sums.length; voice++) {
                traces[voice][write] = (short) (sums[voice] / cycles);
                sums[voice] = 0;
            }

            write = (write + 1) % TRACE;
        }

        private void trace(final int voice, final short[] into, final int count) {
            var kept = traces[voice];
            var wanted = Math.min(count, TRACE);
            var from = Math.floorMod(write - wanted, TRACE);

            for (var i = 0; i < wanted; i++) {
                into[i] = kept[(from + i) % TRACE];
            }
        }
    }

    /**
     * Whether the frame counter is running the five step sequence, which is $4017 bit 7.
     * <p>
     * Worth a gauge of its own because the two sequences are not the same thing at two speeds: four
     * step clocks the envelopes four times and the lengths twice in 29830 cycles and raises an
     * interrupt at the end; five step does it five and two in 37282 and never interrupts. A game
     * whose music is running slow has usually written $80 here without meaning to.
     */
    public boolean isFiveStepFrameCounter() {
        return frameCounter.fiveStep;
    }

    /**
     * Whether $4017 bit 6 is holding the frame counter's interrupt off.
     */
    public boolean isFrameIRQInhibited() {
        return frameCounter.irqInhibit;
    }

    /**
     * Writes $4015: which channels are enabled.
     * <p>
     * Disabling a channel does not stop it politely, it zeroes its length counter, and the counter
     * stays at zero -- a length register write is ignored -- until the channel is enabled again.
     * The DMC's bit is the odd one out: it has no length counter, so what it switches is the
     * sample, and switching it back on starts the sample over rather than resuming it.
     */
    private void writeStatus(final int data) {
        setDMCIRQFlag(false);

        pulse1.setEnabled((data & STATUS_PULSE_1) != 0);
        pulse2.setEnabled((data & STATUS_PULSE_2) != 0);
        triangle.setEnabled((data & STATUS_TRIANGLE) != 0);
        noise.setEnabled((data & STATUS_NOISE) != 0);
        dmc.setEnabled((data & STATUS_DMC) != 0);
    }

    /**
     * Raises or clears the frame counter's interrupt flag, and drives its end of the /IRQ line to
     * match. The flag and the line are the same thing here: nothing else gates it once the inhibit
     * bit has had its say.
     */
    private void setFrameIRQFlag(final boolean raised) {
        setFrameIRQFlag(raised, raised);
    }

    /**
     * The same, for the two cycles a sequence ends on where the flag and the line disagree.
     *
     * @param raised what bit 6 of $4015 reads back as.
     * @param line   whether this end of the shared /IRQ line is pulled low.
     */
    private void setFrameIRQFlag(final boolean raised, final boolean line) {
        frameCounter.irqFlag = raised;
        frameIRQHandler.setIRQLine(line);

        // Whatever a $4015 read was waiting to acknowledge, it is not this. The sequencer raises
        // the flag on three consecutive cycles at the end of a sequence, so a read landing in the
        // middle of them is answered by a fresh interrupt before the clear it asked for comes due
        // -- and that interrupt is a new one, which nobody has acknowledged yet. blargg's
        // 6-irq_flag_timing measures exactly this: the flag has to read back set on all three.
        frameIRQClearPending = false;
    }

    /**
     * Raises or clears the DMC's interrupt flag, and drives its end of the /IRQ line to match.
     */
    private void setDMCIRQFlag(final boolean raised) {
        dmc.irqFlag = raised;
        dmcIRQHandler.setIRQLine(raised);
    }

    // ---------------------------------------------------------------- the DMC's DMA
    //
    // The DMC reads its samples straight out of the cartridge, over the same bus the CPU uses, and
    // the CPU is simply held off it for the four cycles that takes. That stall already exists for
    // OAM DMA -- CPU.tick() spins while CPUBus.tickDMA says to -- so the fetch rides the same
    // seam, and these three methods are all the MMU needs to drive it. Doing the read from there
    // rather than from here is what keeps the APU free of any idea of what memory is.

    /**
     * Whether the DMC is waiting for a byte of its sample.
     *
     * @return true when the sample buffer is empty and there is a sample still to play.
     */
    public boolean isDMCFetchPending() {
        return dmc.isFetchPending();
    }

    /**
     * Whether there is a sample left to fetch bytes of. Asked either side of a $4015 write to tell
     * a write that stopped playback from one that changed nothing.
     */
    public boolean isDMCSampleActive() {
        return dmc.bytesRemaining > 0;
    }

    /**
     * Whether a sample stopped now would be stopped inside the window that schedules an aborted
     * DMA: the DMC is playing, and its output unit is one or two cycles from emptying the buffer
     * and asking for the next byte. See MMU.scheduleAbortedDMA.
     */
    public boolean isDMCReloadImminent() {
        return dmc.bytesRemaining > 0 && dmc.sampleBufferFilled && dmc.loadDelay == 0
                && dmc.isReloadImminent();
    }

    /**
     * Which half of the divided clock the cycle the CPU is part way through is, for code reached
     * from a bus access rather than from {@link #tick()}. One behind {@code cycles}, because the
     * chip is clocked before the processor is. See {@link CPUBus#isGetCycle}.
     */
    boolean isGetCycleNow() {
        return CPUBus.isGetCycle(cycles - 1);
    }

    /**
     * Where the next byte of the sample is.
     *
     * @return an address in $8000-$FFFF.
     */
    public int dmcFetchAddress() {
        return dmc.currentAddress;
    }

    /**
     * Hands the DMC the byte it was waiting for, and moves it on to the next: the address counter
     * advances, wrapping $FFFF round to $8000 rather than off the end of the cartridge, and the
     * sample either loops or raises the interrupt when it runs out.
     *
     * @param data the byte read from {@link #dmcFetchAddress()}.
     * @return true if this byte was the sample's last and it ran out inside the window that
     *         schedules an aborted DMA -- an implicit stop. See MMU.scheduleAbortedDMA.
     */
    public boolean finishDMCFetch(final int data) {
        return dmc.finishFetch(data);
    }

    // ---------------------------------------------------------------- for the tests
    //
    // $4015 is the only register the chip answers, it says no more than whether each counter is
    // above zero, and reading it clears the frame interrupt on the way out. That is too coarse and
    // too destructive to check the counters with, so the counters themselves are visible to the
    // tests in the same package.

    /**
     * The frame counter's interrupt flag, which is bit 6 of $4015, without a $4015 read's side
     * effect of clearing it.
     */
    boolean isFrameIRQRaised() {
        return frameCounter.irqFlag;
    }

    /**
     * How many CPU cycles into its sequence the frame counter is.
     */
    int frameCounterCycle() {
        return frameCounter.cycle;
    }

    int pulse1Length() {
        return pulse1.lengthCounter.value;
    }

    int pulse2Length() {
        return pulse2.lengthCounter.value;
    }

    int triangleLength() {
        return triangle.lengthCounter.value;
    }

    int noiseLength() {
        return noise.lengthCounter.value;
    }

    /**
     * What the triangle's second gate has left, in quarter frames.
     */
    int triangleLinearCounter() {
        return triangle.linearCounter;
    }

    /**
     * The volume pulse 1's envelope is putting out, 0 to 15.
     */
    int pulse1Volume() {
        return pulse1.envelope.volume();
    }

    /**
     * The volume the noise channel's envelope is putting out, 0 to 15.
     */
    int noiseVolume() {
        return noise.envelope.volume();
    }

    /**
     * The eleven bit divider period pulse 1 is running at, which the sweep unit rewrites as it
     * goes.
     */
    int pulse1Period() {
        return pulse1.period;
    }

    int pulse2Period() {
        return pulse2.period;
    }

    int pulse1Output() {
        return pulse1.output();
    }

    // Symmetric debug accessor mirroring pulse1Output(); kept for parity even when unused.
    @SuppressWarnings("unused")
    int pulse2Output() {
        return pulse2.output();
    }

    int triangleOutput() {
        return triangle.output();
    }

    int noiseOutput() {
        return noise.output();
    }

    /**
     * The noise channel's shift register, whose sequence length is the whole character of the
     * channel and cannot be measured from the outside in any reasonable number of cycles.
     */
    int noiseShiftRegister() {
        return noise.shiftRegister;
    }

    /**
     * The DMC's seven bit output level, which is the channel: nothing gates it and nothing scales
     * it.
     */
    int dmcOutput() {
        return dmc.output;
    }

    /**
     * How much of the sample the DMC has left to read, which is what bit 4 of $4015 answers for.
     */
    int dmcBytesRemaining() {
        return dmc.bytesRemaining;
    }

    /**
     * The DMC's interrupt flag, which is bit 7 of $4015, without a $4015 read's side effect of
     * clearing the frame counter's.
     */
    boolean isDMCIRQRaised() {
        return dmc.irqFlag;
    }

    // =================================================================== the frame counter

    /**
     * The divider that clocks everything in the chip that is not a sound generator.
     * <p>
     * It is not really a frame counter: it runs from the CPU clock and knows nothing about the
     * picture, which is why its 240Hz is not exactly four times the 60.0988Hz the PPU draws at. It
     * emits two signals. A <em>quarter frame</em> clocks the envelopes and the triangle's linear
     * counter, and a <em>half frame</em> clocks the length counters and the sweep units -- and
     * every half frame is a quarter frame as well.
     * <p>
     * In four step mode it also raises an interrupt at the end of every sequence unless the
     * inhibit bit is set. In five step mode it never does, which is why a game that wants nothing
     * to do with the interrupt writes $80 here rather than switching it off.
     */
    private final class FrameCounter {
        /**
         * How many CPU cycles into the current sequence, counting from one.
         */
        private int cycle;

        /**
         * True while the five step sequence is selected, which is bit 7 of $4017.
         */
        private boolean fiveStep;

        /**
         * True while the interrupt is inhibited, which is bit 6 of $4017. Setting it does not only
         * stop the next interrupt, it clears the flag that is already there.
         */
        private boolean irqInhibit;

        /**
         * The interrupt flag itself, which is bit 6 of $4015. Driven through
         * {@link APU#setFrameIRQFlag} so that the line follows it.
         */
        private boolean irqFlag;

        /**
         * How many CPU cycles are left before a $4017 write reaches the sequencer, or zero when
         * there is no write in flight.
         */
        private int writeDelay;

        /**
         * The value of that write, kept until the delay runs out.
         */
        private int pendingValue;

        /**
         * Where the sequence has got to, and a $4017 write that has not landed yet. The interrupt
         * flag is a latch and so comes out of the file; the /IRQ line it drives is the BUS's.
         */
        private void serialize(final StateIO io) {
            cycle = io.u16(cycle);
            fiveStep = io.bool(fiveStep);
            irqInhibit = io.bool(irqInhibit);
            irqFlag = io.bool(irqFlag);
            writeDelay = io.u8(writeDelay);
            pendingValue = io.u8(pendingValue);
        }

        private void tick() {
            // The cycle a $4017 write lands on is cycle zero of the new sequence, not the cycle
            // before it: the sequencer is reset rather than clocked. One cycle either way here
            // moves every step and both interrupt windows, which is what blargg's 4, 5 and 6
            // measure to the cycle.
            if (writeDelay > 0 && --writeDelay == 0) {
                applyPendingWrite();
            } else {
                cycle++;
            }

            if (fiveStep) {
                tickFiveStep();
            } else {
                tickFourStep();
            }
        }

        // The two below would read better as a switch, and were one until the step cycles moved to
        // Region: a case label has to be a compile-time constant, and which sequence this chip
        // steps through is now a question about the machine it is in. The order is the order the
        // cases were in, and the three interrupt cycles are consecutive rather than alternative.

        private void tickFourStep() {
            if (cycle == region.step1Cycle() || cycle == region.step3Cycle()) {
                clockQuarterFrame();
            } else if (cycle == region.step2Cycle()) {
                clockQuarterFrame();
                clockHalfFrame();
            } else if (cycle == region.irqFirstCycle()) {
                raiseIRQFlag();
            } else if (cycle == region.step4Cycle()) {
                clockQuarterFrame();
                clockHalfFrame();
                raiseIRQ();
            } else if (cycle == region.fourStepPeriod()) {
                settleIRQ();
                cycle = 0;
            }
        }

        private void tickFiveStep() {
            if (cycle == region.step1Cycle() || cycle == region.step3Cycle()) {
                clockQuarterFrame();
            } else if (cycle == region.step2Cycle() || cycle == region.step5Cycle()) {
                clockQuarterFrame();
                clockHalfFrame();
            } else if (cycle == region.fiveStepPeriod()) {
                cycle = 0;
            }

            // The fourth step of this sequence really does nothing.
        }

        // A four step sequence ends on three consecutive cycles, and all three do something
        // different. The flag and the /IRQ line are not one signal here, and the inhibit bit
        // reaches them at different moments -- which is why a program that has asked for no
        // interrupts can still read bit 6 of $4015 back set, for exactly two cycles, and still
        // never be interrupted. AccuracyCoin brackets every edge of that a cycle either side.

        /**
         * The first: the flag goes up, and nothing else. The level detector is left where it was,
         * so an interrupt does not begin here even with the inhibit bit clear -- it begins on the
         * cycle below, which is what makes AccuracyCoin's tests N and O land an instruction apart
         * from where a machine that pulled the line here would put them.
         * <p>
         * "Where it was" is spelt out rather than remembered: outside these three cycles the line
         * is the flag gated by the inhibit bit and nothing else drives it, so that expression is
         * the level it is already sitting at.
         */
        private void raiseIRQFlag() {
            setFrameIRQFlag(true, irqFlag && !irqInhibit);
        }

        /**
         * The second: the flag stays up whatever the inhibit bit says, and the line follows it
         * unless the bit is set. This is the cycle an interrupt actually starts on.
         */
        private void raiseIRQ() {
            setFrameIRQFlag(true, !irqInhibit);
        }

        /**
         * The third and last, where the inhibit bit finally reaches the flag itself. Nothing else
         * closes the two-cycle window above -- an inhibited interrupt is never acknowledged, it
         * simply stops being reported.
         */
        private void settleIRQ() {
            setFrameIRQFlag(!irqInhibit);
        }

        /**
         * Takes a $4017 write, which does not land until three or four CPU cycles later.
         * <p>
         * The inhibit bit is the exception and applies at once: a program that has just been
         * interrupted writes $40 here to acknowledge it, and having that wait would leave the line
         * still low when the handler returns.
         */
        private void write(final int data) {
            irqInhibit = (data & FRAME_IRQ_INHIBIT) != 0;

            if (irqInhibit) {
                setFrameIRQFlag(false);
            }

            pendingValue = data;

            // Reached from the CPU's half of the cycle, by which time the chip has already been
            // clocked for it -- so the cycle doing the writing is the one before this count.
            writeDelay = CPUBus.isGetCycle(cycles - 1)
                    ? WRITE_DELAY_FROM_GET
                    : WRITE_DELAY_FROM_PUT;
        }

        /**
         * The delayed half of a $4017 write: the sequence restarts, and entering five step mode
         * clocks a quarter and a half frame on the way in. That immediate clock is how a game
         * gets a length counter tick at a moment of its own choosing.
         */
        private void applyPendingWrite() {
            fiveStep = (pendingValue & FRAME_MODE_FIVE_STEP) != 0;
            cycle = 0;

            if (fiveStep) {
                clockQuarterFrame();
                clockHalfFrame();
            }
        }

        private void reset() {
            cycle = 0;
            writeDelay = 0;
        }
    }

    /**
     * Clocks everything that runs at a quarter frame: the envelopes and the triangle's linear
     * counter.
     */
    private void clockQuarterFrame() {
        pulse1.envelope.clock();
        pulse2.envelope.clock();
        noise.envelope.clock();
        triangle.clockLinearCounter();
    }

    /**
     * Clocks everything that runs at a half frame: the length counters and the sweep units.
     */
    private void clockHalfFrame() {
        pulse1.lengthCounter.clock();
        pulse2.lengthCounter.clock();
        triangle.lengthCounter.clock();
        noise.lengthCounter.clock();

        pulse1.clockSweep();
        pulse2.clockSweep();
    }

    // =================================================================== shared units

    /**
     * The counter that decides how long a note lasts without the program having to switch it off.
     * <p>
     * A write to the channel's length register loads one of {@link #LENGTH_TABLE}'s 32 values and
     * every half frame takes one off it, until either the note runs out or the halt flag stops the
     * counting -- which is what makes a note held rather than timed. Reaching zero silences the
     * channel, and so does disabling it through $4015, which zeroes the counter and then refuses
     * to load it again until the channel comes back.
     */
    private static final class LengthCounter {
        private int value;
        private boolean halt;
        private boolean enabled;

        private void serialize(final StateIO io) {
            value = io.u8(value);
            halt = io.bool(halt);
            enabled = io.bool(enabled);
        }

        private void clock() {
            if (!halt && value > 0) {
                value--;
            }
        }

        /**
         * Loads the counter from the top five bits of a length register write, unless the channel
         * is switched off.
         */
        private void load(final int data) {
            if (enabled) {
                value = LENGTH_TABLE[(data >> 3) & 0x1F];
            }
        }

        private void setEnabled(final boolean enabled) {
            this.enabled = enabled;

            if (!enabled) {
                value = 0;
            }
        }
    }

    /**
     * The volume envelope the two pulses and the noise channel share.
     * <p>
     * A divider counting quarter frames drives a decay counter that walks 15 down to 0, which is
     * either the channel's volume or, with the constant volume bit set, is ignored in favour of
     * the same four bits read as a number. The loop flag sends the decay back to 15 instead of
     * letting it sit at zero, and it is the same bit as the length counter's halt flag: one bit of
     * the register does both jobs, which is why a looping envelope and a held note always go
     * together.
     */
    private static final class Envelope {
        /**
         * The four bits of the register, which are the divider's period and equally the level used
         * when {@link #constantVolume} is set.
         */
        private int period;

        private boolean constantVolume;
        private boolean loop;

        /**
         * Set by a write to the channel's fourth register and acted on at the next quarter frame,
         * which is what restarts the envelope without resetting the sound generator's own divider.
         */
        private boolean start;

        private int divider;
        private int decay;

        private void serialize(final StateIO io) {
            period = io.u8(period);
            constantVolume = io.bool(constantVolume);
            loop = io.bool(loop);
            start = io.bool(start);
            divider = io.u8(divider);
            decay = io.u8(decay);
        }

        private void clock() {
            if (start) {
                start = false;
                decay = 15;
                divider = period;
                return;
            }

            if (divider > 0) {
                divider--;
                return;
            }

            divider = period;

            if (decay > 0) {
                decay--;
            } else if (loop) {
                decay = 15;
            }
        }

        /**
         * @return the volume the channel plays at, 0 to 15.
         */
        private int volume() {
            return constantVolume ? period : decay;
        }
    }

    /**
     * A first-order high-pass, which is what a capacitor in series with the signal comes to.
     * <p>
     * Two of these are in the console's output path and both matter here. Without them the DMC's
     * level -- which is a number from 0 to 127 and rarely anywhere near zero -- would sit as a
     * standing offset under everything else, and a game that pops $4011 would move the whole
     * waveform up rather than making a click.
     */
    private static final class HighPass {
        private final double coefficient;

        private double lastInput;
        private double lastOutput;

        /**
         * The coefficient is not here: it is final and derived from the cutoff, so it is the same
         * number in every build. Only what the filter has accumulated travels.
         */
        private void serialize(final StateIO io) {
            lastInput = io.f64(lastInput);
            lastOutput = io.f64(lastOutput);
        }

        private HighPass(final double cutoffHz) {
            var rc = 1.0 / (2.0 * Math.PI * cutoffHz);
            var dt = 1.0 / SAMPLE_RATE;

            coefficient = rc / (rc + dt);
        }

        private double filter(final double sample) {
            lastOutput = coefficient * (lastOutput + sample - lastInput);
            lastInput = sample;

            return lastOutput;
        }
    }

    /**
     * A first-order low-pass, which is the other half of the same idea and stands in for
     * everything between the chip and a television speaker that could not follow a 14kHz edge.
     */
    private static final class LowPass {
        private final double coefficient;

        private double lastOutput;

        private void serialize(final StateIO io) {
            lastOutput = io.f64(lastOutput);
        }

        private LowPass(final double cutoffHz) {
            var rc = 1.0 / (2.0 * Math.PI * cutoffHz);
            var dt = 1.0 / SAMPLE_RATE;

            coefficient = dt / (rc + dt);
        }

        private double filter(final double sample) {
            lastOutput += coefficient * (sample - lastOutput);

            return lastOutput;
        }
    }

    // =================================================================== the channels

    /**
     * One of the two pulse channels: a square wave with four duty cycles, an envelope and a sweep
     * unit.
     * <p>
     * The two are the same circuit twice over with one difference, in how the sweep unit negates
     * -- see {@link #onesComplementNegate} -- which is why they are one class and not two.
     */
    private static final class Pulse {
        /**
         * The four duty cycles, one bit per step of the eight step sequencer.
         * <p>
         * The last is the second with every bit flipped, which is the same 25% wave a quarter of
         * a period along, so what it really offers is a phase difference against the other pulse.
         */
        private static final int[][] DUTY_CYCLES = {
                {0, 1, 0, 0, 0, 0, 0, 0},  // 12.5%
                {0, 1, 1, 0, 0, 0, 0, 0},  // 25%
                {0, 1, 1, 1, 1, 0, 0, 0},  // 50%
                {1, 0, 0, 1, 1, 1, 1, 1},  // 25% inverted
        };

        /**
         * The shortest period the channel will play. Anything below this is inaudible anyway --
         * the wave would be above 12kHz -- and the sweep unit mutes it outright.
         */
        private static final int MINIMUM_PERIOD = 8;

        /**
         * The longest period the eleven bit timer can hold, and so the point past which the sweep
         * unit mutes the channel rather than letting it wrap.
         */
        private static final int MAXIMUM_PERIOD = 0x7FF;

        private final LengthCounter lengthCounter = new LengthCounter();
        private final Envelope envelope = new Envelope();

        /**
         * True for pulse 1, whose sweep unit negates by adding the one's complement of the change
         * rather than the two's complement, so that a downward sweep lands one step lower than
         * pulse 2's would. Two channels sweeping down together drift apart by that one step, and
         * games are written knowing it.
         */
        private final boolean onesComplementNegate;

        private int duty;
        private int sequencerStep;

        /**
         * The eleven bit divider period, written across $4002 and $4003. The sequencer advances
         * once every {@code period + 1} APU cycles, so the wave comes out at one sixteenth of that
         * in CPU cycles: eight steps at two CPU cycles each.
         */
        private int period;
        private int timer;

        // The sweep unit: a second divider, clocked at a half frame, that walks the period itself.
        private boolean sweepEnabled;
        private boolean sweepNegate;
        private int sweepPeriod;
        private int sweepShift;
        private boolean sweepReload;
        private int sweepDivider;

        private Pulse(final boolean onesComplementNegate) {
            this.onesComplementNegate = onesComplementNegate;
        }

        /**
         * Which pulse this is stays out of it: {@code onesComplementNegate} is which chip pin the
         * channel is wired to, not something it remembers.
         */
        private void serialize(final StateIO io) {
            lengthCounter.serialize(io);
            envelope.serialize(io);

            duty = io.u8(duty);
            sequencerStep = io.u8(sequencerStep);
            period = io.u16(period);
            timer = io.u16(timer);

            sweepEnabled = io.bool(sweepEnabled);
            sweepNegate = io.bool(sweepNegate);
            sweepPeriod = io.u8(sweepPeriod);
            sweepShift = io.u8(sweepShift);
            sweepReload = io.bool(sweepReload);
            sweepDivider = io.u8(sweepDivider);
        }

        private void write(final int register, final int data) {
            switch (register) {
                case 0 -> {
                    duty = (data >> 6) & 3;
                    envelope.period = data & 0x0F;
                    envelope.constantVolume = (data & 0x10) != 0;
                    envelope.loop = (data & 0x20) != 0;
                    lengthCounter.halt = (data & 0x20) != 0;
                }
                case 1 -> {
                    sweepEnabled = (data & 0x80) != 0;
                    sweepPeriod = (data >> 4) & 0x07;
                    sweepNegate = (data & 0x08) != 0;
                    sweepShift = data & 0x07;
                    sweepReload = true;
                }
                case 2 -> period = (period & 0x700) | data;
                default -> {
                    period = (period & 0x0FF) | ((data & 0x07) << 8);
                    lengthCounter.load(data);

                    // The wave starts again from the top of its duty cycle, and the envelope
                    // restarts with it. The divider is deliberately left where it is: only the
                    // phase is reset, not the tuning.
                    sequencerStep = 0;
                    envelope.start = true;
                }
            }
        }

        /**
         * One APU cycle of the divider, which is every other CPU cycle.
         */
        private void tickTimer() {
            if (timer > 0) {
                timer--;
                return;
            }

            timer = period;
            sequencerStep = (sequencerStep + 1) & 7;
        }

        /**
         * A half frame of the sweep unit.
         * <p>
         * The order is the hardware's and not the obvious one: the period is adjusted before the
         * divider is reloaded, and the divider keeps counting even while the unit is muting the
         * channel, so a sweep that has run the channel out of range starts again the moment the
         * program writes a period back into it.
         */
        private void clockSweep() {
            if (sweepDivider == 0 && sweepEnabled && sweepShift > 0 && !isMuted()) {
                period = targetPeriod();
            }

            if (sweepDivider == 0 || sweepReload) {
                sweepDivider = sweepPeriod;
                sweepReload = false;
            } else {
                sweepDivider--;
            }
        }

        /**
         * Where the sweep unit is taking the period next: the period plus or minus itself shifted
         * right by the shift count.
         * <p>
         * Recomputed on demand rather than kept, because it changes with every write to $4002 and
         * $4003 as well as with the sweep unit's own steps, and it is what the muting test is
         * made of.
         */
        private int targetPeriod() {
            var change = period >> sweepShift;

            if (!sweepNegate) {
                return period + change;
            }

            return period - change - (onesComplementNegate ? 1 : 0);
        }

        /**
         * Whether the sweep unit is holding the channel silent.
         * <p>
         * Two ways, and neither of them cares whether sweeping is switched on: a period below
         * eight, and a target period the eleven bit timer could not hold. The second is why a
         * pulse channel cannot play the bottom two octaves at all with the shift count left at
         * zero, where the target is twice the period -- a real limit of the hardware that games
         * write around by putting bass on the triangle.
         */
        private boolean isMuted() {
            return period < MINIMUM_PERIOD || targetPeriod() > MAXIMUM_PERIOD;
        }

        /**
         * @return the four bit level the channel is putting into the mixer.
         */
        private int output() {
            if (lengthCounter.value == 0 || isMuted() || DUTY_CYCLES[duty][sequencerStep] == 0) {
                return 0;
            }

            return envelope.volume();
        }

        /**
         * The sequencer runs at one step every {@code period + 1} APU cycles and there are eight of
         * them, so a whole wave is sixteen CPU cycles' worth of that. Zero while the sweep unit is
         * muting the channel, since there is no note to name.
         */
        private VoiceState state(final APUChannel channel, final Region region) {
            return new VoiceState(
                    channel,
                    lengthCounter.value > 0,
                    lengthCounter.value,
                    period,
                    isMuted() ? 0 : region.cpuClockHz() / (16.0 * (period + 1)),
                    output(),
                    envelope.volume(),
                    envelope.constantVolume,
                    lengthCounter.halt,
                    duty,
                    false,
                    -1,
                    -1,
                    -1);
        }

        private void setEnabled(final boolean enabled) {
            lengthCounter.setEnabled(enabled);
        }
    }

    /**
     * The triangle channel: a 32 step ramp up and back down, at four bits a step.
     * <p>
     * It has no volume control of any kind -- the steps are the waveform -- and it is clocked at
     * the CPU rate rather than half of it, so it reaches an octave lower than the pulses for the
     * same period. Its second gate, on top of the length counter every channel has, is the linear
     * counter: a finer grained timer clocked at a quarter frame instead of a half.
     */
    private static final class Triangle {
        /**
         * The waveform, which is the whole channel: fifteen down to zero and back up again, at one
         * step per divider tick. Nothing scales it, so the triangle is either playing at full
         * volume or not playing.
         */
        private static final int[] SEQUENCE = {
                15, 14, 13, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0,
                0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
        };

        private final LengthCounter lengthCounter = new LengthCounter();

        /**
         * The linear counter's reload value, and the control bit that doubles as the length
         * counter's halt flag.
         */
        private int linearCounterReload;
        private boolean control;

        /**
         * Set by a write to $400B and cleared by a quarter frame once the control bit is clear,
         * which is what makes the counter reload once rather than every quarter frame.
         */
        private boolean linearCounterReloadFlag;

        private int linearCounter;

        private int period;
        private int timer;
        private int sequencerStep;

        private void serialize(final StateIO io) {
            lengthCounter.serialize(io);

            linearCounterReload = io.u8(linearCounterReload);
            control = io.bool(control);
            linearCounterReloadFlag = io.bool(linearCounterReloadFlag);
            linearCounter = io.u8(linearCounter);
            period = io.u16(period);
            timer = io.u16(timer);
            sequencerStep = io.u8(sequencerStep);
        }

        private void write(final int register, final int data) {
            switch (register) {
                case 0 -> {
                    linearCounterReload = data & 0x7F;
                    control = (data & 0x80) != 0;
                    lengthCounter.halt = control;
                }
                case 2 -> period = (period & 0x700) | data;
                case 3 -> {
                    period = (period & 0x0FF) | ((data & 0x07) << 8);
                    lengthCounter.load(data);
                    linearCounterReloadFlag = true;
                }
                default -> { /* $4009 is not wired to anything */ }
            }
        }

        private void clockLinearCounter() {
            if (linearCounterReloadFlag) {
                linearCounter = linearCounterReload;
            } else if (linearCounter > 0) {
                linearCounter--;
            }

            if (!control) {
                linearCounterReloadFlag = false;
            }
        }

        /**
         * One CPU cycle of the divider.
         * <p>
         * The two counters do not silence this channel, they stop it: the sequencer simply holds
         * wherever it had got to. Which is exactly what makes the triangle click rather than fade
         * when a note ends, and why an emulator that zeroed the output instead sounds wrong.
         */
        private void tickTimer() {
            if (timer > 0) {
                timer--;
                return;
            }

            timer = period;

            if (linearCounter > 0 && lengthCounter.value > 0) {
                sequencerStep = (sequencerStep + 1) & 31;
            }
        }

        /**
         * @return the four bit level the channel is putting into the mixer.
         */
        private int output() {
            return SEQUENCE[sequencerStep];
        }

        /**
         * Thirty-two steps at one CPU cycle's worth of divider each, so an octave below a pulse
         * written with the same period -- which is why a bass line and its melody are written a
         * period apart rather than an octave.
         * <p>
         * A period below two is a wave above 15kHz that the chip turns into a steady half level
         * rather than a note, so it is reported as no pitch at all.
         */
        private VoiceState state(final Region region) {
            return new VoiceState(
                    APUChannel.TRIANGLE,
                    lengthCounter.value > 0,
                    lengthCounter.value,
                    period,
                    period >= 2 ? region.cpuClockHz() / (32.0 * (period + 1)) : 0,
                    output(),
                    -1,
                    false,
                    control,
                    -1,
                    false,
                    linearCounter,
                    -1,
                    -1);
        }

        private void resetSequencer() {
            sequencerStep = 0;
        }

        private void setEnabled(final boolean enabled) {
            lengthCounter.setEnabled(enabled);
        }
    }

    /**
     * The noise channel: a shift register whose feedback makes a sequence long enough to hear as
     * hiss, gated by an envelope and a length counter like a pulse.
     */
    private final class Noise {
        private final LengthCounter lengthCounter = new LengthCounter();
        private final Envelope envelope = new Envelope();

        /**
         * The shift register, fifteen bits of it, which powers up holding one. It must never be
         * allowed to reach zero: the feedback is exclusive-or, so zero would stay zero and the
         * channel would go silent forever.
         */
        private int shiftRegister = 1;

        /**
         * True while $400E's top bit is set, which taps the register six bits along instead of
         * one. The sequence is 93 steps long instead of 32767, so it repeats fast enough to be
         * heard as a metallic pitch rather than as hiss.
         */
        private boolean shortMode;

        private int period;
        private int timer;

        private void serialize(final StateIO io) {
            lengthCounter.serialize(io);
            envelope.serialize(io);

            shiftRegister = io.u16(shiftRegister);
            shortMode = io.bool(shortMode);
            period = io.u16(period);
            timer = io.u16(timer);

            // Zero is a fixed point of the feedback: a register that reaches it never leaves, and
            // the channel is silent for the rest of the session. One line here is the difference
            // between a damaged file costing a load and costing the sound.
            if (shiftRegister == 0) {
                shiftRegister = 1;
            }
        }

        private void write(final int register, final int data) {
            switch (register) {
                case 0 -> {
                    envelope.period = data & 0x0F;
                    envelope.constantVolume = (data & 0x10) != 0;
                    envelope.loop = (data & 0x20) != 0;
                    lengthCounter.halt = (data & 0x20) != 0;
                }
                case 2 -> {
                    shortMode = (data & 0x80) != 0;

                    // The divider here counts APU cycles, which is every other CPU cycle; every
                    // period in either region's table is even, so halving them loses nothing. The
                    // bottom of the table is not really noise at all -- at four cycles the shift
                    // register runs at 447kHz and what comes out is a tone.
                    period = region.noisePeriod(data & 0x0F) / 2 - 1;
                }
                case 3 -> {
                    lengthCounter.load(data);
                    envelope.start = true;
                }
                default -> { /* $400D is not wired to anything */ }
            }
        }

        /**
         * One APU cycle of the divider, which is every other CPU cycle.
         */
        private void tickTimer() {
            if (timer > 0) {
                timer--;
                return;
            }

            timer = period;

            var tap = shortMode ? 6 : 1;
            var feedback = (shiftRegister & 1) ^ ((shiftRegister >> tap) & 1);

            shiftRegister = (shiftRegister >> 1) | (feedback << 14);
        }

        /**
         * @return the four bit level the channel is putting into the mixer.
         */
        private int output() {
            if (lengthCounter.value == 0 || (shiftRegister & 1) != 0) {
                return 0;
            }

            return envelope.volume();
        }

        /**
         * How fast the shift register is being clocked rather than a note: what comes out is a
         * pseudo-random sequence 32767 steps long, or 93 in short mode, so the pitch anybody hears
         * is that rate divided by the sequence rather than the rate itself.
         * <p>
         * The divider counts APU cycles, which is every other CPU cycle, and holds one less than
         * the count -- so a period of 15 is 32 CPU cycles and 55.9kHz, which is the figure every
         * table of these prints.
         */
        private VoiceState state(final Region region) {
            return new VoiceState(
                    APUChannel.NOISE,
                    lengthCounter.value > 0,
                    lengthCounter.value,
                    period,
                    region.cpuClockHz() / (2.0 * (period + 1)),
                    output(),
                    envelope.volume(),
                    envelope.constantVolume,
                    lengthCounter.halt,
                    -1,
                    shortMode,
                    -1,
                    -1,
                    -1);
        }

        private void setEnabled(final boolean enabled) {
            lengthCounter.setEnabled(enabled);
        }
    }

    /**
     * The delta modulation channel, which plays recorded sound rather than generating it.
     * <p>
     * A seven bit level that each bit of the sample moves up or down by two: one bit per sample,
     * which is why a second of speech costs a few kilobytes of cartridge instead of tens. Nothing
     * gates it -- no envelope, no length counter -- and the program can also just write the level
     * straight to $4011, which is how games without any DPCM sample at all still use this channel,
     * as a one bit speaker for clicks.
     * <p>
     * It is the only part of the APU that touches memory. When its sample buffer runs dry it takes
     * the bus off the CPU for four cycles and reads the next byte itself, which is what
     * {@link APU#isDMCFetchPending()} and its two companions are for.
     */
    private final class DMC {
        /**
         * The highest level the output can be stepped up to. Steps are two at a time from a seven
         * bit level, so 126 is the top and 125 the last one a step can start from.
         */
        private static final int MAXIMUM_STEP_FROM = 125;

        /**
         * How long a sample started by a $4015 write takes to ask for its first byte, in CPU
         * cycles. Four of them separate the write from the halt, which is the two APU cycles the
         * ROM prints, and the last of the four is the request latch every fetch goes through.
         */
        private static final int LOAD_DELAY = 3;

        private boolean irqEnabled;
        private boolean irqFlag;
        private boolean loop;

        private int period;
        private int timer;

        /**
         * The level itself, seven bits of it, which is what reaches the mixer.
         */
        private int output;

        /**
         * How fast bytes are being turned into deltas, which is the sample rate: 33143Hz at the top
         * of the table and 4182Hz at the bottom. Where it is reading from is the address that moves
         * as the sample plays, not the one $4012 was written with, since that is the question
         * anybody watching a sample play is asking.
         */
        private VoiceState state(final Region region) {
            return new VoiceState(
                    APUChannel.DMC,
                    bytesRemaining > 0,
                    0,
                    period,
                    period > 0 ? region.cpuClockHz() / period : 0,
                    output,
                    -1,
                    false,
                    loop,
                    -1,
                    false,
                    -1,
                    currentAddress,
                    bytesRemaining);
        }

        /**
         * Where the sample starts and how long it is, as $4012 and $4013 spell them: the address
         * in 64 byte steps from $C000, and the length in sixteen byte steps plus one.
         */
        private int sampleAddress = 0xC000;
        private int sampleLength = 1;

        private int currentAddress = 0xC000;
        private int bytesRemaining;

        /**
         * The byte read ahead by the DMA, waiting for the shift register to want it. Having it is
         * what lets the fetch happen at any point in the eight bits rather than exactly at the
         * end of them.
         */
        private int sampleBuffer;
        private boolean sampleBufferFilled;

        private int shiftRegister;
        private int bitsRemaining = 8;

        /**
         * True while there is nothing to play, which freezes the level rather than zeroing it. A
         * sample that ends leaves the speaker where it was; dropping it to zero would click.
         */
        private boolean silence = true;

        /**
         * How many CPU cycles are left before a sample that $4015 has just started asks for its
         * first byte.
         * <p>
         * A sample already playing feeds itself: the timer empties the buffer and the fetch is
         * asked for on the cycle after. One started by a write to $4015 is not that -- the
         * channel has to be loaded first -- and AccuracyCoin measures how long that takes by
         * arranging for the transfer to land on a {@code LDA $2002} and seeing whether the halted
         * CPU's re-read cleared the VBlank flag before the instruction's own read of it.
         */
        private int loadDelay;

        /**
         * Including the read-ahead buffer and whether it is filled, because a DMA fetch in flight is
         * the one piece of this channel the CPU can see the effect of: the cycle it steals is what
         * makes a DMC sample shift a raster split.
         */
        private void serialize(final StateIO io) {
            irqEnabled = io.bool(irqEnabled);
            irqFlag = io.bool(irqFlag);
            loop = io.bool(loop);

            period = io.u16(period);
            timer = io.u16(timer);
            output = io.u8(output);

            sampleAddress = io.u16(sampleAddress);
            sampleLength = io.u16(sampleLength);
            currentAddress = io.u16(currentAddress);
            bytesRemaining = io.u16(bytesRemaining);

            sampleBuffer = io.u8(sampleBuffer);
            sampleBufferFilled = io.bool(sampleBufferFilled);
            shiftRegister = io.u8(shiftRegister);
            bitsRemaining = io.u8(bitsRemaining);
            silence = io.bool(silence);
            loadDelay = io.u8(loadDelay);
        }

        private void write(final int register, final int data) {
            switch (register) {
                case 0 -> {
                    irqEnabled = (data & 0x80) != 0;
                    loop = (data & 0x40) != 0;

                    // One of the sixteen rates $4010 can select, in CPU cycles between one bit of
                    // the sample and the next. The fastest is 33kHz and the slowest 4kHz.
                    period = region.dmcRate(data & 0x0F) - 1;

                    // Switching the interrupt off acknowledges one that is already there, which is
                    // the other way a program has of clearing it besides writing $4015.
                    if (!irqEnabled) {
                        setDMCIRQFlag(false);
                    }
                }
                case 1 -> output = data & 0x7F;
                case 2 -> sampleAddress = 0xC000 + (data & 0xFF) * 64;
                default -> sampleLength = (data & 0xFF) * 16 + 1;
            }
        }

        /**
         * One CPU cycle of the divider.
         */
        private void tickTimer() {
            if (loadDelay > 0) {
                loadDelay--;
            }

            if (timer > 0) {
                timer--;
                return;
            }

            timer = period;
            clockOutputUnit();
        }

        /**
         * One bit of the sample: a step of two up or down, clamped at both ends of the seven bits.
         * <p>
         * The clamp is the reason a DPCM sample is not simply a waveform. A step that would run
         * off either end is dropped rather than wrapping, so the encoder has to keep the signal
         * inside what a chain of two-unit steps can follow.
         */
        private void clockOutputUnit() {
            if (!silence) {
                if ((shiftRegister & 1) != 0) {
                    if (output <= MAXIMUM_STEP_FROM) {
                        output += 2;
                    }
                } else if (output >= 2) {
                    output -= 2;
                }
            }

            shiftRegister >>= 1;
            bitsRemaining--;

            if (bitsRemaining > 0) {
                return;
            }

            bitsRemaining = 8;
            silence = !sampleBufferFilled;

            if (sampleBufferFilled) {
                shiftRegister = sampleBuffer;
                sampleBufferFilled = false;
            }
        }

        /**
         * Bit 4 of a $4015 write. Clearing it stops the sample where it is; setting it starts the
         * sample again from the top, but only if there is not one already playing.
         */
        private void setEnabled(final boolean enabled) {
            if (!enabled) {
                bytesRemaining = 0;
            } else if (bytesRemaining == 0) {
                currentAddress = sampleAddress;
                bytesRemaining = sampleLength;

                // Counted to the chip's own clock rather than the processor's. The fetch a $4015
                // write starts halts the CPU on the get cycle of the second APU cycle after the
                // write -- so both halves of one APU cycle aim at the same cycle, and a write on
                // the put half waits one cycle less than a write on the get half. Left as a fixed
                // number of CPU cycles the two land two cycles apart instead of together, which
                // AccuracyCoin's Explicit DMA Abort reads straight off: it measures the fetch's
                // position against code an abort has already shifted by an odd number of cycles.
                loadDelay = LOAD_DELAY - (CPUBus.isGetCycle(cycles - 1) ? 0 : 1);
            }
        }

        private boolean isFetchPending() {
            return loadDelay == 0 && !sampleBufferFilled && bytesRemaining > 0;
        }

        /**
         * Whether the output unit is one or two cycles from emptying the buffer, and so from
         * asking for the next byte.
         * <p>
         * Playback stopping inside that window is what schedules an aborted DMA. See
         * MMU.scheduleAbortedDMA.
         */
        private boolean isReloadImminent() {
            return bitsRemaining == 1 && timer <= 1;
        }

        private boolean finishFetch(final int data) {
            // A fetch whose sample was taken from under it still happens on the bus -- see
            // MMU.scheduleAbortedDMA -- but the byte has nowhere to go. Loading it anyway would
            // leave the buffer full, and the next sample the CPU started would then wait for the
            // timer to empty it rather than fetching straight away.
            if (bytesRemaining == 0) {
                return false;
            }

            sampleBuffer = data & 0xFF;
            sampleBufferFilled = true;

            // The counter is fourteen bits wide and wraps to $8000, so a sample that runs off the
            // end of the cartridge comes back round to the start of it rather than reading
            // anything else.
            currentAddress = currentAddress == 0xFFFF ? 0x8000 : currentAddress + 1;
            bytesRemaining--;

            if (bytesRemaining > 0) {
                return false;
            }

            if (loop) {
                currentAddress = sampleAddress;
                bytesRemaining = sampleLength;

                return false;
            }

            if (irqEnabled) {
                setDMCIRQFlag(true);
            }

            // The sample has just run out. If the output unit was about to ask for another byte,
            // that is an implicit stop, and it schedules an aborted DMA exactly as switching the
            // channel off by hand would.
            return isReloadImminent();
        }
    }
}
