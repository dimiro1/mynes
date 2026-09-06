package com.github.dimiro1.mynes.ui.controlpanel;

import org.jetbrains.annotations.Nullable;

import java.awt.Rectangle;

/**
 * Where the control panel was and how it was arranged, remembered between runs.
 * <p>
 * The debugger window remembered nothing, deliberately: its panes were sized for whichever bug was
 * being chased, and a window that opened at the shape of the last one is a small trap. This is a
 * different thing -- somebody sizes a control panel once for their display and then leaves it --
 * which is the same kind of answer as where a window has been dragged to.
 * <p>
 * Every field is allowed to say nothing, and says nothing until the panel has been opened once.
 * A null anywhere means "use the default", which is what a fresh install and a hand-edited config
 * file both are.
 *
 * @param bounds where the window was and how big, or null for the first time it is opened.
 * @param tab    the title of the instrument that was in front, or null for the first one. Held as a
 *               title rather than an index because the tabs are going to be added to.
 */
public record Layout(@Nullable Rectangle bounds, @Nullable String tab) {
    /**
     * What a config file that has never seen a control panel answers.
     */
    public static final Layout DEFAULT = new Layout(null, null);
}
