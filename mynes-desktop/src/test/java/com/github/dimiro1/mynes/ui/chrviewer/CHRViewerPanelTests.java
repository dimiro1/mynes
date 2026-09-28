package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import java.awt.Dimension;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Builds the view over character memory and makes it draw a cartridge's tiles.
 * <p>
 * The first test this one has ever had, and it is here because the view is a panel: a window needed
 * a display, and the machine that runs the build has none. What it catches is what the other view
 * tests catch -- a bank decoded off its end, a tile renderer that throws on its first row -- and it
 * walks the two combo boxes because choosing a bank and a palette is what the view is for.
 */
class CHRViewerPanelTests {
    /**
     * The palette that is not read out of the machine at all: four fixed colours, which is the one
     * branch in how the tiles are coloured.
     */
    private static final String DEFAULT_PALETTE = "Default";

    private static Cart cart;
    private static NES nes;

    @BeforeAll
    static void machine() {
        cart = Cart.load(rom(), "chr-viewer.nes");
        nes = new NES(cart);

        for (var i = 0; i < 40_000; i++) {
            nes.tick();
        }
    }

    @Test
    void theViewBuildsAndDrawsEveryTileOfEveryBank() {
        var view = new CHRViewerPanel(cart, nes.getPPU(), Palettes.defaultPalette());
        var banks = combos(view).getFirst();

        assertEquals(2, banks.getItemCount(), "8KB of character memory is two 4KB banks");

        for (var bank = 0; bank < banks.getItemCount(); bank++) {
            banks.setSelectedIndex(bank);
            view.refresh();
            Views.paint(view);
        }
    }

    /**
     * Every palette in turn, the machine's eight and the fixed one, since where the four colours
     * come from is the only branch in the drawing.
     */
    @Test
    void theTilesAreDrawnThroughWhicheverPaletteIsChosen() {
        var view = new CHRViewerPanel(cart, nes.getPPU(), Palettes.defaultPalette());
        var palettes = palettes(view);

        for (var choice = 0; choice < palettes.getItemCount(); choice++) {
            palettes.setSelectedIndex(choice);
            view.refresh();
            Views.paint(view);
        }
    }

    /**
     * Tall sprites, which pair the tiles up two at a time and so lay the whole sheet out
     * differently. The only tick in the view.
     */
    @Test
    void theTilesArePairedUpForTallSprites() {
        var view = new CHRViewerPanel(cart, nes.getPPU(), Palettes.defaultPalette());

        Views.find(view, JCheckBox.class).doClick();
        Views.paint(view);
    }

    @Test
    void selectingAnotherTileDoesNotMoveTheCentredPanel() {
        var view = new CHRViewerPanel(cart, nes.getPPU(), Palettes.defaultPalette());
        var grid = Views.find(view, TilesViewerPanel.class);
        Dimension before = view.getPreferredSize();

        grid.dispatchEvent(new MouseEvent(grid, MouseEvent.MOUSE_PRESSED,
                0, 0, 36, 36, 1, false));
        assertEquals(0x11, grid.selectedTile());
        assertEquals(before, view.getPreferredSize());
        grid.dispatchEvent(new MouseEvent(grid, MouseEvent.MOUSE_PRESSED,
                0, 0, 68, 68, 1, false));
        assertEquals(0x22, grid.selectedTile());
        assertEquals(before, view.getPreferredSize());
    }

    @Test
    void usageAndPatternBytesStayTogetherInTheInspector() {
        var view = new CHRViewerPanel(cart, nes.getPPU(), Palettes.defaultPalette());
        Views.paint(view);
        var usage = labelStartingWith(view, "Nametables:");
        var patternBytes = Views.find(view, javax.swing.JTextArea.class);

        assertEquals(patternBytes.getX(), usage.getX());
        assertTrue(usage.getY() < patternBytes.getY());
        assertTrue(usage.getWidth() >= usage.getPreferredSize().width);
    }

    @Test
    void bankSwitchingDoesNotTurnPhysicalChrBanksIntoPpuAddresses() {
        var image = new byte[16 + 0x4000 + 0x4000];
        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 2;
        image[6] = 0x30; // CNROM: two physical 8 KB CHR banks.
        var switchingCart = Cart.load(image, "banked-tiles.nes");
        var switchingNes = new NES(switchingCart);
        var view = new CHRViewerPanel(
                switchingCart, switchingNes.getPPU(), Palettes.defaultPalette());
        var selector = combos(view).getFirst();

        assertEquals(2, selector.getItemCount());
        switchingCart.mapper().prgWrite(0x8000, 1);
        view.refresh();
        assertTrue(labels(view).stream().anyMatch(label -> label.contains("08 09 0A 0B")));
        assertTrue(labels(view).stream().anyMatch(label -> label.contains("CHR ROM $02000")));
    }

    /**
     * The palette chooser, told from the bank chooser by what is in it rather than by which came
     * first: an order is exactly the thing that would change silently.
     */
    private static JComboBox<Object> palettes(final Container view) {
        return combos(view).stream()
                .filter(box -> DEFAULT_PALETTE.equals(box.getItemAt(0).toString()))
                .findFirst()
                .orElseThrow();
    }

    private static List<JComboBox<Object>> combos(final Container root) {
        var found = new ArrayList<JComboBox<Object>>();

        collect(root, found);

        return found;
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

    private static JLabel labelStartingWith(final Container root, final String prefix) {
        for (var child : root.getComponents()) {
            if (child instanceof JLabel label && label.getText().startsWith(prefix)) {
                return label;
            }
            if (child instanceof Container inner) {
                var found = labelStartingWith(inner, prefix);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void collect(final Container root, final List<JComboBox<Object>> found) {
        for (var child : root.getComponents()) {
            if (child instanceof JComboBox<?> box) {
                found.add((JComboBox<Object>) box);
            }

            if (child instanceof Container inner) {
                collect(inner, found);
            }
        }
    }

    /**
     * A cartridge with two banks of character memory, both full of something other than zeroes, so
     * that a decoded tile has more than one colour in it and switching bank changes the picture.
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

        for (var i = 0; i < 0x2000; i++) {
            image[16 + 0x4000 + i] = (byte) (i * 37);
        }

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
