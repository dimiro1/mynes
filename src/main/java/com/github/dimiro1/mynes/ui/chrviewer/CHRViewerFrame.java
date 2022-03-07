package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.cart.mappers.Mapper;

import javax.swing.*;
import java.awt.*;

public class CHRViewerFrame extends JFrame {
    private final Component parent;
    private final Mapper mapper;
    private final JLabel selectedTileLabel = new JLabel("Selected: $00");

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

        var tilesViewer = new TilesViewerPanel(mapper);

        tilesViewer.addChangeListener(tile ->
                selectedTileLabel.setText(String.format("Selected: $%02X", tile.getTileIndex())));

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

        add(tilesViewer, BorderLayout.NORTH);

        var infoPanel = new JPanel();
        infoPanel.add(selectedTileLabel);
        add(infoPanel, BorderLayout.SOUTH);
        pack();
    }
}
