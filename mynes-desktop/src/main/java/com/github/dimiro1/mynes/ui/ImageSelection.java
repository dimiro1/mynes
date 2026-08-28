package com.github.dimiro1.mynes.ui;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;

/**
 * A picture on its way to the clipboard.
 * <p>
 * {@code java.awt.datatransfer} ships {@link java.awt.datatransfer.StringSelection} and nothing at
 * all for pictures, so this is the piece it is missing. One flavour only: the toolkit turns an
 * {@link Image} into whatever the platform pastes -- TIFF on macOS, a bitmap on Windows -- and a
 * second flavour carrying the same picture as encoded bytes would only be a chance for the program
 * on the other end to choose the worse of two answers to one question.
 * <p>
 * The clipboard holds on to this object rather than to any bytes, and the program pasting asks for
 * the picture whenever it gets round to it -- which may be minutes later, with the machine ten
 * thousand frames further on. So the image handed in has to be one nothing else is going to draw
 * over. {@link ScreenComponent#snapshot} builds a fresh one on every call, which is what makes it
 * safe to hand straight here.
 */
final class ImageSelection implements Transferable {
    private static final DataFlavor[] FLAVOURS = {DataFlavor.imageFlavor};

    private final Image image;

    ImageSelection(final Image image) {
        this.image = image;
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        // A copy: whoever asks owns what it is handed, and the array behind it is shared with every
        // other picture that has ever been copied.
        return FLAVOURS.clone();
    }

    @Override
    public boolean isDataFlavorSupported(final DataFlavor flavour) {
        return DataFlavor.imageFlavor.equals(flavour);
    }

    @Override
    public Image getTransferData(final DataFlavor flavour) throws UnsupportedFlavorException {
        if (!isDataFlavorSupported(flavour)) {
            throw new UnsupportedFlavorException(flavour);
        }

        return image;
    }
}
