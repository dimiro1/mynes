package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.cart.Cart;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;

public class CHRViewerFrame extends JFrame {
    private final Component parent;
    private final Cart cart;
    private final JLabel selectedLabel = new JLabel("Tile: $00");
    private int baseAddress = 0;
    private int selectedTileNumber;

    public CHRViewerFrame(final Component parent, final Cart cart) {
        super();
        this.parent = parent;
        this.cart = cart;

        init();
    }

    private void init() {
        setTitle("CHR Viewer");
        setResizable(false);
        setLocationRelativeTo(parent);
        setLayout(new MigLayout());

        var tilesViewer = new TilesViewerPanel(cart);
        var selectedTile = new TileComponent(
                selectedTileNumber, baseAddress, 272, 272, cart.mapper());

        tilesViewer.addChangeListener(tile -> {
            selectedTileNumber = tile.getTileNumber();
            selectedLabel.setText(String.format("Tile: $%02X", selectedTileNumber));
            selectedTile.setTileNumber(selectedTileNumber);
            selectedTile.setBaseAddress(baseAddress);
            selectedTile.repaint();
        });

        var bankSelector = new JComboBox<CHRBank>();
        for (var i = 0; i < cart.chrROM().length / 0x1000; i++) {
            bankSelector.addItem(new CHRBank(i * 0x1000));
        }

        bankSelector.addActionListener(e -> {
            var selected = (CHRBank) bankSelector.getSelectedItem();
            if (selected != null) {
                baseAddress = selected.address;

                tilesViewer.setBaseAddress(selected.address);
                tilesViewer.repaint();

                selectedTile.setBaseAddress(baseAddress);
                selectedTile.repaint();
            }
        });

        var mode8x16 = new JCheckBox("Tiles 8x16 Mode");
        mode8x16.setSelected(tilesViewer.getMode() == TilesViewerPanel.Mode.MODE_8X16);
        mode8x16.addActionListener(e -> {
            tilesViewer.setMode(tilesViewer.getMode() == TilesViewerPanel.Mode.MODE_8X16
                    ? TilesViewerPanel.Mode.MODE_8X8
                    : TilesViewerPanel.Mode.MODE_8X16);
            tilesViewer.repaint();
        });

        add(selectedLabel, "span 1");
        add(new JLabel("Zoom"), "span 1, wrap");
        add(tilesViewer, "span 1");
        add(selectedTile, "span 1, growy, spany, wrap");
        add(mode8x16, "span 2, wrap");
        add(bankSelector, "span 1, grow");
        pack();
    }

    private record CHRBank(int address) {
        @Override
        public String toString() {
            return String.format("CHR: $%04X", address);
        }
    }
}
