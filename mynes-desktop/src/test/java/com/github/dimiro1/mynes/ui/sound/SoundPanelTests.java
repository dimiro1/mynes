package com.github.dimiro1.mynes.ui.sound;

import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five rows, the meters and the two ways of drawing what came out.
 * <p>
 * The half worth asserting on is the chip's rather than the panel's: that a voice's own trace is
 * that voice's, which is what makes drawing them separately worth anything. A square wave and a
 * triangle are recognisable at a glance and unrecognisable in their sum, and the way for that to be
 * wrong is for every trace to be the same array.
 */
class SoundPanelTests {
    /**
     * A frame of NTSC sound, which is what the runner hands over and what the chip is asked for.
     */
    private static final int FRAME = 735;

    private static NES nes;

    @BeforeAll
    static void machine() {
        nes = new NES(Cart.load(rom(), "sound-panel.nes"));
        nes.getAPU().setPeakTracking(true);

        // Far enough for the cartridge below to have started both pulses and the triangle, and for
        // a frame of each of them to be in the chip's trace.
        for (var i = 0; i < 80_000; i++) {
            nes.tick();
        }
    }

    @Test
    void thePanelDrawsTheFiveVoicesWithoutADisplay() {
        var panel = new SoundPanel();

        panel.show(readout());
        Views.paint(panel);
    }

    /**
     * Split on, which is the whole of the feature: five more traces appear and each is given its
     * own voice's samples.
     */
    @Test
    void splittingTheVoicesDrawsOneTraceEach() {
        var panel = new SoundPanel();
        // By label rather than by being the first one found: there are two ticks on this panel now
        // and which of them comes first is a layout decision that has already changed once.
        var split = box(panel, "Split the voices");

        assertNotNull(split);

        panel.show(readout());
        Views.paint(panel);

        split.doClick();

        panel.show(readout());
        Views.paint(panel);
    }

    /**
     * The claim under the picture: each voice's trace is its own. Two channels playing different
     * notes at different volumes cannot come back as the same samples unless something is handing
     * out one array five times.
     */
    @Test
    void everyVoiceHasItsOwnTrace() {
        var readout = readout();
        var one = readout.trace(APUChannel.PULSE_1);
        var triangle = readout.trace(APUChannel.TRIANGLE);

        assertTrue(loudest(one) > 0, "pulse 1 is playing");
        assertTrue(loudest(triangle) > 0, "and so is the triangle");
        assertFalse(
                java.util.Arrays.equals(one, triangle),
                "and they are not the same samples");
    }

    /**
     * A machine nobody has asked to be watched records nothing, which is what keeps the mixer's
     * hottest line to one null check.
     */
    @Test
    void aChipNobodyIsWatchingRecordsNothing() {
        var quiet = new NES(Cart.load(rom(), "sound-panel.nes"));

        for (var i = 0; i < 80_000; i++) {
            quiet.tick();
        }

        var trace = new short[FRAME];

        quiet.getAPU().trace(APUChannel.PULSE_1, trace, trace.length);

        assertTrue(loudest(trace) == 0, "nothing was kept");
    }

    /**
     * One keyboard per voice by default, and one for all of them on a tick -- the same shape the
     * traces below have, and for the same reason: separate parts are easier to follow, together is
     * where an octave, a third and a semitone of accidental dissonance stop looking alike.
     */
    @Test
    void theKeyboardsAreOneEachUntilSomebodyAsksForOne() {
        var panel = new SoundPanel();

        panel.show(readout());
        Views.paint(panel);

        var keyboards = all(panel, Piano.class);

        assertEquals(4, keyboards.size(), "three voices with a keyboard, plus the one they share");
        assertEquals(
                3,
                keyboards.stream().filter(java.awt.Component::isVisible).count(),
                "one each to begin with");

        var one = box(panel, "All three on one keyboard");

        assertNotNull(one);
        one.doClick();

        assertEquals(
                1,
                keyboards.stream().filter(java.awt.Component::isVisible).count(),
                "and one between them after that");

        Views.paint(panel);
    }

    private static JCheckBox box(final java.awt.Container root, final String label) {
        for (var found : all(root, JCheckBox.class)) {
            if (label.equals(found.getText())) {
                return found;
            }
        }

        return null;
    }

    private static <T> java.util.List<T> all(final java.awt.Container root, final Class<T> type) {
        var found = new java.util.ArrayList<T>();

        for (var child : root.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }

            if (child instanceof java.awt.Container inner) {
                found.addAll(all(inner, type));
            }
        }

        return found;
    }

    private static Readout readout() {
        var traces = new ArrayList<short[]>();

        for (var channel : APUChannel.values()) {
            var trace = new short[FRAME];

            nes.getAPU().trace(channel, trace, trace.length);
            traces.add(trace);
        }

        return Readout.of(
                nes,
                new short[FRAME],
                List.copyOf(traces),
                Readout.Pads.NONE,
                Readout.Events.NONE);
    }

    private static int loudest(final short[] samples) {
        var top = 0;

        for (var sample : samples) {
            top = Math.max(top, Math.abs(sample));
        }

        return top;
    }

    /**
     * A cartridge that starts both pulses and the triangle at different periods and volumes, then
     * spins -- so every trace has something in it and no two of them are alike.
     *
     * <pre>
     * 8000  A9 0F     LDA #$0F     enable all four length-counter channels
     * 8002  8D 15 40  STA $4015
     * 8005  A9 BF     LDA #$BF     pulse 1: 50% duty, constant volume 15
     * 8007  8D 00 40  STA $4000
     * 800A  A9 FD     LDA #$FD
     * 800C  8D 02 40  STA $4002    period 253, which is an A above middle C
     * 800F  A9 08     LDA #$08
     * 8011  8D 03 40  STA $4003
     * 8014  A9 B8     LDA #$B8     pulse 2: 50% duty, constant volume 8
     * 8016  8D 04 40  STA $4004
     * 8019  A9 7E     LDA #$7E
     * 801B  8D 06 40  STA $4006    a much longer period, so a different note
     * 801E  A9 09     LDA #$09
     * 8020  8D 07 40  STA $4007
     * 8023  A9 FF     LDA #$FF     triangle: linear counter loaded and held
     * 8025  8D 08 40  STA $4008
     * 8028  A9 40     LDA #$40
     * 802A  8D 0A 40  STA $400A
     * 802D  A9 09     LDA #$09
     * 802F  8D 0B 40  STA $400B
     * 8032  4C 32 80  JMP $8032
     * </pre>
     */
    private static byte[] rom() {
        var code = new int[]{
                0xA9, 0x0F, 0x8D, 0x15, 0x40,
                0xA9, 0xBF, 0x8D, 0x00, 0x40,
                0xA9, 0xFD, 0x8D, 0x02, 0x40,
                0xA9, 0x08, 0x8D, 0x03, 0x40,
                0xA9, 0xB8, 0x8D, 0x04, 0x40,
                0xA9, 0x7E, 0x8D, 0x06, 0x40,
                0xA9, 0x09, 0x8D, 0x07, 0x40,
                0xA9, 0xFF, 0x8D, 0x08, 0x40,
                0xA9, 0x40, 0x8D, 0x0A, 0x40,
                0xA9, 0x09, 0x8D, 0x0B, 0x40,
                0x4C, 0x32, 0x80,
        };

        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        for (var i = 0; i < code.length; i++) {
            image[16 + i] = (byte) code[i];
        }

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
