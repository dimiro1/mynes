package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.PauseBox;
import com.github.dimiro1.mynes.ui.PauseControl;

import javax.swing.JFrame;
import java.awt.Component;

/**
 * A window with {@link OAMViewerPanel} in it and nothing else. See {@link NametableViewerFrame}
 * for what a frame is still for once the view is a panel.
 */
public final class OAMViewerFrame extends JFrame {
    private final OAMViewerPanel view;

    public OAMViewerFrame(
            final Component parent,
            final PPU ppu,
            final NESPalette palette,
            final PauseControl pauseControl) {

        var pause = new PauseBox(pauseControl);

        this.view = new OAMViewerPanel(ppu, palette, pause);

        setTitle("OAM Viewer");
        setResizable(false);

        add(view);
        pack();

        pause.installIn(getRootPane());
        setLocationRelativeTo(parent);
    }

    /**
     * Draws the sprites in {@code palette} from now on, following Settings &gt; Palette...
     */
    public void setPalette(final NESPalette palette) {
        view.setPalette(palette);
    }
}
