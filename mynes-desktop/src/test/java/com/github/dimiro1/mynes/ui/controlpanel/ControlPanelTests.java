package com.github.dimiro1.mynes.ui.controlpanel;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.ui.AudioOutput;
import com.github.dimiro1.mynes.ui.Commands;
import com.github.dimiro1.mynes.ui.EmulatorRunner;
import com.github.dimiro1.mynes.ui.PauseControl;
import com.github.dimiro1.mynes.ui.ScreenComponent;
import com.github.dimiro1.mynes.ui.Switches;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The window, and the column down the side of it.
 * <p>
 * The column is a panel and is tested where there is no display, which is where the build runs.
 * The window itself is the one thing here that needs a peer, so those tests are skipped in CI --
 * which is the whole of what decision 12 gives up, and it is not much: everything in the window is
 * a panel that has been drawn already by the time it gets there.
 */
class ControlPanelTests {
    private static NES nes;
    private static Cart cart;
    private static Debugger debugger;
    private static EmulatorRunner runner;

    @BeforeAll
    static void machine() {
        cart = Cart.load(rom(), "control-panel.nes");
        nes = new NES(cart);
        debugger = new Debugger();
        debugger.attach(nes);

        // Never started, so the machine is this thread's throughout and nothing below races.
        runner = new EmulatorRunner(
                nes, new ScreenComponent(), debugger, 0, AudioOutput.DEFAULT_LATENCY_MS);

        for (var i = 0; i < 40_000; i++) {
            nes.tick();
        }
    }

    @Test
    void theColumnBuildsAndDrawsWithoutADisplay() {
        Views.paint(new ControlsColumn(new Switches(), new Commands()));
    }

    /**
     * The claim the column is built on: it holds no state and no listeners, only the switches the
     * menu bar is showing. So clicking one here is the same click, and a switch greyed anywhere is
     * greyed here.
     */
    @Test
    void everyControlInTheColumnIsTheSwitchItself() {
        var switches = new Switches();
        var column = new ControlsColumn(switches, new Commands());

        var pause = checkBox(column, "Pause");

        assertNotNull(pause, "the column has a Pause tick");
        assertFalse(switches.pause().isOn());

        pause.doClick();
        assertTrue(switches.pause().isOn(), "and it is the same switch the menu has");

        switches.pause().set(false);
        assertFalse(pause.isSelected(), "which moves it back from anywhere else");

        switches.unlimitedSprites().setEnabled(false);
        assertFalse(
                checkBox(column, "Unlimited Sprites").isEnabled(),
                "and greying a switch greys it here");
    }

    /**
     * A choice comes out as radio buttons rather than a combo box, because a combo box cannot be
     * given an Action and so could not be the same choice the menu is showing.
     */
    @Test
    void aChoiceInTheColumnMovesWithTheOneInTheMenu() {
        var switches = new Switches();
        var column = new ControlsColumn(switches, new Commands());
        var full = radio(column, com.github.dimiro1.mynes.ui.Volume.values()[0].label());

        assertNotNull(full);
        assertTrue(full.isSelected(), "the choice starts on its first option");

        switches.volume().select(com.github.dimiro1.mynes.ui.Volume.values()[1]);

        assertFalse(full.isSelected(), "and follows the choice rather than holding it");
    }

    /**
     * The commands are greyed until there is a machine, because a button that looks pressable and
     * does nothing is worse than one that says so.
     */
    @Test
    void theButtonsAreGreyedUntilSomethingEnablesThem() {
        var commands = new Commands();
        var column = new ControlsColumn(new Switches(), commands);
        var reset = button(column, "Reset");

        assertNotNull(reset);
        assertFalse(reset.isEnabled());

        commands.reset().setEnabled(true);
        assertTrue(reset.isEnabled());

        var ran = new ArrayList<String>();

        commands.reset().onRun(() -> ran.add("reset"));
        reset.doClick();

        assertEquals(List.of("reset"), ran);
    }

    @Test
    void theWindowBuildsAndTakesAMachine() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to put a window on");

        onSwingThread(() -> {
            var panel = new ControlPanelFrame(
                    null, new Switches(), new Commands(), PauseControl.NONE, Layout.DEFAULT);

            try {
                panel.setMachine(nes, runner, debugger, cart, Palettes.defaultPalette());
                panel.stopped(
                        new Debugger.Stop(Debugger.Reason.BREAKPOINT, 0x8000, null, -1, -1, -1));
                panel.running();

                // Again, which is what a power cycle brings: the debugger is repointed and the
                // instruments are built afresh.
                panel.setMachine(nes, runner, debugger, cart, Palettes.defaultPalette());
                panel.setPalette(Palettes.defaultPalette());
            } finally {
                panel.dispose();
            }
        });
    }

    /**
     * What the config file is handed on the way out, and what it hands back next time.
     */
    @Test
    void theWindowRemembersWhereItWasAndWhatWasInFront() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to put a window on");

        var remembered = new Layout[1];

        onSwingThread(() -> {
            var panel = new ControlPanelFrame(
                    null, new Switches(), new Commands(), PauseControl.NONE, Layout.DEFAULT);

            try {
                panel.setMachine(nes, runner, debugger, cart, Palettes.defaultPalette());
                panel.setBounds(new Rectangle(40, 60, 1200, 800));

                remembered[0] = panel.currentLayout();
            } finally {
                panel.dispose();
            }
        });

        assertEquals(new Rectangle(40, 60, 1200, 800), remembered[0].bounds());
        assertNotNull(remembered[0].tab(), "and which instrument was in front");

        var back = new Layout[1];

        onSwingThread(() -> {
            var panel = new ControlPanelFrame(
                    null, new Switches(), new Commands(), PauseControl.NONE, remembered[0]);

            try {
                panel.setMachine(nes, runner, debugger, cart, Palettes.defaultPalette());
                back[0] = panel.currentLayout();
            } finally {
                panel.dispose();
            }
        });

        assertEquals(remembered[0].bounds(), back[0].bounds(), "put back where it was");
        assertEquals(remembered[0].tab(), back[0].tab(), "with the same instrument in front");
    }

    /**
     * Bounds that land on no screen there is any more -- a window sized on a monitor that has since
     * been unplugged -- are dropped rather than used, since a window nobody can reach looks exactly
     * like one that failed to open.
     */
    @Test
    void boundsOnNoScreenAreIgnored() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to put a window on");

        var bounds = new Rectangle[1];

        onSwingThread(() -> {
            var panel = new ControlPanelFrame(
                    null,
                    new Switches(),
                    new Commands(),
                    PauseControl.NONE,
                    new Layout(new Rectangle(-30_000, -30_000, 800, 600), null));

            try {
                bounds[0] = panel.getBounds();
            } finally {
                panel.dispose();
            }
        });

        assertFalse(
                bounds[0].x == -30_000 && bounds[0].y == -30_000,
                "the window opened somewhere somebody can reach it");
    }

    /**
     * A tick over a machine nobody is clocking cannot stop it, and says so.
     */
    @Test
    void aPanelOverNothingHasNothingToPause() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to put a window on");

        var switches = new Switches();

        onSwingThread(() -> {
            var panel = new ControlPanelFrame(
                    null, switches, new Commands(), PauseControl.NONE, Layout.DEFAULT);

            panel.dispose();
        });

        assertFalse(switches.pause().isEnabled());
    }

    private static JCheckBox checkBox(final Container root, final String label) {
        return find(root, JCheckBox.class, label);
    }

    private static JRadioButton radio(final Container root, final String label) {
        return find(root, JRadioButton.class, label);
    }

    private static JButton button(final Container root, final String label) {
        return find(root, JButton.class, label);
    }

    private static <T extends javax.swing.AbstractButton> T find(
            final Container root, final Class<T> type, final String label) {

        for (var child : root.getComponents()) {
            if (type.isInstance(child) && label.equals(((javax.swing.AbstractButton) child).getText())) {
                return type.cast(child);
            }

            if (child instanceof Container inner) {
                var found = find(inner, type, label);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    private static void onSwingThread(final Runnable work) throws Exception {
        var failure = new Exception[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                work.run();
            } catch (RuntimeException | Error e) {
                failure[0] = new Exception(e);
            }
        });

        if (failure[0] != null) {
            throw failure[0];
        }
    }

    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        image[16] = 0x4C;
        image[17] = 0x00;
        image[18] = (byte) 0x80;

        for (var i = 0; i < 0x2000; i++) {
            image[16 + 0x4000 + i] = (byte) (i * 37);
        }

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
