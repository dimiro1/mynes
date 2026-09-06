package com.github.dimiro1.mynes.ui.events;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The raster and the four filters over it, drawn where there is no display.
 * <p>
 * Painting is most of what this buys: the raster is drawn rather than laid out, so a mark placed
 * past the end of the component -- which a scanline number on a PAL machine or a dot at 340 would
 * do -- is caught here and nowhere else. What is asserted beyond that is the wording, which is the
 * half of the panel a screenshot keeps, and that a filter really hides something.
 */
class EventsPanelTests {
    private static NES nes;

    @BeforeAll
    static void machine() {
        nes = new NES(Cart.load(rom(), "events-panel.nes"));

        for (var i = 0; i < 40_000; i++) {
            nes.tick();
        }
    }

    private static Debugger.Event event(final Debugger.EventKind kind, final int scanline) {
        return new Debugger.Event(kind, 0x2005, 0x40, scanline, 100, 0xC032);
    }

    private static Readout readout(final Readout.Events events) {
        return Readout.of(nes, Readout.NO_SCOPE, Readout.NO_TRACES, Readout.Pads.NONE, events);
    }

    private static Readout.Events frame(final Debugger.Event... events) {
        return new Readout.Events(List.of(events), 0);
    }

    @Test
    void thePanelDrawsAFrameWithoutADisplay() {
        var panel = new EventsPanel(Region.NTSC, reads -> { });

        panel.show(readout(frame(
                event(Debugger.EventKind.PPU_WRITE, 114),
                event(Debugger.EventKind.AUDIO_WRITE, 241),
                event(Debugger.EventKind.CARTRIDGE_WRITE, 0),
                event(Debugger.EventKind.NMI, 241),
                event(Debugger.EventKind.IRQ, 60))));

        Views.paint(panel);
    }

    /**
     * The last scanline of a PAL frame is 311, which is a raster half as tall again as an NTSC one
     * -- and the one place a mark can be drawn past the end of a component sized for the other
     * console.
     */
    @Test
    void aPalFrameIsTallerAndItsLastLineIsStillInside() {
        var panel = new EventsPanel(Region.PAL, reads -> { });

        panel.show(readout(frame(
                event(Debugger.EventKind.PPU_WRITE, Region.PAL.preRenderLine()),
                event(Debugger.EventKind.NMI, 241))));

        Views.paint(panel);

        var ntsc = new EventsPanel(Region.NTSC, reads -> { });

        Views.paint(ntsc);

        assertTrue(
                panel.getPreferredSize().height > ntsc.getPreferredSize().height,
                "312 scanlines want more room than 262");
    }

    @Test
    void theSummarySaysWhatTheFrameWasMadeOf() {
        var panel = new EventsPanel(Region.NTSC, reads -> { });

        panel.show(readout(frame(
                event(Debugger.EventKind.PPU_WRITE, 114),
                event(Debugger.EventKind.PPU_WRITE, 114),
                event(Debugger.EventKind.NMI, 241))));

        var text = String.join(" ", labels(panel));

        assertTrue(text.contains("3 of 3 shown"), text);
        assertTrue(text.contains("2 PPU write"), text);
        assertTrue(text.contains("1 NMI"), text);
    }

    /**
     * A filter hides marks that have already been recorded, which is why it can say how many of how
     * many are on show. Reads are the other kind of thing entirely and are settled at the machine.
     */
    @Test
    void aFilterHidesItsOwnKindAndSaysSo() {
        var panel = new EventsPanel(Region.NTSC, reads -> { });

        panel.show(readout(frame(
                event(Debugger.EventKind.PPU_WRITE, 114),
                event(Debugger.EventKind.AUDIO_WRITE, 200),
                event(Debugger.EventKind.AUDIO_WRITE, 210))));

        assertTrue(String.join(" ", labels(panel)).contains("3 of 3 shown"));

        var sound = box(panel, "Sound");

        assertNotNull(sound, "there is a filter for the audio writes");
        sound.doClick();

        assertTrue(
                String.join(" ", labels(panel)).contains("1 of 3 shown"),
                "the two audio writes came off the raster and the line says so");
    }

    /**
     * A frame with nothing in it says so rather than looking like a panel that has not been given
     * anything yet.
     */
    @Test
    void anEmptyFrameSaysNothingWasTouched() {
        var panel = new EventsPanel(Region.NTSC, reads -> { });

        panel.show(readout(Readout.Events.NONE));

        assertTrue(String.join(" ", labels(panel)).contains("nothing touched this frame"));
    }

    @Test
    void aFrameThatOverflowedSaysHowMuchWasLost() {
        var panel = new EventsPanel(Region.NTSC, reads -> { });

        panel.show(readout(new Readout.Events(
                List.of(event(Debugger.EventKind.PPU_READ, 30)), 900)));

        assertTrue(
                String.join(" ", labels(panel)).contains("900 more than the log holds"),
                "a frame that overflowed is the frame worth looking at");
    }

    /**
     * The reads tick is not a filter: it reaches past the window and puts a hook on the bus, so the
     * panel's only job is to say so.
     */
    @Test
    void theReadsTickIsHandedOnRatherThanAppliedHere() {
        var asked = new ArrayList<Boolean>();
        var panel = new EventsPanel(Region.NTSC, asked::add);

        box(panel, "Record reads").doClick();
        assertEquals(List.of(true), asked);

        box(panel, "Record reads").doClick();
        assertEquals(List.of(true, false), asked);
    }

    private static JCheckBox box(final Container root, final String label) {
        for (var child : root.getComponents()) {
            if (child instanceof JCheckBox found && label.equals(found.getText())) {
                return found;
            }

            if (child instanceof Container inner) {
                var found = box(inner, label);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
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
     * -- the events are made up -- only that there is one for a readout to be taken from.
     */
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

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
