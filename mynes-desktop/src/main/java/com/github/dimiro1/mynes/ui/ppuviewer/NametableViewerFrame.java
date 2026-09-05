package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.PauseBox;
import com.github.dimiro1.mynes.ui.PauseControl;

import javax.swing.JFrame;
import java.awt.Component;

/**
 * A window with {@link NametableViewerPanel} in it and nothing else.
 * <p>
 * The view is a panel because it is going to be one tab of the control panel, and everything only a
 * window can give is here rather than in it: the title, the size it is packed to, and the root pane
 * the Machine menu's shortcut is bound on. Nothing here stops the view's sweep on the way out --
 * the sweep runs off the panel being on screen, and a window being disposed is a panel that is not.
 */
public final class NametableViewerFrame extends JFrame {
    private final NametableViewerPanel view;

    public NametableViewerFrame(
            final Component parent,
            final NES nes,
            final NESPalette palette,
            final PauseControl pauseControl) {

        var pause = new PauseBox(pauseControl);

        this.view = new NametableViewerPanel(nes, palette, pause);

        setTitle("Nametable Viewer");
        setResizable(false);

        add(view);
        pack();

        pause.installIn(getRootPane());
        setLocationRelativeTo(parent);
    }

    /**
     * Draws the nametables in {@code palette} from now on, following Settings &gt; Palette...
     */
    public void setPalette(final NESPalette palette) {
        view.setPalette(palette);
    }
}
