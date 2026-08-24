package com.github.dimiro1.mynes;

/**
 * How many idle scanlines to add to a frame, so that the program gets more time in it.
 *
 * <h2>What this is for</h2>
 *
 * A NES game does a frame's worth of work between one NMI and the next, and when that work does not
 * fit the main loop overruns: the next NMI arrives with the last frame unfinished, the game skips a
 * turn, and the picture stutters. Super Mario Bros. 3 under sprite load and Gradius under anything
 * at all are the cases everybody knows. It is not a fault in the console -- it is a cartridge asking
 * for more cycles than 29780 -- so nothing an accurate emulator does will fix it.
 * <p>
 * What every mature emulator offers instead is this, which Mesen calls "additional scanlines before
 * NMI / after NMI": the PPU idles through extra scanlines, the CPU gets
 * {@code 341 / 3} = ~113.67 cycles per line on NTSC and ~106.56 on PAL, and the game finishes its
 * work in time. The picture is drawn exactly as the hardware draws it; what changes is how long the
 * beam spends not drawing it.
 *
 * <h2>Extra lines rather than a faster CPU</h2>
 *
 * The other way to give a program more cycles is to run its clock faster, which is what Mesen's
 * older "overclock rate" did. That breaks every piece of code timed against the beam inside the
 * visible frame -- a mid-screen scroll split, a raster bar, an MMC3 counting scanlines -- because
 * the CPU and the PPU no longer agree about where the picture is. Extra <em>blanking</em> lines
 * cannot: they land where the beam is already idle, so every cycle-counted trick inside the picture
 * still lines up dot for dot.
 *
 * <h2>Before the NMI, or after it</h2>
 *
 * {@link #beforeNmi()} lines are run after the post-render line and before the VBlank flag goes up;
 * {@link #afterNmi()} lines are run at the end of vertical blank, with the flag still up, before the
 * pre-render line. <strong>Before is the knob to reach for.</strong> Extra post-render lines change
 * nothing a game can observe except that the frame is longer. Extra vblank lines move the
 * pre-render line -- and so the picture -- relative to the NMI, which is exactly what code that
 * cycle-counts from the NMI down to a split is measuring.
 * <p>
 * Either way the pre-render line arrives later in CPU cycles than the hardware puts it, so a program
 * that waits out the PPU's warm-up by counting 29658 cycles rather than by waiting for two VBlanks
 * has its first $2000/$2001 writes dropped. That is the same class of difference PAL's fifty extra
 * lines make to an NTSC game, and it is worth knowing about before blaming the cartridge.
 *
 * <h2>The two clocks that are deliberately not stretched</h2>
 *
 * The APU stands still on an extra line rather than running through it -- {@link APU#idle()} --
 * which is what keeps pitch, tempo and the samples-per-frame count the hardware's. And the PPU's own
 * dot clock does not advance on one either, so OAM decay is measured in hardware time; otherwise a
 * setting past 270 lines on NTSC would push a frame's blanking past the charge and every sprite in
 * the game would vanish once a frame.
 *
 * <h2>This is a hack, and the report says so</h2>
 *
 * Unlike the sprite limit, which changes only pixels, this changes the machine's timing and so what
 * the game <em>does</em>. Two runs that disagree about it are two different games rather than two
 * views of one, which is why it rides inside a movie the way Game Genie codes do and is refused
 * while one is recording.
 *
 * @param beforeNmi extra scanlines between the post-render line and the VBlank flag.
 * @param afterNmi  extra scanlines between the end of vertical blank and the pre-render line.
 * @see <a href="https://www.nesdev.org/wiki/PPU_rendering">NESdev: PPU rendering</a>
 */
public record Overclock(int beforeNmi, int afterNmi) {

    /**
     * The most extra lines either half will take, which is Mesen's limit and is chosen for the same
     * reason: it is far past anything a game needs -- four frames' worth on NTSC -- and it is a
     * guard against a typo rather than a considered ceiling.
     */
    public static final int MAX_SCANLINES = 1000;

    /**
     * The hardware: no extra lines at all. What a machine nobody has asked to overclock runs at.
     */
    public static final Overclock NONE = new Overclock(0, 0);

    public Overclock {
        check(beforeNmi, "before");
        check(afterNmi, "after");
    }

    private static void check(final int lines, final String which) {
        if (lines < 0 || lines > MAX_SCANLINES) {
            throw new IllegalArgumentException(
                    "an overclock is 0 to " + MAX_SCANLINES + " scanlines " + which
                            + " the NMI, and " + lines + " is not.");
        }
    }

    /**
     * Whether this is the hardware, and so whether anything below the front end has to know about it
     * at all.
     */
    public boolean isNone() {
        return beforeNmi == 0 && afterNmi == 0;
    }

    /**
     * That many percent of the region's frame, added before the NMI.
     * <p>
     * The conversion a menu needs, because the two ends think in different units: a player wants
     * "half as long again to do the work in" and the chip wants a number of scanlines, and which
     * number that is depends on whether the frame is 262 lines or 312. Fifty percent is 131 lines on
     * NTSC and 156 on PAL.
     * <p>
     * Before the NMI rather than split, for the reason in the class Javadoc: it is the half that
     * breaks nothing a game can observe.
     */
    public static Overclock percentOf(final Region region, final int percent) {
        return new Overclock((int) Math.round(region.scanlinesPerFrame() * percent / 100.0), 0);
    }

    @Override
    public String toString() {
        if (isNone()) {
            return "no extra scanlines";
        }

        return beforeNmi + " extra scanlines before the NMI and " + afterNmi + " after it";
    }
}
