package com.github.dimiro1.mynes.ui.ppu;

import com.github.dimiro1.mynes.cart.mappers.Mapper;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;

public class CHRViewer extends JFrame {
    private final Mapper mapper;
    private Tile selectedTile;
    private final String title = "CHR Viewer";

    public CHRViewer(final Mapper mapper) {
        super();
        this.mapper = mapper;

        setTitle(title);
        setSize(272, 574);
        setResizable(false);
        setLocationRelativeTo(null);
        addComponents();
    }

    private void addComponents() {
        var tilesPanel = new JPanel();
        tilesPanel.setBackground(Color.BLACK);
        tilesPanel.setLayout(new GridLayout(32, 16, 1, 1));

        for (var i = 0; i < 512; i++) {
            var tile = new Tile(i, mapper);

            if (i == 0) {
                selectTile(tile);
            }

            tile.addMouseListener(new MouseListener() {
                @Override
                public void mouseClicked(final MouseEvent e) {
                    selectTile(tile);
                }

                @Override
                public void mouseReleased(final MouseEvent e) { /* Not used */ }

                @Override
                public void mousePressed(final MouseEvent e) { /* Not used */ }

                @Override
                public void mouseEntered(final MouseEvent e) { /* Not used */ }

                @Override
                public void mouseExited(final MouseEvent e) { /* Not used */ }
            });

            tilesPanel.add(tile);
        }

        add(tilesPanel);
    }

    private void selectTile(final Tile tile) {
        if (selectedTile != null) {
            selectedTile.removeHighlight();
        }

        selectedTile = tile;
        tile.highlight();

        setTitle(String.format("%s ($%02X)", title, tile.getTileIndex()));
    }
}
