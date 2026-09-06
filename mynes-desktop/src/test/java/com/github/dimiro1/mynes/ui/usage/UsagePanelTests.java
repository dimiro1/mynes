package com.github.dimiro1.mynes.ui.usage;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Usage;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JLabel;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The load graph and the four memory maps, drawn where there is no display.
 * <p>
 * Painting is most of the value, as it is for every panel here: the strips are drawn rather than
 * laid out, so a cell put past the end of the component or a column taller than the box is caught
 * here and nowhere else. Two of them are drawn at a width nothing asked for, because this is the one
 * panel in the program that takes the width of whatever it is scrolling inside -- so the arithmetic
 * that shares a hundred and twenty-eight cells out over it has to survive being given an awkward
 * number of pixels.
 * <p>
 * What is asserted beyond that is the wording, and only where it says two different things: a board
 * with no RAM on it is not a board whose RAM nobody has written to, and a reading nobody took is not
 * a frame that used none of itself.
 */
class UsagePanelTests {
    private static NES nes;

    @BeforeAll
    static void machine() {
        nes = new NES(Cart.load(rom(), "usage-panel.nes"));

        for (var i = 0; i < 40_000; i++) {
            nes.tick();
        }
    }

    @Test
    void thePanelDrawsWithoutADisplay() {
        var panel = new UsagePanel(() -> { });

        panel.show(readout(Usage.Snapshot.NONE));
        Views.paint(panel);
    }

    /**
     * A full frame, a full window and a map with something in every area, which is the case with the
     * most to draw and the most to draw wrongly.
     */
    @Test
    void aBusyMachineIsDrawnAtEveryWidth() {
        var panel = new UsagePanel(() -> { });

        panel.show(readout(measured()));

        // Awkward on purpose. 128 cells over 301 pixels divides into nothing, and a strip that
        // worked out one cell width and multiplied by it would leave a gap at one end.
        for (var width : List.of(301, 640, 1279)) {
            panel.setSize(width, panel.getPreferredSize().height);
            Views.paint(panel);
        }
    }

    @Test
    void aReadingNobodyTookSaysSoRatherThanClaimingAnIdleMachine() {
        var panel = new UsagePanel(() -> { });

        panel.show(readout(Usage.Snapshot.NONE));

        assertTrue(
                labels(panel).contains("—"),
                "nothing measured is not a frame that used none of itself");

        panel.show(readout(measured()));

        assertTrue(
                labels(panel).stream().anyMatch(text -> text.endsWith("%")),
                "a frame that was measured says what share of it went on work");
    }

    /**
     * A cartridge with no RAM chip on it, which every UxROM board is. There is no area in the
     * reading at all for that, and an empty strip on its own would read as RAM nobody has touched.
     */
    @Test
    void aBoardWithNoRAMSaysSoRatherThanShowingAnEmptyMap() {
        var panel = new UsagePanel(() -> { });

        panel.show(readout(measured()));

        assertTrue(
                labels(panel).contains("none on the board"),
                "there is no cartridge RAM on this one to have written to");
    }

    @Test
    void startingAgainAsksWhoeverIsClockingTheMachine() {
        var asked = new int[1];
        var panel = new UsagePanel(() -> asked[0]++);
        var button = Views.find(panel, JButton.class);

        assertNotNull(button, "the map has to be startable again, or it is a map of the boot clear");
        button.doClick();

        assertEquals(1, asked[0]);
    }

    /**
     * A frame that has been measured, with a map of the console's own memory but nothing on the
     * cartridge -- the board this ROM describes has no RAM.
     */
    private static Usage.Snapshot measured() {
        var meter = new Usage();

        meter.frameEnded(1, 0, 0);

        for (var at = 0; at < 20_000; at += 40) {
            meter.wrote(0x0300 + (at / 40) % 0x0600, 0x8003, at);
        }

        meter.wrote(0x01FF, 0x8010, 20_040);
        meter.wrote(0x0010, 0x8020, 20_080);
        meter.frameEnded(2, 29_780, 513);

        return meter.snapshot(0);
    }

    private static Readout readout(final Usage.Snapshot usage) {
        return Readout.of(
                nes,
                Readout.NO_SCOPE,
                Readout.NO_TRACES,
                Readout.Pads.NONE,
                Readout.Events.NONE,
                usage);
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
     * but its board, so what it runs does not matter -- only that there is one to read.
     * <p>
     * <b>Mapper 2 rather than 0</b>, which is the whole reason this file builds a ROM instead of
     * borrowing one: UxROM is a board with no RAM chip on it, and the case worth testing is a panel
     * saying that rather than drawing an empty map of memory that is not there. An NROM board gets
     * the 8KB every board here has when the header says nothing.
     */
    private static byte[] rom() {
        var image = new byte[16 + 0x8000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 2;
        image[5] = 1;
        image[6] = 0x20;

        // JMP $8000, forever, in both banks -- the switchable one is what resets into.
        for (var bank = 0; bank < 2; bank++) {
            image[16 + bank * 0x4000] = 0x4C;
            image[17 + bank * 0x4000] = 0x00;
            image[18 + bank * 0x4000] = (byte) 0x80;
        }

        image[16 + 0x7FFC] = 0x00;
        image[16 + 0x7FFD] = (byte) 0x80;

        return image;
    }
}
