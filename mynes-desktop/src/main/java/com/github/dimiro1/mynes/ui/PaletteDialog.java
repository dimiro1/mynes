package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.palette.Palettes;

import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.util.function.Consumer;

/**
 * Settings &gt; Palette...: the palettes on the left, the 64 colours of the selected one on the
 * right.
 * <p>
 * There is no OK or Cancel. Every selection takes effect the moment it is made, which is what makes
 * comparing palettes against the running game a matter of holding Down: the picture behind the
 * dialog changes as the selection moves. Being modal does not get in the way of that -- emulation
 * runs on its own thread, and a repaint posted from it is served by the dialog's event pump like
 * any other.
 * <p>
 * It works with the emulator paused too. The screen recolours the frame it already has rather than
 * waiting for the next one.
 */
public class PaletteDialog extends JDialog {
    private final SwatchGrid swatches = new SwatchGrid();
    private final Consumer<NESPalette> onChange;

    public PaletteDialog(
            final Frame owner,
            final NESPalette selected,
            final Consumer<NESPalette> onChange) {
        super(owner, "Palette", true);

        this.onChange = onChange;

        init(selected);
    }

    private void init(final NESPalette selected) {
        setResizable(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new MigLayout());

        var palettes = Palettes.all();

        var list = new JList<>(palettes.toArray(new NESPalette[0]));
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setVisibleRowCount(palettes.size());

        // Selected before the listener goes on, so that opening the dialog does not count as
        // picking the palette that is already in use and rewrite the config file for nothing.
        list.setSelectedValue(selected, true);
        swatches.setPalette(selected);

        list.addListSelectionListener(e -> {
            if (e.getValueIsAdjusting()) {
                return;
            }

            var chosen = list.getSelectedValue();
            if (chosen != null) {
                swatches.setPalette(chosen);
                onChange.accept(chosen);
            }
        });

        var close = new JButton("Close");
        close.addActionListener(e -> dispose());

        add(new JScrollPane(list), "growy");
        add(swatches, "top, wrap");
        add(close, "span 2, align right");

        pack();
        setLocationRelativeTo(getOwner());
    }

    /**
     * The 64 colours a palette names, as 4 rows of 16 -- the layout every NES reference chart uses,
     * where a row is one brightness and a column one hue, so two palettes can be told apart at a
     * glance by the shape of the difference.
     * <p>
     * Emphasis is left out. It is a thing $2001 does to a picture rather than a property of the
     * palette, and eight copies of the grid would say nothing the one says.
     */
    private static final class SwatchGrid extends JComponent {
        private static final int COLUMNS = 16;
        private static final int ROWS = 4;

        /**
         * Big enough to see the colour, small enough that the whole chart sits next to the list.
         */
        private static final int CELL = 24;

        private NESPalette palette = Palettes.defaultPalette();

        SwatchGrid() {
            setPreferredSize(new Dimension(COLUMNS * CELL, ROWS * CELL));
            setOpaque(true);

            // Tooltips are only offered for components the manager knows about, and the text itself
            // comes from getToolTipText(MouseEvent) below, which changes per swatch.
            ToolTipManager.sharedInstance().registerComponent(this);
        }

        void setPalette(final NESPalette palette) {
            this.palette = palette;
            repaint();
        }

        @Override
        protected void paintComponent(final Graphics g) {
            super.paintComponent(g);

            for (var entry = 0; entry < COLUMNS * ROWS; entry++) {
                g.setColor(new Color(palette.colour(entry)));
                g.fillRect((entry % COLUMNS) * CELL, (entry / COLUMNS) * CELL, CELL, CELL);
            }
        }

        /**
         * The entry number and the colour under the pointer, which is the answer to "what is that
         * one" when two palettes differ in a single swatch.
         */
        @Override
        public String getToolTipText(final MouseEvent event) {
            var column = event.getX() / CELL;
            var row = event.getY() / CELL;

            if (column < 0 || column >= COLUMNS || row < 0 || row >= ROWS) {
                return null;
            }

            var entry = row * COLUMNS + column;

            return String.format("$%02X  #%06X", entry, palette.colour(entry) & 0xFFFFFF);
        }
    }
}
