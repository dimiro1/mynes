package com.github.dimiro1.mynes.ui.pads;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Usage;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two pads and the strip under them, drawn where there is no display.
 * <p>
 * Painting is most of the value: the pad is drawn rather than laid out, so a rectangle put past the
 * end of the component or a font asked of a component that has no parent is caught here and nowhere
 * else. What is asserted beyond that is the wording, and only where it says two different things --
 * a window nobody has been counting is not a game that never lagged, and a panel that said "0 of 0"
 * for both would be worse than one that said nothing.
 */
class PadsPanelTests {
    private static NES nes;

    @BeforeAll
    static void machine() {
        nes = new NES(Cart.load(rom(), "pads-panel.nes"));

        for (var i = 0; i < 40_000; i++) {
            nes.tick();
        }
    }

    @Test
    void thePanelDrawsBothPadsWithoutADisplay() {
        var panel = new PadsPanel();

        panel.show(readout(Readout.Pads.NONE));
        Views.paint(panel);
    }

    /**
     * Everything held at once, which is the case the drawing is most likely to get wrong: eight
     * buttons lit, and every one of them named beside the picture.
     */
    @Test
    void everythingHeldIsDrawnAndNamed() {
        var panel = new PadsPanel();

        nes.getController1().setButtons(0xFF);
        panel.show(readout(new Readout.Pads(1, 8, 1, 0, alternating(), Readout.Pads.WINDOW / 2)));
        Views.paint(panel);

        var text = String.join(" ", labels(panel));

        for (var button : List.of("Up", "Down", "Left", "Right", "Select", "Start", "A", "B")) {
            assertTrue(text.contains(button), "the panel names " + button);
        }

        nes.getController1().setButtons(0);
    }

    @Test
    void aWindowNobodyCountedSaysSoRatherThanClaimingNoLag() {
        var panel = new PadsPanel();

        panel.show(readout(Readout.Pads.NONE));

        assertTrue(
                labels(panel).contains("nothing counted yet"),
                "an empty window is not a game that never lagged");

        panel.show(readout(new Readout.Pads(1, 8, 1, 0, new boolean[]{true, true}, 0)));

        assertTrue(
                labels(panel).stream().anyMatch(text -> text.contains("every one of the last 2")),
                "where two frames both read the pad, both were counted");
    }

    /**
     * Alternate frames polled, which is what a game overrunning its frame looks like.
     */
    private static boolean[] alternating() {
        var polled = new boolean[Readout.Pads.WINDOW];

        for (var i = 0; i < polled.length; i++) {
            polled[i] = i % 2 == 0;
        }

        return polled;
    }

    private static Readout readout(final Readout.Pads pads) {
        return Readout.of(
                nes,
                Readout.NO_SCOPE,
                Readout.NO_TRACES,
                pads,
                Readout.Events.NONE,
                Usage.Snapshot.NONE);
    }

    private static List<String> labels(final Container root) {
        var found = new ArrayList<String>();

        for (var child : root.getComponents()) {
            if (child instanceof JLabel label) {
                found.add(label.getText());
            }

            if (child instanceof Container inner) {
                found.addAll(labels(inner));
            }
        }

        return found;
    }

    /**
     * A cartridge that does nothing but sit in a loop. Nothing here reads the machine for anything
     * but the buttons, so what it runs does not matter -- only that there is one to read.
     */
    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        // JMP $8000, forever.
        image[16] = 0x4C;
        image[17] = 0x00;
        image[18] = (byte) 0x80;

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
