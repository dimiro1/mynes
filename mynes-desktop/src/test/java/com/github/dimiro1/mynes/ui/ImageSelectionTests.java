package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for what File &gt; Copy Screenshot hands the clipboard.
 * <p>
 * The clipboard asks the questions here at its own pace and on somebody else's thread, so the
 * answers have to hold whenever they are asked rather than at the moment the key was pressed.
 */
class ImageSelectionTests {
    private final BufferedImage picture = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);

    private final ImageSelection selection = new ImageSelection(picture);

    @Test
    void aPictureIsOfferedAsAPictureAndNothingElse() {
        assertArrayEquals(new DataFlavor[]{DataFlavor.imageFlavor},
                selection.getTransferDataFlavors());
    }

    @Test
    void theListOfFlavoursIsNotTheOneEverybodyElseWasHanded() {
        // An owner is entitled to sort or scribble on what it is given.
        assertNotSame(selection.getTransferDataFlavors(), selection.getTransferDataFlavors());
    }

    @Test
    void theImageFlavourIsSupportedAndTheOthersAreNot() {
        assertTrue(selection.isDataFlavorSupported(DataFlavor.imageFlavor));
        assertFalse(selection.isDataFlavorSupported(DataFlavor.stringFlavor));
        assertFalse(selection.isDataFlavorSupported(DataFlavor.javaFileListFlavor));
    }

    @Test
    void thePictureComesBackAsItself() throws Exception {
        assertSame(picture, selection.getTransferData(DataFlavor.imageFlavor));
    }

    @Test
    void askingForAFlavourThatWasNotOfferedIsAnError() {
        // Rather than null, which the program pasting would have to be written to expect.
        assertThrows(UnsupportedFlavorException.class,
                () -> selection.getTransferData(DataFlavor.stringFlavor));
    }
}
