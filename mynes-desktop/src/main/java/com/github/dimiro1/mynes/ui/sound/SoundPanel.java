package com.github.dimiro1.mynes.ui.sound;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.Nullable;

import javax.swing.JCheckBox;
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

    /**
     * How tall one voice's own trace is. Shorter than the mixed one below them, because what a
     * split view is for is telling five shapes apart rather than measuring any of them.
     */
    private static final int VOICE_HEIGHT = 40;

    private final Map<APUChannel, Row> rows = new EnumMap<>(APUChannel.class);
    private final Map<APUChannel, Scope> traces = new EnumMap<>(APUChannel.class);
    private final Map<APUChannel, JLabel> names = new EnumMap<>(APUChannel.class);

    private final JCheckBox split = new JCheckBox("Split the voices");
    private final Scope scope = new Scope();

    /**
     * Three keyboards and the one they share, all four built and only the wanted ones shown --
     * which is how the traces below work, and for the same reason: a keyboard that was rebuilt
     * every time the tick moved would come back with no trail behind it.
     */
    private final Piano together = new Piano(Piano.PITCHED);
    private final Map<APUChannel, Piano> apart = new EnumMap<>(APUChannel.class);
    private final Map<APUChannel, JLabel> keyboardNames = new EnumMap<>(APUChannel.class);

    private final JCheckBox oneKeyboard = new JCheckBox("All four on one keyboard");

    /**
     * The last frame handed over, kept so that ticking Split draws the five traces out of it
     * straight away. Without it they would come up empty and stay that way until the next readout
     * -- which on a machine somebody has stopped in order to look at is never.
     */
    private @Nullable Readout last;

    public SoundPanel() {
        // hidemode 3 because the five voice traces below are built hidden and shown by a tick, and
        // MigLayout's default keeps the room an invisible component asked for -- which left a hand's
        // depth of nothing between the Output row and the scope on every panel where Split was off,
        // which is every panel by default.
        setLayout(new MigLayout(
                "insets 10, wrap 8, gapx 14, gapy 3, hidemode 3",
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

        // The three voices that have a pitch, put where a musician would look for them. The rows
        // above already name the notes; what this adds is how they stand to each other, which is
        // the question a column of names cannot answer -- an octave, a third, or a semitone of
        // accidental dissonance all look the same written down.
        add(Theme.heading("Notes"), "newline, gaptop 14, span 8, split 3");
        add(
                Theme.note("A0 to C8, which is the range the chip has -- the noise only in short"
                        + " mode, and the DMC never"),
                "gapleft 8");
        add(oneKeyboard, "gapleft 24, wrap");

        // A keyboard each, which is the default for the reason a score is written that way: a part
        // is easier to follow than a texture. The name sits in the same first column the voice rows
        // above use, so a keyboard and its numbers are found by the same eye movement.
        for (var channel : Piano.PITCHED) {
            var keyboard = new Piano(channel);
            var name = new JLabel(channel.label());

            name.setForeground(Traces.colourOf(channel));

            apart.put(channel, keyboard);
            keyboardNames.put(channel, name);

            add(name, "gaptop 4");
            add(keyboard, "span 7, growx, h " + keyboard.getPreferredSize().height + "!, wrap");
        }

        // And the one they share, for the other question: what the parts are doing to each other.
        together.setVisible(false);
        add(together, "span 8, growx, h " + together.getPreferredSize().height + "!, wrap");

        // Worth saying because the column beside this has ten sound controls in it and only five of
        // them move this line. The five voice ticks happen at the mixer inside the chip, so they
        // change what comes out; Mute and the volume happen on the way to the sound card, so they
        // change how loudly it is played and nothing about what was played. Which is also why the
        // meters above keep moving for a voice somebody has switched off -- "this is playing and
        // you cannot hear it" is a different answer from "this is not playing".
        add(Theme.heading("Output"), "newline, gaptop 14, span 8, split 3");
        add(Theme.note("what the chip makes, before Volume and Mute"), "gapleft 8");
        add(split, "gapleft 24, wrap");

        // One trace per voice above the mixed one, hidden until somebody asks. What each of them
        // shows is what that voice put *into* the mixer, where the trace below is what came out --
        // which is the whole reason to look at them separately: a square wave, a triangle, a hiss
        // and a sampled drum are recognisable at a glance where their sum is not.
        for (var channel : APUChannel.values()) {
            var name = new JLabel(channel.label());
            var trace = new Scope(
                    Traces.colourOf(channel),
                    Traces.fullScaleOf(channel),
                    VOICE_HEIGHT,
                    false);

            name.setForeground(Traces.colourOf(channel));
            name.setVisible(false);
            trace.setVisible(false);

            names.put(channel, name);
            traces.put(channel, trace);

            add(name, "span 8, split 2, w 70!");
            add(trace, "growx, h " + VOICE_HEIGHT + "!, wrap");
        }

        add(scope, "span 8, growx, h 96!");

        split.setToolTipText("Draw each voice on its own as well as the sum of them");
        split.addActionListener(e -> showSplit(split.isSelected()));

        oneKeyboard.setToolTipText(
                "Put all four voices on one keyboard, where what they are doing to each other --"
                        + " an octave, a third, a semitone of accidental dissonance -- is visible");
        oneKeyboard.addActionListener(e -> showOneKeyboard(oneKeyboard.isSelected()));
    }

    /**
     * One keyboard or three. A question about this panel rather than about the machine, so nothing
     * is asked of anything: all four are already up to date, and this only decides which are up.
     */
    private void showOneKeyboard(final boolean one) {
        together.setVisible(one);

        for (var channel : Piano.PITCHED) {
            apart.get(channel).setVisible(!one);
            keyboardNames.get(channel).setVisible(!one);
        }

        revalidate();
        repaint();
    }

    /**
     * Shows or hides the five, which is a question about this panel rather than about the machine
     * -- so it is a tick here rather than a switch in the column, the way the nametable view's grid
     * is.
     */
    private void showSplit(final boolean showing) {
        for (var channel : APUChannel.values()) {
            names.get(channel).setVisible(showing);
            traces.get(channel).setVisible(showing);
        }

        if (showing && last != null) {
            drawTheVoices(last);
        }

        revalidate();
        repaint();
    }

    private void drawTheVoices(final Readout readout) {
        for (var channel : APUChannel.values()) {
            traces.get(channel).show(readout.trace(channel));
        }
    }


    /**
     * The machine as it stood when a frame finished.
     */
    public void show(final Readout readout) {
        for (var channel : APUChannel.values()) {
            rows.get(channel).show(readout.voice(channel), readout.peak(channel));
        }

        scope.show(readout.scope());
        together.show(readout);

        for (var keyboard : apart.values()) {
            keyboard.show(readout);
        }

        last = readout;

        if (split.isSelected()) {
            drawTheVoices(readout);
        }
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
         * The nearest note and how far off it is, for whichever channels have one.
         * <p>
         * Asked of {@link APU.VoiceState#pitch()} rather than of the frequency beside it, which is
         * the one place on this row where the two are different numbers: the noise's hertz is how
         * fast its shift register is clocked, and only in short mode does that divide down into
         * something with a pitch. The DMC never has one.
         */
        private String noteOf(final APU.VoiceState voice) {
            var pitch = voice.pitch();

            if (pitch <= 0) {
                return NOTHING;
            }

            var named = Notes.nameOf(pitch);

            if (named == null) {
                return NOTHING;
            }

            var cents = Notes.centsOff(pitch);

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
