package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import java.awt.Container;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        var view = new CHRViewerPanel(cart, nes.getPPU(), Palettes.defaultPalette(), null);
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
        var view = new CHRViewerPanel(cart, nes.getPPU(), Palettes.defaultPalette(), null);
        var palettes = palettes(view);

        for (var choice = 0; choice < palettes.getItemCount(); choice++) {
            palettes.setSelectedIndex(choice);
            view.refresh();
            Views.paint(view);
        }
    }

    /**
     * Tall sprites, which pair the tiles up two at a time and so lay the whole sheet out
     * differently. The only tick in the view, with the Pause one left out.
     */
    @Test
    void theTilesArePairedUpForTallSprites() {
        var view = new CHRViewerPanel(cart, nes.getPPU(), Palettes.defaultPalette(), null);

        Views.find(view, JCheckBox.class).doClick();
        Views.paint(view);
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
