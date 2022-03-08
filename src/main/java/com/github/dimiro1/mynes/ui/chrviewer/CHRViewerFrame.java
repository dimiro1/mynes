package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.cart.mappers.Mapper;

import javax.swing.*;
import java.awt.*;

public class CHRViewerFrame extends JFrame {
    private final Component parent;
    private final Mapper mapper;
    private final JLabel selectedTileTopLabel = new JLabel("Selected: $00");
    private final JLabel selectedTileBottomLabel = new JLabel("Selected: $00");

    public CHRViewerFrame(final Component parent, final Mapper mapper) {
        super();
        this.parent = parent;
        this.mapper = mapper;

        init();
    }

    private void init() {
        setTitle("CHR Viewer");
        setResizable(false);
        setLocationRelativeTo(parent);
        setLayout(new BorderLayout());

        var tilesTopViewer = new TilesViewerPanel(mapper);
        var tilesBottomViewer = new TilesViewerPanel(mapper, 0x1000);

        tilesTopViewer.addChangeListener(tile ->
                selectedTileTopLabel.setText(String.format("Selected: $%02X", tile.getTileNumber())));

        tilesBottomViewer.addChangeListener(tile ->
                selectedTileBottomLabel.setText(String.format("Selected: $%02X", tile.getTileNumber())));

        var tilesTopInfoPanel = new JPanel();
        tilesTopInfoPanel.add(selectedTileTopLabel);

        var tilesBottomInfoPanel = new JPanel();
        tilesBottomInfoPanel.add(selectedTileBottomLabel);

        var tilesTopViewerContainer = new JPanel();
        tilesTopViewerContainer.setLayout(new BorderLayout());
        tilesTopViewerContainer.setBorder(BorderFactory.createEmptyBorder(5, 5, 0, 5));
        tilesTopViewerContainer.add(tilesTopViewer, BorderLayout.NORTH);
        tilesTopViewerContainer.add(tilesTopInfoPanel, BorderLayout.SOUTH);

        var tilesBottomViewerContainer = new JPanel();
        tilesBottomViewerContainer.setLayout(new BorderLayout());
        tilesBottomViewerContainer.setBorder(BorderFactory.createEmptyBorder(0, 5, 5, 5));
        tilesBottomViewerContainer.add(tilesBottomViewer, BorderLayout.NORTH);
        tilesBottomViewerContainer.add(tilesBottomInfoPanel, BorderLayout.SOUTH);

        var menuBar = new JMenuBar();
        setJMenuBar(menuBar);

        var viewMenu = new JMenu("View");
        menuBar.add(viewMenu);

        var viewMenuItem8x8 = new JRadioButtonMenuItem("8x8");
        viewMenuItem8x8.setSelected(tilesTopViewer.getMode() == TilesViewerPanel.Mode.MODE_8X8);
        viewMenu.add(viewMenuItem8x8);

        var viewMenuItem8x16 = new JRadioButtonMenuItem("8x16");
        viewMenuItem8x16.setSelected(tilesTopViewer.getMode() == TilesViewerPanel.Mode.MODE_8X16);
        viewMenu.add(viewMenuItem8x16);

        viewMenuItem8x8.addActionListener(e -> {
            viewMenuItem8x16.setSelected(false);
            tilesTopViewer.setMode(TilesViewerPanel.Mode.MODE_8X8);
            tilesBottomViewer.setMode(TilesViewerPanel.Mode.MODE_8X8);
        });

        viewMenuItem8x16.addActionListener(e -> {
            viewMenuItem8x8.setSelected(false);
            tilesTopViewer.setMode(TilesViewerPanel.Mode.MODE_8X16);
            tilesBottomViewer.setMode(TilesViewerPanel.Mode.MODE_8X16);
        });

        add(tilesTopViewerContainer, BorderLayout.NORTH);
        add(tilesBottomViewerContainer, BorderLayout.SOUTH);
        pack();
    }
}
