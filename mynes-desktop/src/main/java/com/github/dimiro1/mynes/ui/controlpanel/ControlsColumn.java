package com.github.dimiro1.mynes.ui.controlpanel;

import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.ui.Commands;
import com.github.dimiro1.mynes.ui.Switches;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import net.miginfocom.swing.MigLayout;

import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;

/**
 * Every lever on the machine, laid out flat down the side of the panel.
 * <p>
 * The Machine, Debug, Hacks and Settings menus each hold a few of these and none of them holds all,
 * which is the thing the control panel is for: watching a game slow down and reaching for the
 * overclock is two menus and a submenu today, and one glance at a column here. So the switches are
 * grouped by what they are about rather than by which menu they happen to live in.
 * <p>
 * <b>Not a tab.</b> A control panel whose controls can be hidden behind another tab is not one --
 * so this is a fixed column, and the instruments beside it are what change.
 * <p>
 * Nothing here holds any state or does anything. Every control is built from the
 * {@link Switches.Toggle}, {@link Switches.Choice} or {@link Commands.Command} the menu bar's own
 * item is built from, so the two cannot disagree, greying either greys both, and this class needs
 * no listeners at all. Which is also why it takes no machine: it never touches one.
 */
final class ControlsColumn extends JPanel {
    /**
     * How far the settings under a switch are indented from it, which is what says they belong to
     * it: the speeds are a setting on Fast Forward rather than a fifth thing to switch on.
     */
    private static final String UNDER = "gapleft 16";

    private static final String GROUP = "gaptop 12";

    ControlsColumn(final Switches switches, final Commands commands) {
        setLayout(new MigLayout("insets 10 12 10 12, wrap 1, gapy 1, fillx", "[grow,fill]", ""));

        add(Theme.heading("Machine"));
        add(new JCheckBox(switches.pause()));
        add(new JCheckBox(switches.fastForward()));
        add(radios(switches.fastForwardSpeed()), UNDER);
        add(buttons(new JButton(commands.reset()), new JButton(commands.powerCycle())), "gaptop 6");

        add(Theme.heading("Picture"), GROUP);
        add(new JCheckBox(switches.background()));
        add(new JCheckBox(switches.sprites()));
        add(new JCheckBox(switches.unlimitedSprites()));

        // Two headings rather than one, because these are two different kinds of thing and the
        // Sound tab beside them makes the difference visible: Mute and the volume are how loudly
        // the machine is played, and happen on the way to the sound card, so the scope's trace does
        // not move when they do. The five below are what it is playing, and happen at the mixer
        // inside the chip, so they change the trace and the numbers with it.
        add(Theme.heading("Sound"), GROUP);
        add(new JCheckBox(switches.mute()));
        add(radios(switches.volume()), UNDER);

        add(Theme.heading("Voices"), GROUP);

        for (var channel : APUChannel.values()) {
            add(new JCheckBox(switches.channel(channel)));
        }

        add(Theme.heading("Hacks"), GROUP);
        add(radios(switches.overclock()));
        add(new JButton(commands.gameGenie()), "gaptop 6");

        add(Theme.heading("Trace"), GROUP);
        add(buttons(new JButton(commands.startTrace()), new JButton(commands.stopTrace())));

        // Under its own heading rather than beside the trace, which writes down what the processor
        // did: this writes down what the sound chip played, and the two are read by different
        // people in different programs.
        add(Theme.heading("Music"), GROUP);
        add(buttons(new JButton(commands.startMusic()), new JButton(commands.stopMusic())));

    }

    /**
     * One choice as a row of radio buttons that wraps rather than as a combo box, because a combo
     * box cannot be given an Action and so could not be the same choice the menu is showing. They
     * flow across the column: five volumes are two short rows here and five rows in a menu.
     */
    private static <E> JComponent radios(final Switches.Choice<E> choice) {
        var row = new JPanel(new MigLayout("insets 0, gapx 6, gapy 0, flowx, wrap 3", "[]", "[]"));
        var group = new ButtonGroup();

        for (var value : choice.values()) {
            var button = new JRadioButton(choice.option(value));

            group.add(button);
            row.add(button);
        }

        return row;
    }

    private static JComponent buttons(final JButton first, final JButton second) {
        var row = new JPanel(new MigLayout("insets 0, gapx 6", "[grow,fill][grow,fill]", "[]"));

        row.add(first);
        row.add(second);

        return row;
    }
}
