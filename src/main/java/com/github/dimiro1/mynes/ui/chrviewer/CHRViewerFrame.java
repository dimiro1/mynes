package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.cart.Cart;

import javax.swing.*;
import java.awt.*;

public class CHRViewerFrame extends JFrame {
    private final Component parent;
    private final Cart cart;
    private final JLabel selectedTileTopLabel = new JLabel("Selected: $00");
    private final JLabel selectedTileBottomLabel = new JLabel("Selected: $00");

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
        setLayout(new BorderLayout());

        var tilesViewer = new TilesViewerPanel(cart);

        tilesViewer.addChangeListener(tile ->
                selectedTileBottomLabel.setText(String.format("Selected: $%02X", tile.getTileNumber())));

        var tilesTopInfoPanel = new JPanel();
        tilesTopInfoPanel.add(selectedTileTopLabel);

        var tilesInfoPanel = new JPanel();
        tilesInfoPanel.add(selectedTileBottomLabel);

        var bankSelector = new JComboBox<CHRBank>();
        for (var i = 0; i < cart.chrROM().length / 0x1000; i++) {
            bankSelector.addItem(new CHRBank(i * 0x1000));
        }

        bankSelector.addActionListener(e -> {
            var selected = (CHRBank) bankSelector.getSelectedItem();
            if (selected != null) {
                tilesViewer.setBaseAddress(selected.address);
            }
        });

        var bankSelectorPanel = new JPanel();
        bankSelectorPanel.setLayout(new BorderLayout());
        bankSelectorPanel.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        bankSelectorPanel.add(bankSelector, BorderLayout.NORTH);

        var tilesViewerContainer = new JPanel();
        tilesViewerContainer.setLayout(new BorderLayout());
        tilesViewerContainer.setBorder(BorderFactory.createEmptyBorder(5, 5, 5, 5));
        tilesViewerContainer.add(tilesViewer, BorderLayout.NORTH);
        tilesViewerContainer.add(tilesInfoPanel, BorderLayout.SOUTH);

        var menuBar = new JMenuBar();
        setJMenuBar(menuBar);

        var viewMenu = new JMenu("View");
        menuBar.add(viewMenu);

        var viewMenuItem8x8 = new JRadioButtonMenuItem("8x8");
        viewMenuItem8x8.setSelected(tilesViewer.getMode() == TilesViewerPanel.Mode.MODE_8X8);
        viewMenu.add(viewMenuItem8x8);

        var viewMenuItem8x16 = new JRadioButtonMenuItem("8x16");
        viewMenuItem8x16.setSelected(tilesViewer.getMode() == TilesViewerPanel.Mode.MODE_8X16);
        viewMenu.add(viewMenuItem8x16);

        viewMenuItem8x8.addActionListener(e -> {
            viewMenuItem8x16.setSelected(false);
            tilesViewer.setMode(TilesViewerPanel.Mode.MODE_8X8);
        });

        viewMenuItem8x16.addActionListener(e -> {
            viewMenuItem8x8.setSelected(false);
            tilesViewer.setMode(TilesViewerPanel.Mode.MODE_8X16);
        });

        add(tilesViewerContainer, BorderLayout.NORTH);
        add(bankSelectorPanel, BorderLayout.SOUTH);
        pack();
    }

    private record CHRBank(int address) {
        @Override
        public String toString() {
            return String.format("$%04X", address);
        }
    }
}
