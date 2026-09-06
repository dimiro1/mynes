package com.github.dimiro1.mynes.ui.sound;

import com.github.dimiro1.mynes.APUChannel;

import java.awt.Color;

/**
 * A colour per voice, for the split scope.
 * <p>
 * Five hues rather than five shades, because what the colour has to do is tell one trace from
 * another at a glance -- and they are ordered the way the rows above them are, so that the trace
 * and its numbers are found by the same eye movement.
 * <p>
 * The two pulses are deliberately near neighbours: they are the same kind of voice and a game
 * usually has them playing the same kind of part, so a pair that looked unrelated would be the
 * wrong story. The triangle is the bass, the noise is the percussion, and the DMC is whatever was
 * sampled -- three different things, three unrelated hues.
 */
final class Traces {
    private static final Color[] COLOURS = {
            new Color(0x1A7F37),  // pulse 1, the green everything else here uses for "going"
            new Color(0x2DA44E),  // pulse 2, the same green a shade lighter
            new Color(0x0550AE),  // triangle, the blue the disassembly uses for an address
            new Color(0x953800),  // noise, the orange it uses for an immediate
            new Color(0x8250DF),  // DMC, the purple it uses for a branch
    };

    private Traces() {
    }

    static Color colourOf(final APUChannel channel) {
        return COLOURS[channel.ordinal()];
    }

    /**
     * The loudest a voice can be, which is what its own trace is drawn against.
     * <p>
     * Each against its own rather than all against the same, because they are not on one scale: the
     * four that come off a sequencer or an envelope run 0 to 15 and the DMC's level is seven bits.
     * Drawn against a shared scale the DMC would be the only one visible.
     */
    static double fullScaleOf(final APUChannel channel) {
        return channel == APUChannel.DMC ? 127 : 15;
    }
}
