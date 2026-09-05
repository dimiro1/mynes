package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.junit.jupiter.api.Test;

import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one thing switches are for: two places showing the same switch, and neither of them holding
 * it.
 * <p>
 * Everything here builds real Swing controls without a display, because that is the only way to
 * test the claim -- a {@link Switches.Toggle} that agreed with itself and with nothing else would
 * pass any test written against the Toggle alone.
 */
class SwitchesTests {
    @Test
    void aTickAndACheckboxAreTheSameSwitch() {
        var switches = new Switches();
        var item = new JCheckBoxMenuItem(switches.mute());
        var box = new JCheckBox(switches.mute());

        assertFalse(item.isSelected());
        assertFalse(box.isSelected());

        item.doClick();

        assertTrue(switches.mute().isOn(), "the switch heard it");
        assertTrue(box.isSelected(), "and so did the control nobody clicked");

        box.doClick();

        assertFalse(switches.mute().isOn());
        assertFalse(item.isSelected());
    }

    @Test
    void aSwitchGreysOutEverywhereAtOnce() {
        var switches = new Switches();
        var item = new JCheckBoxMenuItem(switches.warp());
        var box = new JCheckBox(switches.warp());

        switches.warp().setEnabled(false);

        assertFalse(item.isEnabled());
        assertFalse(box.isEnabled());
    }

    /**
     * The distinction the whole thing turns on: a switch brought into line with a machine is not
     * somebody asking for anything. A new machine is running and not fast forwarding, and both
     * ticks have to say so without either being taken for a click.
     */
    @Test
    void followingIsNotTelling() {
        var switches = new Switches();
        var told = new ArrayList<Boolean>();
        var item = new JCheckBoxMenuItem(switches.pause());

        switches.pause().onChange(told::add);
        switches.pause().set(true);

        assertTrue(item.isSelected(), "the tick moved");
        assertEquals(List.of(), told, "and nothing was told about it");

        item.doClick();

        assertEquals(List.of(false), told, "where a click is");
    }

    @Test
    void aChoiceIsOneOfItsOptionsAndTheOthersFollow() {
        var switches = new Switches();
        var menu = menuOf(switches.videoFilter());

        assertSame(VideoFilter.values()[0], switches.videoFilter().get());
        assertTrue(menu.getItem(0).isSelected());

        menu.getItem(2).doClick();

        assertSame(VideoFilter.values()[2], switches.videoFilter().get());
        assertFalse(menu.getItem(0).isSelected(), "the old one let go");
    }

    /**
     * Two menus over one choice, which is what the control panel is about to be: picking in either
     * moves the dot in both, and neither of them is where the answer is kept.
     */
    @Test
    void twoPlacesShowingOneChoiceAgree() {
        var switches = new Switches();
        var here = menuOf(switches.screenSize());
        var there = menuOf(switches.screenSize());

        here.getItem(3).doClick();

        assertSame(ScreenScale.values()[3], switches.screenSize().get());
        assertTrue(there.getItem(3).isSelected(), "the other menu moved with it");
        assertFalse(there.getItem(0).isSelected());

        there.getItem(1).doClick();

        assertSame(ScreenScale.values()[1], switches.screenSize().get());
        assertTrue(here.getItem(1).isSelected(), "and back the other way");
    }

    /**
     * Picking what is already picked still counts, because whether it means anything depends on the
     * choice: clicking the size a maximized window is already at is how it is un-maximized.
     */
    @Test
    void pickingTheOptionAlreadyPickedIsStillSomebodyPicking() {
        var switches = new Switches();
        var told = new ArrayList<ScreenScale>();
        var menu = menuOf(switches.screenSize());

        switches.screenSize().onChange(told::add);

        menu.getItem(0).doClick();
        menu.getItem(0).doClick();

        assertEquals(
                List.of(ScreenScale.values()[0], ScreenScale.values()[0]),
                told,
                "both clicks arrived");
    }

    /**
     * Seeding a choice from what was remembered of the last run, which is a quiet move for the
     * reason {@link #followingIsNotTelling} gives.
     */
    @Test
    void aChoiceIsSeededWithoutTellingAnybody() {
        var switches = new Switches();
        var told = new ArrayList<Volume>();

        switches.volume().onChange(told::add);
        switches.volume().select(Volume.HALF);

        var menu = menuOf(switches.volume());

        assertSame(Volume.HALF, switches.volume().get());
        assertEquals(List.of(), told);
        assertTrue(menu.getItem(indexOf(switches.volume(), Volume.HALF)).isSelected());
    }

    /**
     * A submenu of options greys out along with them. A heading left black over a list nobody can
     * pick would open onto nothing.
     */
    @Test
    void whatIsHoldingAChoiceGreysOutWithIt() {
        var switches = new Switches();
        var menu = menuOf(switches.overclock());

        switches.overclock().shownIn(menu);
        switches.overclock().setEnabled(false);

        assertFalse(menu.isEnabled(), "the submenu");
        assertFalse(menu.getItem(0).isEnabled(), "and the items in it");

        switches.overclock().setEnabled(true);

        assertTrue(menu.isEnabled());
    }

    /**
     * One option greyed and the rest left alone, which is the NTSC decoder on a PAL machine: the
     * tick stays where it is, because the setting is somebody's preference about American
     * cartridges and a European one should not take it away.
     */
    @Test
    void oneOptionCanBeGreyedWithoutTheRest() {
        var switches = new Switches();
        var menu = menuOf(switches.videoFilter());

        switches.videoFilter().setEnabled(VideoFilter.NTSC, false);

        assertFalse(menu.getItem(indexOf(switches.videoFilter(), VideoFilter.NTSC)).isEnabled());
        assertTrue(menu.isEnabled(), "and the submenu is still worth opening");
        assertTrue(
                menu.getItem(indexOf(switches.videoFilter(), VideoFilter.NONE)).isEnabled());
    }

    /**
     * The labels, mnemonics and accelerators are the switch's rather than the menu's, so that a
     * second place showing one cannot call it something else.
     */
    @Test
    void aMenuItemTakesItsWholeAppearanceFromTheSwitch() {
        var switches = new Switches();
        var item = new JCheckBoxMenuItem(switches.pause());

        assertEquals("Pause", item.getText());
        assertEquals(KeyEvent.VK_P, item.getMnemonic());
        assertEquals(
                KeyStroke.getKeyStroke(KeyEvent.VK_P, MenuKey.mask()), item.getAccelerator());
    }

    /**
     * The seven that start on, because a machine draws and plays everything until somebody asks it
     * not to.
     */
    @Test
    void theLayersAndTheVoicesStartOn() {
        var switches = new Switches();

        assertTrue(switches.background().isOn());
        assertTrue(switches.sprites().isOn());

        for (var channel : APUChannel.values()) {
            assertTrue(switches.channel(channel).isOn(), channel.label());
        }
    }

    /**
     * The menu the window builds, built the same way here: a radio item per option and a group to
     * make them look like one choice.
     */
    private static <E> JMenu menuOf(final Switches.Choice<E> choice) {
        var menu = new JMenu();
        var group = new ButtonGroup();

        for (var value : choice.values()) {
            var item = new JRadioButtonMenuItem(choice.option(value));

            group.add(item);
            menu.add(item);
        }

        return menu;
    }

    private static <E> int indexOf(final Switches.Choice<E> choice, final E value) {
        return choice.values().indexOf(value);
    }
}
