package com.github.dimiro1.mynes.ui.pads;

import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import net.miginfocom.swing.MigLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;

/**
 * Both pads as the game sees them, and how often it has bothered to look.
 * <p>
 * Two questions, and the second is the reason this is an instrument rather than a line on the
 * dashboard.
 * <p>
 * <b>What is held</b> is the easy half, and it is still worth drawing: a pad that shows nothing
 * while somebody is pressing keys separates a controller mapping that is wrong from a game that is
 * ignoring the pad, which is the first fork in every "the controls do not work" investigation. The
 * <em>poll</em> counts beside it are what settle it -- a game polling once a frame with nothing
 * held is a game reading a pad nobody is pressing, and a game polling not at all has stopped
 * asking.
 * <p>
 * <b>How often it looks</b> is the half nothing else can answer. A game reads $4016 once a frame,
 * so a frame that went by with no poll in it is a frame whose main loop did not finish in time --
 * the game skipped a turn and drew the same picture again. That is invisible in every other view
 * here: the screen is a picture, the sound carries on, and the frame counter keeps counting. It is
 * also exactly what the Overclock hack in the column beside this exists to undo, so the strip is
 * the before and after for it.
 * <p>
 * <b>The two ports are told apart by their reads rather than by their polls.</b> One write to $4016
 * latches both, so both pads always show the same number of polls; what a one player game does not
 * do is clock any bits out of $4017. A second pad with polls and no bits is a game that never looks
 * at player two, which is the answer to why a second controller does nothing.
 */
public final class PadsPanel extends JPanel {
    private static final String NOTHING = "—";

    /**
     * The eight buttons in the order a controller shifts them out, so that what is written here and
     * what a game reads are the same list.
     */
    private static final String[] BUTTONS = {
            "A", "B", "Select", "Start", "Up", "Down", "Left", "Right",
    };

    private final Pad one = new Pad();
    private final Pad two = new Pad();

    // Given the whole window's width up front rather than however many frames have been counted so
    // far, since a box that grew as it filled would move everything under it four times a second.
    private final LagStrip strip = new LagStrip(Readout.Pads.WINDOW);
    private final JLabel lag = value();

    public PadsPanel() {
        setLayout(new MigLayout("insets 10, gapy 3, wrap 1", "[]", ""));

        add(Theme.heading("Player one"));
        one.addTo(this);

        add(Theme.heading("Player two"), "gaptop 16");
        two.addTo(this);

        add(Theme.heading("Reading the pad"), "gaptop 20");
        add(Theme.note("a frame with no $4016 read in it is a frame whose main loop did not finish"));
        add(strip, "gaptop 6, h " + strip.getPreferredSize().height + "!");
        add(lag, "gaptop 2");
    }

    /**
     * The machine as it stood when a frame finished.
     */
    public void show(final Readout readout) {
        var pads = readout.pads();

        one.show(readout.pad1(), pads.polls1(), pads.bits1());
        two.show(readout.pad2(), pads.polls2(), pads.bits2());

        strip.setPolled(pads.polled());
        lag.setText(describeLag(pads));
    }

    /**
     * What there is to say when nobody has been counting: that nobody has been counting, rather
     * than a game that never lagged. A readout taken anywhere but the emulation loop -- the
     * debugger's stop snapshot, the README's camera -- carries no window at all.
     */
    private static String describeLag(final Readout.Pads pads) {
        if (pads.frames() == 0) {
            return "nothing counted yet";
        }

        if (pads.lagFrames() == 0) {
            return "every one of the last " + pads.frames() + " frames was read";
        }

        return pads.lagFrames() + " of the last " + pads.frames()
                + " frames went by without one";
    }

    /**
     * One controller: the picture, what it is holding spelled out, and the two counts.
     * <p>
     * The buttons are named beside the drawing as well as lit on it, because a screenshot of a
     * debug window is something people paste into a bug report and a green rectangle is not
     * something they can type.
     */
    private static final class Pad {
        private final PadView view = new PadView();
        private final JLabel held = value();
        private final JLabel polls = value();
        private final JLabel bits = value();

        void addTo(final JPanel parent) {
            // The three facts in a panel of their own rather than as three rows beside the picture,
            // which is what keeps them together: a row spanning the height of the drawing is spread
            // over it, and three lines a finger apart read as three separate things.
            var facts = new JPanel(
                    new MigLayout("insets 0, gapx 14, gapy 2, wrap 2", "[70][]", ""));

            facts.add(new JLabel("held"));
            facts.add(held);
            facts.add(new JLabel("polls"));
            facts.add(polls);
            facts.add(new JLabel("bits read"));
            facts.add(bits);

            var row = new JPanel(new MigLayout("insets 0, gapx 26", "", ""));

            row.add(view, "aligny top");
            row.add(facts, "aligny top");

            parent.add(row);
        }

        void show(final int buttons, final int polled, final int read) {
            view.setHeld(buttons);
            held.setText(name(buttons));
            polls.setText(perFrame(polled));
            bits.setText(perFrame(read));
        }

        /**
         * A count for the frame that has just finished, said as the rate it is: what somebody wants
         * to know is whether the game is polling once a frame, not what one particular frame did.
         */
        private static String perFrame(final int count) {
            return count == 0 ? NOTHING : count + " in the last frame";
        }

        private static String name(final int buttons) {
            var text = new StringBuilder();

            for (var bit = 0; bit < BUTTONS.length; bit++) {
                if ((buttons & (1 << bit)) != 0) {
                    text.append(text.isEmpty() ? "" : " ").append(BUTTONS[bit]);
                }
            }

            return text.isEmpty() ? NOTHING : text.toString();
        }
    }

    private static JLabel value() {
        var label = new JLabel(NOTHING);

        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        return label;
    }
}
