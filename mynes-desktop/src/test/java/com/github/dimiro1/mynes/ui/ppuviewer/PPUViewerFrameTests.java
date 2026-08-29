package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.Palettes;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Builds both windows and makes them draw a machine.
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
            var frame = new NametableViewerFrame(null, nes, Palettes.defaultPalette());

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
            var frame = new OAMViewerFrame(null, nes.getPPU(), Palettes.defaultPalette());

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
     * The other sprite size, which takes a different path through the decoder: a tall sprite ignores
     * $2000's table bit and reads its two halves out of a pair of tiles picked by the tile number.
     */
    @Test
    void theOAMViewerDrawsTallSpritesToo() throws Exception {
        onSwingThread(() -> {
            // $2000 bit 5, written straight to the PPU: the machine above has finished warming up,
            // and nothing is clocking it, so this thread owns it.
            nes.getPPU().write(0, 0x20);

            var frame = new OAMViewerFrame(null, nes.getPPU(), Palettes.defaultPalette());

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
