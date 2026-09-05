package com.github.dimiro1.mynes.ui;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;

/**
 * The Pause tick, in every debug window rather than only in the one with the Machine menu.
 * <p>
 * A person watching the CHR viewer or the palette viewer is watching something that will not hold
 * still, and until now stopping it meant finding the game window, pausing it there, and coming
 * back -- by which time whatever was worth looking at had been drawn over. So the tick is in each
 * of those windows, and so is the shortcut the menu carries, because the window that has the
 * keyboard is the window the shortcut has to work in.
 * <p>
 * <b>It follows the machine rather than remembering what it was last told.</b> Pause is reachable
 * from the Machine menu, from four debug windows and from every breakpoint the debugger has, so a
 * tick that only moved when it was clicked would be wrong within a minute. So it keeps its own
 * {@link Sweep} rather than being refreshed by whatever it was put in: following the machine is the
 * whole of its job, and a tick that only did it where somebody had remembered to ask would be the
 * bug it exists to prevent.
 */
public final class PauseBox extends JCheckBox {
    /**
     * The same quarter second the debug views sweep on, and for the same reason: fast enough that
     * the tick is never visibly behind the machine, and two reads of a boolean either way.
     */
    private static final int REFRESH_MILLIS = 250;

    private final PauseControl control;

    public PauseBox(final PauseControl control) {
        super("Pause");

        this.control = control;

        setEnabled(control.isReal());
        setToolTipText("Stop the machine where it is");
        addActionListener(e -> control.setPaused(isSelected()));

        refresh();

        Sweep.every(REFRESH_MILLIS, this, this::refresh);
    }

    /**
     * Brings the tick into line with the machine, whoever stopped it.
     * <p>
     * {@code setSelected} is deliberately not {@code doClick}: it moves the tick without firing the
     * listener, so following the machine cannot turn into telling it what to do.
     */
    public void refresh() {
        if (control.isReal() && isSelected() != control.isPaused()) {
            setSelected(control.isPaused());
        }
    }

    /**
     * Puts the Machine menu's own shortcut on the window this ends up in.
     * <p>
     * {@code WHEN_IN_FOCUSED_WINDOW}, because the point is that it works wherever in the window the
     * focus happens to be -- in the middle of a table of sprites, or on a swatch.
     */
    public void installIn(final JRootPane root) {
        if (!control.isReal()) {
            return;
        }

        var stroke = KeyStroke.getKeyStroke(KeyEvent.VK_P, MenuKey.mask());

        root.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(stroke, "mynes.pause");
        root.getActionMap().put("mynes.pause", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                doClick();
            }
        });

        setToolTipText("Stop the machine where it is (" + MenuKey.text() + "+P)");
    }
}
