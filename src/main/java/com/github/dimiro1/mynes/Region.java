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
    NTSC("ntsc", "NTSC", 12, 4, 1_789_773.0, 16_639_267L, 262, true, 9000,
            7457, 14913, 22371, 29828, 29829, 29830, 37281, 37282),

    /**
     * The 2C07 and 2A07: 50.0070 frames a second, 312 scanlines, 3.2 dots to a CPU cycle.
     */
    PAL("pal", "PAL", 16, 5, 1_662_607.0, 19_997_209L, 312, false, 29000,
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
     * -- 7843 dots against 24893 -- so one number cannot serve for both, and the NTSC one very
     * nearly does not serve for NTSC either. That is the whole of the phenomenon: the charge only
     * just outlasts the dark part of the picture.
     * <p>
     * 9000 is the figure {@code ppu_open_bus.nes} and the {@code oam} suite are happy with, which
     * makes it the measured one. There is no PAL test ROM to answer for the other, so it is the
     * same margin over the same gap: about fifteen percent, which on a 2C07 is 29000 dots. Both
     * are a good deal shorter than the tens of a second {@code oam_stress} would want, which is
     * why that ROM is an accepted failure.
     */
    public int oamDecayDots() {
        return oamDecayDots;
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
