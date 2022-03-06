package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.cart.Cart;
import com.github.dimiro1.mynes.cart.mappers.Mapper;
import com.github.dimiro1.mynes.ui.ppu.CHRViewer;

import javax.swing.*;
import java.awt.*;

public class GameUI extends JFrame {
    private CHRViewer chrViewer;
    private final Mapper mapper;

    public GameUI(final Cart cart) {
        super("MyNES");
        mapper = cart.mapper();
        init();
    }

    private void init() {
        setPreferredSize(new Dimension(256, 224));

        var container = new JPanel();
        container.setLayout(new SpringLayout());

        var charViewerButton = new JButton("CHR Viewer");
        charViewerButton.addActionListener(e -> {
            if (chrViewer == null) {
                chrViewer = new CHRViewer(this, mapper);
            }
            if (!chrViewer.isVisible()) {
                chrViewer.setVisible(true);
            }
        });
        container.add(charViewerButton);

        add(container);
        pack();
    }
}
