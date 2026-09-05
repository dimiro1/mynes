package com.github.dimiro1.mynes.ui;

import java.awt.GraphicsEnvironment;
import java.awt.Toolkit;
import java.awt.event.InputEvent;

/**
 * The key a menu shortcut is held with: command on a Mac, Ctrl everywhere else.
 * <p>
 * Only the toolkit knows which, and a machine with no display has no toolkit to ask -- it throws
 * rather than answering. So this answers Ctrl there, which is not an invented fallback but the very
 * answer {@link Toolkit#getMenuShortcutKeyMaskEx()} itself gives on every platform that has not
 * overridden it.
 * <p>
 * That one line is what lets every view in the front end be built and painted by a test on the
 * machine that runs the build: three of them ask this question while they are being put together,
 * and a window is not needed for any of the rest of it.
 */
public final class MenuKey {
    private MenuKey() {
    }

    /**
     * The modifier mask, for a {@link javax.swing.KeyStroke}.
     */
    public static int mask() {
        return GraphicsEnvironment.isHeadless()
                ? InputEvent.CTRL_DOWN_MASK
                : Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
    }

    /**
     * The same key spelled for a person, which is a symbol on a Mac and a word elsewhere.
     */
    public static String text() {
        return InputEvent.getModifiersExText(mask());
    }
}
