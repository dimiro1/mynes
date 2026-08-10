package com.github.dimiro1.mynes.ui.palette;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for reading a palette file and for the emphasis variants synthesised from it.
 * <p>
 * The emphasis half used to live in {@code PPUBackgroundTests}, back when the PPU did this work.
 * It is the same assertion: the bits do not brighten the channel they name, they dim the other two.
 */
class NESPaletteTests {
    /**
     * How much a channel is dimmed by emphasis. Repeated here rather than reached for, so that a
     * change to the constant has to be a deliberate change to this number too.
     */
    private static final double ATTENUATION = 0.746;

    /**
     * A file where every entry is a different colour: entry {@code i} is
     * {@code (i, 64 + i, 128 + i)}.
     */
    private static byte[] baseColours() {
        var bytes = new byte[64 * 3];

        for (var i = 0; i < 64; i++) {
            bytes[i * 3] = (byte) i;
            bytes[i * 3 + 1] = (byte) (64 + i);
            bytes[i * 3 + 2] = (byte) (128 + i);
        }

        return bytes;
    }

    private static NESPalette palette() {
        return NESPalette.fromRGB("test", "Test", baseColours());
    }

    @Nested
    @DisplayName("reading a file")
    class Reading {
        @Test
        void aPlainFileIsSixtyFourColours() {
            var palette = palette();

            assertEquals(0xFF004080, palette.colour(0));
            assertEquals(0xFF3F7FBF, palette.colour(63));
        }

        @Test
        void everyColourIsOpaque() {
            // The framebuffer is drawn into a TYPE_INT_RGB image, which ignores the alpha byte --
            // but a colour handed anywhere else would come out invisible without this.
            for (var colour : palette().colours()) {
                assertEquals(0xFF000000, colour & 0xFF000000, Integer.toHexString(colour));
            }
        }

        @Test
        void theTableCoversEveryEmphasisCombination() {
            assertEquals(8 * 64, palette().colours().length);
        }

        @Test
        void aWrongLengthIsRejected() {
            assertThrows(IllegalArgumentException.class,
                    () -> NESPalette.fromRGB("test", "Test", new byte[191]));
            assertThrows(IllegalArgumentException.class,
                    () -> NESPalette.fromRGB("test", "Test", new byte[0]));
        }

        @Test
        void aFullFileIsTakenAtItsWord() {
            var bytes = new byte[512 * 3];

            for (var i = 0; i < 512; i++) {
                bytes[i * 3] = (byte) 0x10;
                bytes[i * 3 + 1] = (byte) 0x20;
                bytes[i * 3 + 2] = (byte) 0x30;
            }

            // Entry 0 with red emphasised, set to something the synthesis could never produce.
            bytes[64 * 3] = (byte) 0xFF;

            var colours = NESPalette.fromRGB("test", "Test", bytes).colours();

            assertEquals(0xFF102030, colours[0]);
            assertEquals(0xFFFF2030, colours[1 << 6],
                    "a file that measured emphasis knows better than the approximation");
        }

        @Test
        void theTableHandedOutIsACopy() {
            var palette = palette();

            palette.colours()[0] = 0;

            assertNotEquals(0, palette.colour(0));
        }
    }

    @Nested
    @DisplayName("emphasis")
    class Emphasis {
        @Test
        void noEmphasisLeavesTheColoursAlone() {
            var palette = palette();
            var colours = palette.colours();

            for (var entry = 0; entry < 64; entry++) {
                assertEquals(palette.colour(entry), colours[entry], "entry " + entry);
            }
        }

        @Test
        void emphasisingRedDimsTheOtherTwo() {
            var palette = palette();

            var plain = palette.colour(1);
            var shown = palette.colours()[(1 << 6) | 1];

            assertEquals((plain >> 16) & 0xFF, (shown >> 16) & 0xFF, "red is left alone");
            assertEquals((int) (((plain >> 8) & 0xFF) * ATTENUATION), (shown >> 8) & 0xFF,
                    "green is dimmed");
            assertEquals((int) ((plain & 0xFF) * ATTENUATION), shown & 0xFF, "and so is blue");
        }

        @Test
        void allThreeBitsDimEverything() {
            var palette = palette();

            var plain = palette.colour(1);
            var shown = palette.colours()[(7 << 6) | 1];

            // Each channel is dimmed by the other two being emphasised, so asking for all three
            // at once darkens the picture rather than leaving it alone. Games use it to fade out.
            assertEquals((int) (((plain >> 16) & 0xFF) * ATTENUATION), (shown >> 16) & 0xFF, "red");
            assertEquals((int) (((plain >> 8) & 0xFF) * ATTENUATION), (shown >> 8) & 0xFF, "green");
            assertEquals((int) ((plain & 0xFF) * ATTENUATION), shown & 0xFF, "blue");
        }
    }

    @Nested
    @DisplayName("naming")
    class Naming {
        @Test
        void theShownNameIsWhatAListWouldDraw() {
            // So that a JList of these needs no cell renderer.
            assertEquals("Test", palette().toString());
        }

        @Test
        void theIdIsWhatTheConfigFileRemembers() {
            assertEquals("test", palette().id());
            assertEquals("Test", palette().name());
        }
    }
}
