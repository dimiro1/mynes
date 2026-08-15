package com.github.dimiro1.mynes.ui;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;

/**
 * How many screen pixels wide one of the picture's pixels is drawn.
 * <p>
 * Whole multiples only, and that is the point of the menu rather than a limitation of it. The window
 * has always been resizable and {@link ScreenComponent} has always fitted the picture to it, but a
 * corner dragged by hand lands on a fraction -- and at 2.3x some rows of the picture come out two
 * screen pixels tall and others three, which on a chequerboard or a line of text is a banding no
 * television ever showed. Picking a size here is how to get back to a multiple where every pixel is
 * the same size as every other one.
 * <p>
 * Four of them, because four is where a window stops being one: 4x is 1024x896, already taller than
 * a 900 line laptop display before the menu bar is counted. Past that is a full screen mode, which
 * is a different feature and not this one.
 */
public enum ScreenScale {
    ONE_TIMES(1),
    TWO_TIMES(2),
    THREE_TIMES(3),
    FOUR_TIMES(4);

    private static final Logger logger = System.getLogger("UI");

    private final int factor;
    private final String id;
    private final String label;

    ScreenScale(final int factor) {
        this.factor = factor;
        this.id = Integer.toString(factor);
        this.label = factor + "x";
    }

    /**
     * How many screen pixels a picture pixel becomes.
     */
    public int factor() {
        return factor;
    }

    /**
     * How this size is spelled in the config file: the bare number, since {@code video.scale=3} is
     * what somebody editing the file by hand would write.
     */
    public String id() {
        return id;
    }

    /**
     * How this size is spelled in the menu.
     */
    public String label() {
        return label;
    }

    /**
     * What the window opens at when nothing has said otherwise.
     * <p>
     * 256x224 is a postage stamp on a modern display, and 2x is 512x448, which fits on every display
     * anybody still has.
     */
    @SuppressWarnings("SameReturnValue")
    public static ScreenScale defaultScale() {
        return TWO_TIMES;
    }

    /**
     * The size {@code id} names, or the default if nothing does.
     * <p>
     * A number outside the four, a fraction, a misspelling and a size dropped in a later version all
     * cost the setting rather than the startup.
     */
    public static ScreenScale byId(final String id) {
        for (var scale : values()) {
            if (scale.id().equals(id)) {
                return scale;
            }
        }

        logger.log(Level.WARNING, id + " is not a screen size, falling back to " + defaultScale().id());

        return defaultScale();
    }
}
