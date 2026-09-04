package com.github.dimiro1.mynes;

/**
 * Which console this is: the NTSC one sold in America and Japan, or the PAL one sold in Europe.
 * <p>
 * They are not the same machine running at a different speed. One master crystal is divided two ways
 * on each, and both divisors differ: the NTSC 2A03 takes the clock down by twelve and the 2C02 by
 * four, which is exactly three dots to a CPU cycle, while the PAL 2A07 divides by sixteen and the
 * 2C07 by five, which is 3.2. So the beam is in a different place relative to the program on every
 * cycle, the frame has seventy blanking scanlines instead of twenty, and every table in the APU that
 * is counted in CPU cycles -- the frame counter's steps, the noise periods, the DMC's rates -- is a
 * different table. A PAL cartridge run on an NTSC machine is not merely 17% fast; it is a game whose
 * every timing assumption is wrong.
 * <p>
 * Everything that differs lives here, so that the chips can be written as one machine that is told
 * which one it is, and so that there is one place to read to find out what a region actually is.
 * <p>
 * <strong>Tables here are static and scalars are primitives, on purpose.</strong>
 * {@code SaveStateCompletenessTests} walks the console reflectively and overwrites the contents of
 * every primitive array it can reach, final or not, to prove a save state puts them back. It reaches
 * this enum through {@link PPU} and {@link APU}, and an {@code int[]} instance field here would be
 * vandalised for the rest of the test run -- a lookup table is not state and no save state restores
 * it. Hence {@link #noisePeriod} and {@link #dmcRate}, which index a static table rather than
 * holding one.
 *
 * @see <a href="https://www.nesdev.org/wiki/Cycle_reference_chart">NESdev: cycle reference chart</a>
 */
public enum Region {

    /**
     * The 2C02 and 2A03: 60.0988 frames a second, 262 scanlines, three dots to a CPU cycle.
     */
    NTSC("ntsc", "NTSC", 12, 4, 1_789_773.0, 16_639_267L, 262, true, 100000, 8.0 / 7.0,
            7457, 14913, 22371, 29828, 29829, 29830, 37281, 37282),

    /**
     * The 2C07 and 2A07: 50.0070 frames a second, 312 scanlines, 3.2 dots to a CPU cycle.
     */
    PAL("pal", "PAL", 16, 5, 1_662_607.0, 19_997_209L, 312, false, 100000,
            7_375_000.0 / 5_320_342.5,
            8313, 16627, 24939, 33252, 33253, 33254, 41565, 41566);

    /**
     * The sixteen periods $400E can select, in CPU cycles, one row per region.
     * <p>
     * All thirty-two are even, which matters: the divider that counts them runs at half the CPU
     * clock, so {@link APU} halves them and nothing is lost.
     */
    private static final int[][] NOISE_PERIODS = {
            {4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068},
            {4, 8, 14, 30, 60, 88, 118, 148, 188, 236, 354, 472, 708, 944, 1890, 3778},
    };

    /**
     * The sixteen rates $4010 can select, in CPU cycles between one bit of the sample and the next.
     */
    private static final int[][] DMC_RATES = {
            {428, 380, 340, 320, 286, 254, 226, 214, 190, 160, 142, 128, 106, 84, 72, 54},
            {398, 354, 316, 298, 276, 236, 210, 198, 176, 148, 132, 118, 98, 78, 66, 50},
    };

    private final String id;
    private final String label;
    private final int cpuDivider;
    private final int ppuDivider;
    private final double cpuClockHz;
    private final long frameNanos;
    private final int scanlinesPerFrame;
    private final boolean skipsDotOnOddFrames;
    private final int oamDecayDots;
    private final double pixelAspect;

    // The frame counter's sequence, in CPU cycles from the point it was last reset. Named rather
    // than inlined because blargg's 4-jitter, 5-len_timing and 6-irq_flag_timing are sensitive to
    // all of them at once, and this is where a disagreement with real hardware is calibrated out.
    private final int step1Cycle;
    private final int step2Cycle;
    private final int step3Cycle;
    private final int irqFirstCycle;
    private final int step4Cycle;
    private final int fourStepPeriod;
    private final int step5Cycle;
    private final int fiveStepPeriod;

    Region(
            final String id,
            final String label,
            final int cpuDivider,
            final int ppuDivider,
            final double cpuClockHz,
            final long frameNanos,
            final int scanlinesPerFrame,
            final boolean skipsDotOnOddFrames,
            final int oamDecayDots,
            final double pixelAspect,
            final int step1Cycle,
            final int step2Cycle,
            final int step3Cycle,
            final int irqFirstCycle,
            final int step4Cycle,
            final int fourStepPeriod,
            final int step5Cycle,
            final int fiveStepPeriod) {
        this.id = id;
        this.label = label;
        this.cpuDivider = cpuDivider;
        this.ppuDivider = ppuDivider;
        this.cpuClockHz = cpuClockHz;
        this.frameNanos = frameNanos;
        this.scanlinesPerFrame = scanlinesPerFrame;
        this.skipsDotOnOddFrames = skipsDotOnOddFrames;
        this.oamDecayDots = oamDecayDots;
        this.pixelAspect = pixelAspect;
        this.step1Cycle = step1Cycle;
        this.step2Cycle = step2Cycle;
        this.step3Cycle = step3Cycle;
        this.irqFirstCycle = irqFirstCycle;
        this.step4Cycle = step4Cycle;
        this.fourStepPeriod = fourStepPeriod;
        this.step5Cycle = step5Cycle;
        this.fiveStepPeriod = fiveStepPeriod;
    }

    /**
     * How this region is spelled in a config file and on a command line.
     */
    public String id() {
        return id;
    }

    /**
     * How it is spelled in a menu.
     */
    public String label() {
        return label;
    }

    /**
     * How many master clocks make one CPU cycle: twelve, or sixteen.
     */
    public int cpuDivider() {
        return cpuDivider;
    }

    /**
     * How many master clocks make one PPU dot: four, or five.
     */
    public int ppuDivider() {
        return ppuDivider;
    }

    /**
     * The CPU clock, which is what the APU counts in and so what its decimator divides down from.
     */
    public double cpuClockHz() {
        return cpuClockHz;
    }

    /**
     * How long one frame lasts, for a front end that has to keep in step with a display.
     * <p>
     * 60.0988 frames a second rather than 60, and 50.0070 rather than 50. The third of a percent
     * matters because the APU agrees with it: rounding either to a whole number would leave the
     * sound card and the machine disagreeing, which is a card running dry every few minutes.
     */
    public long frameNanos() {
        return frameNanos;
    }

    /**
     * 262 scanlines, or 312. The extra ninety are all vertical blank, which is why a PAL game has
     * three and a half times as long to do its work between pictures -- and why one written for it
     * does not fit in an NTSC frame.
     */
    public int scanlinesPerFrame() {
        return scanlinesPerFrame;
    }

    /**
     * The last scanline of a frame, which prepares the shifters for line 0 rather than drawing.
     */
    public int preRenderLine() {
        return scanlinesPerFrame - 1;
    }

    /**
     * Whether an odd frame drops the last dot of the pre-render line.
     * <p>
     * The 2C02 does, which is what keeps the NTSC colour burst in step from frame to frame. PAL
     * corrects its own burst phase by alternating it every line, so the 2C07 has nothing to fix and
     * every frame is the full 106392 dots.
     */
    public boolean skipsDotOnOddFrames() {
        return skipsDotOnOddFrames;
    }

    /**
     * How many dots an eight byte row of OAM holds its charge for.
     * <p>
     * It has to be longer than the gap between one frame's sprite evaluation and the next, because
     * that evaluation is the only thing that refreshes OAM: a machine whose sprites decayed every
     * vertical blank would have no sprites at all. That gap is 23 scanlines on NTSC and 73 on PAL
     * -- 7843 dots against 24893 -- and it has to be longer than a program's own reading of OAM
     * takes, too. AccuracyCoin copies the whole of it out through $2004 eighteen cycles at a time,
     * which is 13824 dots from the first byte to the last, and a window that expires part way
     * through leaves the tail of the copy reading back zeroes.
     * <p>
     * The number below is the same on both machines because what decays is a capacitor and what it
     * decays over is time, not dots -- and the two dot clocks are within one percent of each other
     * (5.369MHz against 5.320MHz). 100000 dots is about nineteen milliseconds, a little over one
     * frame. It stays a property of the region rather than becoming a constant because it is a
     * fact about a console and belongs with the others.
     * <p>
     * <b>Why a frame and not the millisecond or two NESdev describes.</b> The wiki says OAM lasts
     * "at least as long as an NTSC vertical blank interval (~1.3ms), but not much longer than
     * this", and in the same breath that decay is "more or less random", sensitive to temperature,
     * and something most emulators do not model at all. AccuracyCoin's OAM Corruption test is the
     * harder measurement: it leaves rendering off for 27400 CPU cycles -- 82200 dots, about
     * fifteen milliseconds -- and then expects to read OAM back intact, on the console its author
     * ran it against. A window that expires inside that does not merely lose the test, it wins it
     * for the wrong reason: every row reads back as zeroes, and the ROM's search for a row that
     * has become a copy of row 0 finds the first decayed one and calls it corruption.
     * <p>
     * So this is set from the measurement rather than from the estimate. Still far shorter than
     * the tens of a second {@code oam_stress} would want, which is why that ROM remains an
     * accepted failure.
     */
    public int oamDecayDots() {
        return oamDecayDots;
    }

    /**
     * How much wider than tall a television drew one of the chip's pixels.
     * <p>
     * The framebuffer is square: 256 by 240 numbers, each of them one pixel. The screen it went to
     * was not, because neither console's dot clock divides its line into square pixels. The 2C02
     * puts its 256 pixels and the border either side of them into 280 pixels' worth of a 4:3 line,
     * so a pixel is {@code 240/280 * 4/3} as wide as it is tall -- exactly 8:7. The 2C07's line
     * is 277 of them at a different clock, which is 7375000/5320342.5 and does not simplify;
     * 1.3862 is what it comes to, and 7:5 and 18:13 are the fractions people round it to.
     * <p>
     * A property of the region for the reason {@link #oamDecayDots} is: it is a fact about one of
     * these consoles and belongs with the others. Nothing inside the machine reads it -- the PPU
     * emits indices and knows nothing about televisions -- so it is the front ends that ask, when
     * somebody has said they want the picture the shape the set drew it rather than the shape the
     * memory holds it.
     *
     * @see <a href="https://www.nesdev.org/wiki/Overscan">NESdev: overscan</a>
     */
    public double pixelAspect() {
        return pixelAspect;
    }

    /**
     * One of the sixteen noise periods, in CPU cycles.
     */
    public int noisePeriod(final int index) {
        return NOISE_PERIODS[ordinal()][index];
    }

    /**
     * One of the sixteen DMC rates, in CPU cycles between one bit of the sample and the next.
     */
    public int dmcRate(final int index) {
        return DMC_RATES[ordinal()][index];
    }

    /**
     * The first quarter-frame step: envelopes and the triangle's linear counter.
     */
    public int step1Cycle() {
        return step1Cycle;
    }

    /**
     * The first half-frame step: length counters and sweep units, plus a quarter frame.
     */
    public int step2Cycle() {
        return step2Cycle;
    }

    /**
     * The third step, a quarter frame again.
     */
    public int step3Cycle() {
        return step3Cycle;
    }

    /**
     * The first of the three consecutive cycles the four step sequence sets its interrupt flag on.
     * <p>
     * It is set here, on {@link #step4Cycle()} and on the cycle the sequence wraps -- and since
     * nothing but a $4015 read or the inhibit bit clears it, what that amounts to is a flag that
     * comes up here and stays up. The width matters only to a program reading $4015 in the middle
     * of the window, which is exactly what {@code 6-irq_flag_timing} does.
     */
    public int irqFirstCycle() {
        return irqFirstCycle;
    }

    /**
     * The last step of the four step sequence: a half frame, so a quarter frame too.
     */
    public int step4Cycle() {
        return step4Cycle;
    }

    /**
     * How long the four step sequence is, so also the cycle it wraps on.
     */
    public int fourStepPeriod() {
        return fourStepPeriod;
    }

    /**
     * The extra half-frame step the five step sequence ends on. Its first three steps are the four
     * step sequence's, and its fourth is a cycle where nothing happens at all.
     */
    public int step5Cycle() {
        return step5Cycle;
    }

    /**
     * How long the five step sequence is, so also the cycle it wraps on.
     */
    public int fiveStepPeriod() {
        return fiveStepPeriod;
    }

    /**
     * The region {@code id} names, or null if nothing does. The caller decides what a name nobody
     * recognises is worth: a config file falls back, a command line refuses.
     */
    public static Region byId(final String id) {
        for (var region : values()) {
            if (region.id.equals(id)) {
                return region;
            }
        }

        return null;
    }
}
