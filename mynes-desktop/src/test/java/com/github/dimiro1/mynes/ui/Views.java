package com.github.dimiro1.mynes.ui;

import javax.swing.JComponent;
import java.awt.Container;
import java.awt.image.BufferedImage;

/**
 * Draws a debug view into an image, where there is no display to put one on.
 * <p>
 * Every view in the front end is a {@link javax.swing.JPanel} rather than a window, which is what
 * lets these tests run on the machine that runs the build rather than only on somebody's desk --
 * and drawing one is the only thing that actually runs its renderers and every
 * {@code paintComponent} in it. A raster written past its end, a table renderer that throws on its
 * first row, a tall sprite decoded off the end of the pattern tables: all of those compile
 * perfectly and all of them are caught here.
 */
public final class Views {
    private Views() {
    }

    /**
     * Sizes a view as though it had been packed into a window, then paints all of it.
     */
    public static void paint(final JComponent view) {
        view.setSize(view.getPreferredSize());
        layOut(view);

        var image = new BufferedImage(
                Math.max(1, view.getWidth()),
                Math.max(1, view.getHeight()),
                BufferedImage.TYPE_INT_ARGB);
        var g = image.createGraphics();

        try {
            view.paint(g);
        } finally {
            g.dispose();
        }
    }

    /**
     * What {@code validate} would have done, done by hand.
     * <p>
     * {@code Container.validate} lays nothing out while the component has no native peer, and a
     * view that is not in a window never gets one -- so every child would still be at its original
     * zero by zero and the paint below would draw nothing at all. {@code doLayout} is the part of
     * validating that does not need a peer, and this is it applied the whole way down.
     */
    private static void layOut(final Container container) {
        container.doLayout();

        for (var child : container.getComponents()) {
            if (child instanceof Container inner) {
                layOut(inner);
            }
        }
    }

    /**
     * The first component of a kind anywhere under {@code root}, so a test can drive a view through
     * the controls a person would use rather than through a field it has no business reaching into.
     */
    public static <T> T find(final Container root, final Class<T> type) {
        for (var child : root.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }

            if (child instanceof Container inner) {
                var found = find(inner, type);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }
}
