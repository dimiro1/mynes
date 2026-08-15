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
 * <p>
 * The same four are what Settings &gt; Screenshot Size offers, since the question a screenshot asks
 * is the same one -- how many pixels wide a picture pixel comes out -- and the answer wants to be a
 * whole number there for the same reason. Only the default differs, and
 * {@link #defaultScreenshotScale()} says why.
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
    public static ScreenScale defaultScale() {
        return TWO_TIMES;
    }

    /**
     * What a screenshot is magnified by when nothing has said otherwise.
     * <p>
     * 1x rather than the window's 2x, because a file is not a window: this is the frame exactly as
     * the machine drew it, and anything that shows it can make it bigger. Magnifying on the way out
     * quadruples the file for pixels that carry nothing, and it cannot be undone by whoever opens
     * it. The larger sizes are for a picture headed somewhere that will not scale it with square
     * pixels.
     */
    public static ScreenScale defaultScreenshotScale() {
        return ONE_TIMES;
    }

    /**
     * The size {@code id} names, or the default if nothing does.
     * <p>
     * A number outside the four, a fraction, a misspelling and a size dropped in a later version all
     * cost the setting rather than the startup.
     */
    public static ScreenScale byId(final String id) {
        return byId(id, defaultScale());
    }

    /**
     * The same, for a setting whose default is not the window's -- the screenshot size, whose
     * missing entry and whose unreadable one have to mean the same thing.
     */
    public static ScreenScale byId(final String id, final ScreenScale fallback) {
        for (var scale : values()) {
            if (scale.id().equals(id)) {
                return scale;
            }
        }

        logger.log(Level.WARNING, id + " is not a screen size, falling back to " + fallback.id());

        return fallback;
    }
}
