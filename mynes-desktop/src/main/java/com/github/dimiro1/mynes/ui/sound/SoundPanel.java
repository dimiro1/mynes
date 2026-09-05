package com.github.dimiro1.mynes.ui.sound;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import net.miginfocom.swing.MigLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;
import java.util.EnumMap;
import java.util.Map;

/**
 * What the five voices are doing, one row each, and the wave they add up to.
 * <p>
 * The APU is the half of the machine with no picture, which is why a sound bug is the hardest kind
 * to chase: a note that will not stop, a channel that never starts, music a semitone flat. None of
 * that is visible anywhere and all of it is in eleven registers.
 * <p>
 * So each row says the same thing in the three ways somebody might be thinking about it: the period
 * the game <em>wrote</em>, which is what a watchpoint catches; the frequency that comes out of it on
 * the console it is running on; and the note that is nearest, with how far off it is. A game whose
 * bass line drifts flat as it descends is doing exactly that, and it is invisible without the last
 * two.
 * <p>
 * Fed from a {@link Readout} at a frame boundary, four times a second, like everything else here.
 * The meters are peaks over that quarter second rather than levels: a level sampled four times a
 * second from a wave oscillating hundreds of times a second is a random number.
 */
public final class SoundPanel extends JPanel {
    private static final String NOTHING = "—";

    /**
     * The four duty cycles, in the order $4000's top two bits select them. The last is the second
     * inverted, which is the same wave a quarter of a period along -- so it is named for what it is
     * rather than for its ratio, which would be a second "25%" nobody could tell from the first.
     */
    private static final String[] DUTIES = {"12.5%", "25%", "50%", "25% inv"};

    private final Map<APUChannel, Row> rows = new EnumMap<>(APUChannel.class);
    private final Scope scope = new Scope();

    public SoundPanel() {
        setLayout(new MigLayout(
                "insets 10, wrap 8, gapx 14, gapy 3",
                "[70][][58!][62!][54!][34!][30!][grow,fill]",
                ""));

        add(Theme.heading(""));
        add(Theme.heading(""));
        add(Theme.heading("note"));
        add(Theme.heading("hertz"));
        add(Theme.heading("period"));
        add(Theme.heading("len"));
        add(Theme.heading("vol"));
        add(Theme.heading(""));

        for (var channel : APUChannel.values()) {
            var row = new Row(channel);

            rows.put(channel, row);
            row.addTo(this);
        }

        add(Theme.heading("Output"), "newline, gaptop 14, span 8");
        add(scope, "span 8, growx, h 96!");
    }

    /**
     * The machine as it stood when a frame finished.
     */
    public void show(final Readout readout) {
        for (var channel : APUChannel.values()) {
            rows.get(channel).show(readout.voice(channel), readout.peak(channel));
        }

        scope.show(readout.scope());
    }

    /**
     * One voice. The columns every channel has are filled the same way for all five; what only one
     * of them has -- a duty cycle, a shift mode, a sample address -- goes in the last column, which
     * is why that one is a sentence rather than a number.
     */
    private static final class Row {
        private final APUChannel channel;

        private final JLabel name = new JLabel();
        private final Meter meter = new Meter();
        private final JLabel note = value();
        private final JLabel hertz = value();
        private final JLabel period = value();
        private final JLabel length = value();
        private final JLabel volume = value();
        private final JLabel detail = value();

        private Row(final APUChannel channel) {
            this.channel = channel;

            name.setText(channel.label());
        }

        private void addTo(final JPanel panel) {
            panel.add(name);
            panel.add(meter);
            panel.add(note);
            panel.add(hertz);
            panel.add(period);
            panel.add(length);
            panel.add(volume);
            panel.add(detail);
        }

        private void show(final APU.VoiceState voice, final double peak) {
            meter.setLevel(peak);

            // A voice with nothing left to play is dimmed rather than blanked: what it was last
            // asked to play is often the answer to why it stopped.
            var colour = voice.playing() ? Theme.foreground() : Theme.muted();

            for (var label : new JLabel[]{name, note, hertz, period, length, volume, detail}) {
                label.setForeground(colour);
            }

            note.setText(noteOf(voice));
            hertz.setText(voice.hertz() > 0 ? String.format("%.1f", voice.hertz()) : NOTHING);
            period.setText(String.format("$%03X", voice.period()));
            length.setText(channel == APUChannel.DMC ? NOTHING : Integer.toString(voice.length()));
            volume.setText(voice.volume() < 0 ? NOTHING : Integer.toString(voice.volume()));
            detail.setText(detailOf(voice));
        }

        /**
         * The nearest note and how far off it is, for the three channels that make one. The noise
         * makes a rate rather than a pitch and the DMC plays whatever was sampled, so neither has a
         * note to name.
         */
        private String noteOf(final APU.VoiceState voice) {
            if (channel == APUChannel.NOISE || channel == APUChannel.DMC || voice.hertz() <= 0) {
                return NOTHING;
            }

            var named = Notes.nameOf(voice.hertz());

            if (named == null) {
                return NOTHING;
            }

            var cents = Notes.centsOff(voice.hertz());

            return cents == 0 ? named : String.format("%s %+d", named, cents);
        }

        /**
         * The thing only this channel has.
         */
        private String detailOf(final APU.VoiceState voice) {
            return switch (channel) {
                case PULSE_1, PULSE_2 -> DUTIES[voice.duty()]
                        + (voice.constant() ? "  constant" : "  envelope")
                        + (voice.loop() ? "  loop" : "");
                // The linear counter, which gates the triangle as surely as its length counter
                // does and is the usual answer to a triangle that has gone quiet with a length
                // still loaded.
                case TRIANGLE -> "linear " + voice.linear() + (voice.loop() ? "  control" : "");
                case NOISE -> (voice.shortMode() ? "short" : "long")
                        + (voice.constant() ? "  constant" : "  envelope")
                        + (voice.loop() ? "  loop" : "");
                case DMC -> String.format(
                        "$%04X  %d bytes%s",
                        voice.address(), voice.bytesLeft(), voice.loop() ? "  loop" : "");
            };
        }

        private static JLabel value() {
            var label = new JLabel(NOTHING);

            label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

            return label;
        }
    }
}
