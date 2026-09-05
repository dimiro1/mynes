package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.PauseBox;
import com.github.dimiro1.mynes.ui.PauseControl;

import javax.swing.JFrame;
import java.awt.Component;

/**
 * A window with {@link CHRViewerPanel} in it and nothing else. See
 * {@link com.github.dimiro1.mynes.ui.ppuviewer.NametableViewerFrame} for what a frame is still for
 * once the view is a panel.
 */
public final class CHRViewerFrame extends JFrame {
    private final CHRViewerPanel view;

    public CHRViewerFrame(
            final Component parent,
            final Cart cart,
            final PPU ppu,
            final NESPalette palette,
            final PauseControl pauseControl) {

        var pause = new PauseBox(pauseControl);

        this.view = new CHRViewerPanel(cart, ppu, palette, pause);

        setTitle("CHR Viewer");
        setResizable(false);

        add(view);
        pack();

        pause.installIn(getRootPane());
        setLocationRelativeTo(parent);
    }

    /**
     * Draws the tiles in {@code palette} from now on, following Settings &gt; Palette...
     */
    public void setPalette(final NESPalette palette) {
        view.setPalette(palette);
    }
}
