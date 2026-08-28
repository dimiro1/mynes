package com.github.dimiro1.mynes.video;

import com.github.dimiro1.mynes.PPU;

/**
 * The picture a television made of the signal, rather than the picture a palette makes of an index.
 * <p>
 * Every other way of colouring a frame here looks one pixel up in a table. That is a good model of
 * what a 2C02 puts on screen right up until the answer depends on the pixel <em>next door</em>, and
 * three things do: colour bleed, dot crawl, and artefact colours -- the extra hues a game gets by
 * alternating narrow columns, which no table can produce because a table sees one pixel at a time.
 * <p>
 * So this does the other thing. The chip does not encode RGB into composite; it draws the composite
 * waveform directly, out of twelve square waves at the colourburst rate. Reproducing that and then
 * decoding it the way a receiver did is the only way those three come out. It is also why the
 * palette is not consulted at all while this is on: a decoder derives its colours from the signal,
 * and a measured palette is a rival answer to the same question rather than a stage of this one.
 * <p>
 * <strong>Three numbers run the whole thing.</strong> A pixel is eight samples of signal and a
 * colour cycle is twelve, so a pixel is two thirds of a cycle and its colour is mandatorily mixed
 * with its neighbours'. And a scanline is 341 pixels, so {@code 341 * 8 mod 12} is 4 -- a third of
 * a cycle of drift per line, which is exactly the three-line diagonal of dot crawl. {@link PPU}
 * counts that drift and hands the frame's share of it over as {@link PPU#getFramePhase()}.
 * <p>
 * NTSC only. The 2C07 runs ten samples to a pixel, alternates its burst phase every line, and has
 * an even number of cycles in a frame, so none of the three arithmetic facts above survives the
 * crossing; a PAL machine needs its own decoder rather than this one with different constants.
 * <p>
 * Not thread safe: the scratch buffers are fields, so that sixty frames a second cost no
 * allocation. Each front end holds its own.
 *
 * @see <a href="https://www.nesdev.org/wiki/NTSC_video">NESdev: NTSC video</a>
 */
public final class NTSCFilter {

    /**
     * Samples of composite signal per pixel, and per colour cycle. The whole character of the
     * picture is that these two do not divide.
     */
    private static final int SAMPLES_PER_PIXEL = 8;
    private static final int SAMPLES_PER_CYCLE = 12;

    /**
     * How many samples one step of {@link PPU#COLOUR_PHASES} is worth: a scanline is 2728 samples
     * and {@code 2728 mod 12} is 4.
     */
    private static final int SAMPLES_PER_PHASE = 4;

    private static final int WIDTH = PPU.SCREEN_WIDTH;
    private static final int HEIGHT = PPU.SCREEN_HEIGHT;
    private static final int SAMPLES_PER_LINE = WIDTH * SAMPLES_PER_PIXEL;

    /**
     * The terminated voltages the DAC drives, measured into a 75 ohm load by lidnariq. Four signal
     * levels, then the same four again as the emphasis attenuator leaves them; low half first,
     * high half second, which is the order {@link #signal} indexes them in.
     */
    private static final float[] LEVELS = {
            0.228f, 0.312f, 0.552f, 0.880f,
            0.616f, 0.840f, 1.100f, 1.100f,
            0.192f, 0.256f, 0.448f, 0.712f,
            0.500f, 0.676f, 0.896f, 0.896f,
    };

    /**
     * Where the receiver puts black and white. $1D and $20 -- the darkest and brightest levels the
     * chip emits -- rather than the 7.5 and 100 IRE of the standard, because a CRT's luma does not
     * clip at a level and the phosphors are the only upper limit there is.
     */
    private static final float BLACK = 0.312f;
    private static final float WHITE = 1.100f;

    /**
     * How far into {@link #LEVELS} the attenuated copy of the four levels starts. An index rather
     * than a ratio because the attenuated voltages were measured too, and are not quite the
     * unattenuated ones times a constant.
     */
    private static final int ATTENUATED = 8;

    /**
     * How much the DAC's own impedance slows an edge, as a time constant in seconds.
     * <p>
     * NESdev's approximation puts 3e-8 at a 2C02G, whose hue rotates about five degrees a palette
     * row, and half of that at a 2C02E's two and a half. This is the 2C02E figure, and the reason
     * to prefer it is measurable rather than a matter of taste: at 1.5e-8 the decoded hues land
     * within a degree or two of every palette this emulator ships, and at 3e-8 they sit ten to
     * thirteen degrees off all of them. So switching the filter on changes the texture of the
     * picture rather than what colour anything is.
     */
    private static final double DISTORTION = 1.5e-8;

    /**
     * How long one sample lasts. The colour generator runs at twice the master clock, which is the
     * same thing as twelve samples to a colour cycle, so this is the sampling rate the distortion
     * above was measured against rather than a rate chosen here.
     */
    private static final double SAMPLE_SECONDS = 1 / (21_477_272.0 * 2);

    /**
     * The voltage each of the 512 pixel values puts on the wire at each of the twelve phases, and
     * beside it the coefficient the low pass filter uses while that voltage is the input. Flat
     * rather than nested, so a scanline walks them without chasing a reference per sample.
     */
    private static final float[] VOLTAGE = voltageTable();
    private static final float[] ALPHA = alphaTable();

    /**
     * Turns a filtered voltage into the number the box filter wants: black at 0, white at 1, and
     * already divided by the twelve samples it will be summed with.
     */
    private static final float SCALE = 1f / ((WHITE - BLACK) * SAMPLES_PER_CYCLE);

    /**
     * The demodulating carrier, sampled at the twelve phases: {@code sin} for U and {@code cos} for
     * V, each already carrying the factor of two that the integral of a squared sine costs, and the
     * quarter cycle less fifteen degrees that the reference carrier is offset by.
     */
    private static final float[] SIN = carrier(true);
    private static final float[] COS = carrier(false);

    /**
     * The YUV to RGB matrix of SMPTE 170M, inverted.
     */
    private static final float R_V = 1.139883f;
    private static final float G_U = -0.394642f;
    private static final float G_V = -0.580622f;
    private static final float B_U = 2.032062f;

    /**
     * A pixel of signal either side of the picture, because the twelve sample window of the first
     * and last pixels reaches six samples outside it.
     * <p>
     * A real scanline has fifteen pixels of border out there and the framebuffer does not carry
     * them, so the edge pixel is repeated into the margin -- which keeps the phase and the voltage
     * agreeing with each other. Clamping the window instead, so that it reads sample zero six times
     * over, does not: it feeds six samples of one voltage to six different phases of the carrier,
     * and invents a column of colour down the side of the picture that is in no signal.
     */
    private static final int MARGIN = SAMPLES_PER_PIXEL;

    /**
     * How many samples of the border to run the slew over before the line starts. Two colour
     * cycles, against a filter that forgets in about five samples.
     */
    private static final int SETTLING_SAMPLES = 2 * SAMPLES_PER_CYCLE;

    /**
     * One scanline of signal as three running totals: the levels, and the levels against each of
     * the two carriers. Rebuilt for each line rather than kept for the frame, so that six thousand
     * floats stay in cache where a million and a half would not.
     * <p>
     * Totals rather than the samples themselves because every one of the three sums a twelve sample
     * window, the windows of neighbouring pixels overlap by four, and a window of a running total
     * is the value at one end less the value at the other. So a pixel costs three subtractions
     * instead of thirty six multiply-adds, and the whole decode becomes one pass along the line
     * rather than one pass per pixel.
     */
    private final float[] totalY = new float[SAMPLES_PER_LINE + 2 * MARGIN + 1];
    private final float[] totalU = new float[totalY.length];
    private final float[] totalV = new float[totalY.length];

    private final int[] pixels = new int[WIDTH * HEIGHT];

    /**
     * Decodes a whole frame.
     *
     * @param frame      a frame of colour indices, {@link PPU#getFrameBuffer()}. Read, never kept.
     * @param framePhase where the frame sits in the three alignment cycle,
     *                   {@link PPU#getFramePhase()}.
     * @return 256x240 packed ARGB, owned by this filter and overwritten by the next call.
     */
    public int[] colourise(final int[] frame, final int framePhase) {
        for (var y = 0; y < HEIGHT; y++) {
            // Each line drifts by a third of a cycle, and the frame's own drift is the offset the
            // whole picture starts from. Both counted in phase steps, so one multiply converts.
            var linePhase = ((framePhase + y) * SAMPLES_PER_PHASE) % SAMPLES_PER_CYCLE;

            modulate(frame, y * WIDTH, linePhase);
            demodulate(y * WIDTH);
        }

        return pixels;
    }

    /**
     * Draws one scanline of the composite waveform, eight samples to a pixel, and slews it.
     * <p>
     * The slew is the one part of this that is not a square wave. The chip's output impedance
     * depends on the level it is driving, so a bright edge takes longer to arrive than a dark one,
     * and chroma -- being the high frequency half of the signal -- comes out rotated by the delay.
     * That is why the hue of a colour drifts with its row on real hardware, and why leaving it out
     * puts every colour here about seven degrees away from every measured palette. Approximated the
     * way NESdev approximates it: a one pole low pass whose coefficient is a function of the input
     * voltage, which is sixteen distinct values and so a table rather than a division per sample.
     */
    private void modulate(final int[] frame, final int row, final int linePhase) {
        // The margin runs from a pixel before the picture to a pixel after it, so the phase starts
        // one pixel's worth of samples early.
        var phase = Math.floorMod(linePhase - MARGIN, SAMPLES_PER_CYCLE);
        var slewed = settle(frame[row] * SAMPLES_PER_CYCLE, phase);
        var y = 0f;
        var u = 0f;
        var v = 0f;
        var s = 0;

        totalY[0] = 0;
        totalU[0] = 0;
        totalV[0] = 0;

        for (var x = -1; x <= WIDTH; x++) {
            var entry = frame[row + Math.clamp(x, 0, WIDTH - 1)] * SAMPLES_PER_CYCLE;

            for (var p = 0; p < SAMPLES_PER_PIXEL; p++) {
                var sample = entry + phase;

                slewed += ALPHA[sample] * (VOLTAGE[sample] - slewed);

                var level = (slewed - BLACK) * SCALE;

                y += level;
                u += level * SIN[phase];
                v += level * COS[phase];

                s++;
                totalY[s] = y;
                totalU[s] = u;
                totalV[s] = v;

                if (++phase == SAMPLES_PER_CYCLE) {
                    phase = 0;
                }
            }
        }
    }

    /**
     * Where the slew has got to by the time the line starts.
     * <p>
     * The filter has a memory of about five samples, and the first pixel's window opens two samples
     * into the margin -- so starting it at whatever voltage happens to be first leaves an
     * unsettled couple of samples inside the picture, and a flat field comes out with a column down
     * the side of it that is a unit or two off the rest. A real scanline has fifteen pixels of
     * border in front of the picture and has long since settled; two colour cycles of the leading
     * pixel is enough to stand in for them.
     */
    private static float settle(final int entry, final int startPhase) {
        var phase = Math.floorMod(startPhase - SETTLING_SAMPLES, SAMPLES_PER_CYCLE);
        var slewed = VOLTAGE[entry + phase];

        for (var i = 0; i < SETTLING_SAMPLES; i++) {
            var sample = entry + phase;

            slewed += ALPHA[sample] * (VOLTAGE[sample] - slewed);
            phase = (phase + 1) % SAMPLES_PER_CYCLE;
        }

        return slewed;
    }

    /**
     * Reads one scanline of waveform back as colours: a twelve sample box filter for luma, and the
     * same twelve against the two carriers for the colour difference signals.
     * <p>
     * Twelve samples for a pixel that is only eight wide is the whole point rather than a rounding:
     * it is the four samples of overlap that carry a neighbour's hue into this pixel. The window
     * never has to be clipped, because {@link #MARGIN} has already put a pixel of signal either
     * side of the picture for it to reach into.
     */
    private void demodulate(final int row) {
        var first = MARGIN - SAMPLES_PER_CYCLE / 2;

        for (var x = 0; x < WIDTH; x++) {
            var last = first + SAMPLES_PER_CYCLE;

            pixels[row + x] = rgb(
                    totalY[last] - totalY[first],
                    totalU[last] - totalU[first],
                    totalV[last] - totalV[first]);

            first += SAMPLES_PER_PIXEL;
        }
    }

    private static int rgb(final float y, final float u, final float v) {
        return 0xFF000000
                | channel(y + R_V * v) << 16
                | channel(y + G_U * u + G_V * v) << 8
                | channel(y + B_U * u);
    }

    /**
     * One RGB channel as a byte. Decoding a signal that was never encoded from RGB in the first
     * place regularly lands outside the gamut, so the clamp is load bearing rather than defensive.
     */
    private static int channel(final float value) {
        return Math.clamp(Math.round(value * 255f), 0, 255);
    }

    /**
     * What the chip puts on the wire for a given pixel value at a given phase.
     * <p>
     * Colour $xY is a square wave that is high for the six phases starting at Y, so the hue is
     * <em>when</em> the wave is high and the level is how high. The three exceptions are the ones
     * that make the greys grey: colour 0 is high throughout, colours 13 to 15 are low throughout,
     * and 14 and 15 are forced to level 1 whatever the top two bits say -- which is why every row
     * of the palette ends in the same black.
     *
     * @param pixel a framebuffer entry, {@code emphasis << 6 | level << 4 | colour}.
     * @param phase 0 to 11.
     */
    private static float signal(final int pixel, final int phase) {
        var colour = pixel & 0x0F;
        var level = (pixel >> 4) & 0x03;
        var emphasis = (pixel >> 6) & 0x07;

        if (colour > 13) {
            level = 1;
        }

        // The attenuator is shared, so any one of the three bits switches it on for its own six
        // phases. It cannot touch $xE or $xF, which is measured rather than obvious.
        var attenuated = colour < 0x0E
                && (((emphasis & 1) != 0 && inColourPhase(0x0C, phase))
                || ((emphasis & 2) != 0 && inColourPhase(0x04, phase))
                || ((emphasis & 4) != 0 && inColourPhase(0x08, phase)));

        var offset = level + (attenuated ? ATTENUATED : 0);
        var low = LEVELS[offset];
        var high = LEVELS[offset + 4];

        if (colour == 0) {
            low = high;
        } else if (colour > 12) {
            high = low;
        }

        return inColourPhase(colour, phase) ? high : low;
    }

    /**
     * Whether the square wave numbered {@code colour} is in its high half at this phase.
     */
    private static boolean inColourPhase(final int colour, final int phase) {
        return (colour + phase) % SAMPLES_PER_CYCLE < SAMPLES_PER_CYCLE / 2;
    }

    private static float[] voltageTable() {
        var table = new float[512 * SAMPLES_PER_CYCLE];

        for (var pixel = 0; pixel < 512; pixel++) {
            for (var phase = 0; phase < SAMPLES_PER_CYCLE; phase++) {
                table[pixel * SAMPLES_PER_CYCLE + phase] = signal(pixel, phase);
            }
        }

        return table;
    }

    /**
     * The low pass coefficient for each voltage in {@link #VOLTAGE}, which is what makes the slew
     * in {@link #modulate} a multiply rather than a division. Higher voltages give a smaller
     * coefficient and so a slower edge, which is the whole of the effect.
     */
    private static float[] alphaTable() {
        var table = new float[VOLTAGE.length];

        for (var i = 0; i < table.length; i++) {
            var constant = VOLTAGE[i] / WHITE * DISTORTION;

            table[i] = (float) (SAMPLE_SECONDS / (constant + SAMPLE_SECONDS));
        }

        return table;
    }

    private static float[] carrier(final boolean sine) {
        var table = new float[SAMPLES_PER_CYCLE];

        for (var phase = 0; phase < table.length; phase++) {
            // Three twelfths of a cycle for the quarter turn between the reference and U, less the
            // half twelfth that the burst itself sits away from it.
            var angle = Math.PI * (phase + 2.5) / 6;

            table[phase] = (float) (2 * (sine ? Math.sin(angle) : Math.cos(angle)));
        }

        return table;
    }
}
