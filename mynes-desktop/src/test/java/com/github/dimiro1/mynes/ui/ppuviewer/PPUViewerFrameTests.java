package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.ui.PauseControl;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Builds all three windows and makes them draw a machine.
 * <p>
 * Nothing here asserts on what they look like -- that is a job for eyes, and the pictures they draw
 * are the reason they exist. What it catches is the class of mistake that only shows up once the
 * thing is actually built: a raster written past its end, a table renderer that throws on its first
 * row, a tall sprite decoded off the end of the pattern tables. All of those compile perfectly.
 * <p>
 * Skipped where there is no display, which includes the CI machine, for the reason the debugger
 * window's tests are.
 */
class PPUViewerFrameTests {
    private static final int SPRITES = 64;

    /**
     * How many of the cartridge's sprites land in the picture. Sprite <i>n</i> gets a Y byte of
     * {@code n * 4}, and anything from 239 up is parked, so the last four are not.
     */
    private static final int ON_SCREEN = 60;

    private static NES nes;

    @BeforeAll
    static void machine() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "no display to put a window on");

        nes = new NES(Cart.load(rom(), "ppu-viewer.nes"));

        // Far enough for the reset to have run and the PPU to be somewhere, so the windows are
        // drawing a machine rather than a field of zeroes.
        for (var i = 0; i < 40_000; i++) {
            nes.tick();
        }
    }

    @Test
    void theNametableViewerBuildsAndDrawsAllFour() throws Exception {
        onSwingThread(() -> {
            var frame = new NametableViewerFrame(null, nes, Palettes.defaultPalette(), PauseControl.NONE);

            try {
                frame.setVisible(true);
                frame.setPalette(Palettes.defaultPalette());
                paint(frame);
            } finally {
                frame.dispose();
            }
        });
    }

    @Test
    void theOAMViewerBuildsAndDrawsEverySprite() throws Exception {
        onSwingThread(() -> {
            var frame = new OAMViewerFrame(null, nes.getPPU(), Palettes.defaultPalette(), PauseControl.NONE);

            try {
                frame.setVisible(true);
                frame.setPalette(Palettes.defaultPalette());
                paint(frame);
            } finally {
                frame.dispose();
            }
        });
    }

    /**
     * The palette viewer, drawn and then walked over with the pointer -- every pixel of it, which
     * is 150,000 mouse moves and takes a moment, because the line each one produces is the half of
     * that window that is not swatches.
     */
    @Test
    void thePaletteViewerBuildsAndDrawsEveryCell() throws Exception {
        onSwingThread(() -> {
            var frame = new PaletteViewerFrame(null, nes.getPPU(), Palettes.defaultPalette(), PauseControl.NONE);

            try {
                frame.setVisible(true);
                frame.setPalette(Palettes.defaultPalette());
                paint(frame);
                sweepWithThePointer(frame);
                paint(frame);
            } finally {
                frame.dispose();
            }
        });
    }

    /**
     * Moves the pointer over every pixel of the palette viewer's swatches, which is what runs the
     * line describing whatever is under it -- including on the seven cells that are not what they
     * look like and so have something extra to say.
     */
    private static void sweepWithThePointer(final PaletteViewerFrame frame) {
        var panel = find(frame.getContentPane());

        for (var y = -2; y < PaletteRAMPanel.HEIGHT + 2; y++) {
            for (var x = -2; x < PaletteRAMPanel.WIDTH + 2; x++) {
                panel.dispatchEvent(new java.awt.event.MouseEvent(
                        panel,
                        java.awt.event.MouseEvent.MOUSE_MOVED,
                        System.currentTimeMillis(),
                        0,
                        x,
                        y,
                        0,
                        false));
            }
        }
    }

    private static PaletteRAMPanel find(final java.awt.Container root) {
        return (PaletteRAMPanel) find(root, PaletteRAMPanel.class);
    }

    /**
     * The first component of a kind anywhere under {@code root}, so a test can drive a window
     * through the controls a person would use rather than through a field it has no business
     * reaching into.
     */
    private static java.awt.Component find(
            final java.awt.Container root, final Class<?> type) {

        for (var child : root.getComponents()) {
            if (type.isInstance(child)) {
                return child;
            }

            if (child instanceof java.awt.Container container) {
                var found = find(container, type);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * Grouping the sprites, picking a group with one click and letting go of it again -- which
     * between them are every path the selection has: the outline round a union of rectangles, the
     * columns read through an order that is no longer the identity, and the selection put back on
     * the sprites it was on after the rows have moved under it.
     * <p>
     * The cartridge below lays its sixty four sprites out four pixels apart down a diagonal, so
     * every one of them touches the next and the guess makes the lot one group -- which is a fine
     * thing to click on. What is being tested here is the plumbing rather than the guess;
     * {@link SpriteGroupsTests} is where the guess is held to anything.
     */
    @Test
    void theOAMViewerGroupsTheSpritesAndPicksOneWithAClick() throws Exception {
        onSwingThread(() -> {
            var frame = new OAMViewerFrame(null, nes.getPPU(), Palettes.defaultPalette(), PauseControl.NONE);

            try {
                frame.setVisible(true);

                var table = (JTable) find(frame.getContentPane(), JTable.class);
                var grouped = checkBox(frame.getContentPane(), "Group");

                table.setRowSelectionInterval(4, 4);
                assertEquals("4", table.getValueAt(4, 1), "OAM order, so row 4 is sprite 4");
                paint(frame);

                grouped.doClick();
                assertEquals(
                        1,
                        rowsWithAGroupLabel(table),
                        "one label, because the sixty four are one group on this cartridge");
                paint(frame);

                table.setRowSelectionInterval(0, 0);
                assertEquals(
                        SPRITES, table.getSelectedRowCount(), "a click takes the whole group");
                paint(frame);

                table.setRowSelectionInterval(0, 5);
                assertEquals(
                        6,
                        table.getSelectedRowCount(),
                        "and a run of rows is left as the run it is");
                paint(frame);

                grouped.doClick();

                for (var row = 0; row < SPRITES; row++) {
                    assertEquals(
                            Integer.toString(row),
                            table.getValueAt(row, 1),
                            "OAM order comes back");
                }

                paint(frame);
            } finally {
                frame.dispose();
            }
        });
    }

    /**
     * Leaving out the sprites the game has parked below the picture.
     * <p>
     * The cartridge below gives sprite <i>n</i> a Y byte of {@code n * 4}, so the four highest
     * numbered are the only ones off the bottom -- which is enough to tell a filter that works from
     * one that does not, and leaves the table with rows in it either way.
     */
    @Test
    void theOAMViewerCanLeaveOutTheSpritesParkedBelowThePicture() throws Exception {
        onSwingThread(() -> {
            var frame = new OAMViewerFrame(null, nes.getPPU(), Palettes.defaultPalette(), PauseControl.NONE);

            try {
                frame.setVisible(true);

                var table = (JTable) find(frame.getContentPane(), JTable.class);
                var onScreenOnly = checkBox(frame.getContentPane(), "On screen only");

                assertEquals(SPRITES, table.getRowCount(), "all of them to begin with");

                onScreenOnly.doClick();
                assertEquals(ON_SCREEN, table.getRowCount(), "and the parked ones are gone");

                for (var row = 0; row < table.getRowCount(); row++) {
                    assertEquals(
                            Integer.toString(row),
                            table.getValueAt(row, 1),
                            "still in OAM order, with the tail cut off rather than a gap in it");
                }

                paint(frame);

                // A selection on a row that only exists while the filter is on, so that turning it
                // off has to put the selection back somewhere that has moved.
                table.setRowSelectionInterval(ON_SCREEN - 1, ON_SCREEN - 1);

                onScreenOnly.doClick();
                assertEquals(SPRITES, table.getRowCount(), "and back again");
                paint(frame);
            } finally {
                frame.dispose();
            }
        });
    }

    /**
     * A tick by its label rather than whichever checkbox the walk reaches first, which is now a
     * question worth asking: the window has three of them.
     */
    private static JCheckBox checkBox(final java.awt.Container root, final String label) {
        for (var child : root.getComponents()) {
            if (child instanceof JCheckBox box && label.equals(box.getText())) {
                return box;
            }

            if (child instanceof java.awt.Container container) {
                var found = checkBox(container, label);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    /**
     * How many rows carry a group label, which is once per group while the rows are in group order
     * -- the blocks the table reads as.
     */
    private static int rowsWithAGroupLabel(final JTable table) {
        var count = 0;

        for (var row = 0; row < table.getRowCount(); row++) {
            if (!table.getValueAt(row, 0).toString().isEmpty()) {
                count++;
            }
        }

        return count;
    }

    /**
     * The other sprite size, which takes a different path through the decoder: a tall sprite ignores
     * $2000's table bit and reads its two halves out of a pair of tiles picked by the tile number.
     */
    @Test
    void theOAMViewerDrawsTallSpritesToo() throws Exception {
        onSwingThread(() -> {
            // $2000 bit 5, written straight to the PPU: the machine above has finished warming up,
            // and nothing is clocking it, so this thread owns it.
            nes.getPPU().write(0, 0x20);

            var frame = new OAMViewerFrame(null, nes.getPPU(), Palettes.defaultPalette(), PauseControl.NONE);

            try {
                frame.setVisible(true);
                paint(frame);
            } finally {
                frame.dispose();
                nes.getPPU().write(0, 0x00);
            }
        });
    }

    /**
     * Draws the whole window into an off-screen image, which is what actually runs every renderer
     * and every {@code paintComponent} in it. Showing the window is not enough on its own: a paint
     * can be coalesced away, and then a renderer that throws is never called.
     */
    private static void paint(final java.awt.Window window) {
        var image = new java.awt.image.BufferedImage(
                Math.max(1, window.getWidth()),
                Math.max(1, window.getHeight()),
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();

        try {
            window.paint(g);
        } finally {
            g.dispose();
        }
    }

    /**
     * Swing wants its components built on its own thread, and an exception thrown over there would
     * otherwise be logged and swallowed rather than failing anything.
     */
    private static void onSwingThread(final Runnable work) throws Exception {
        var failure = new Exception[1];

        SwingUtilities.invokeAndWait(() -> {
            try {
                work.run();
            } catch (RuntimeException | Error e) {
                failure[0] = new Exception(e);
            }
        });

        assertDoesNotThrow(() -> {
            if (failure[0] != null) {
                throw failure[0];
            }
        });
    }

    /**
     * A cartridge that fills OAM with sprites spread across the screen and writes a pattern into
     * every nametable byte, so that both windows have something to draw rather than a field of
     * zeroes -- and so that a sprite parked off the bottom is among them.
     *
     * <pre>
     * 8000  A2 00     LDX #$00
     * 8002  8A        TXA
     * 8003  9D 00 02  STA $0200,X   sprite bytes, which double as coordinates
     * 8006  E8        INX
     * 8007  D0 F9     BNE $8002
     * 8009  8E 03 20  STX $2003     OAMADDR back to zero
     * 800C  A9 02     LDA #$02
     * 800E  8D 14 40  STA $4014     one transfer of page two into OAM
     * 8011  4C 11 80  JMP $8011
     * </pre>
     */
    private static byte[] rom() {
        var code = new int[]{
                0xA2, 0x00,
                0x8A,
                0x9D, 0x00, 0x02,
                0xE8,
                0xD0, 0xF9,
                0x8E, 0x03, 0x20,
                0xA9, 0x02,
                0x8D, 0x14, 0x40,
                0x4C, 0x11, 0x80,
        };

        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        for (var i = 0; i < code.length; i++) {
            image[16 + i] = (byte) code[i];
        }

        // Character memory that is not all zeroes, so a decoded tile has more than one colour in it.
        for (var i = 0; i < 0x2000; i++) {
            image[16 + 0x4000 + i] = (byte) (i * 37);
        }

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
