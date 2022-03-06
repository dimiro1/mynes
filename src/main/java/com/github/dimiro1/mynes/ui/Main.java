package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.cart.Cart;
import com.github.dimiro1.mynes.ui.ppu.CHRViewer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.io.FileInputStream;
import java.io.IOException;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws IOException {
        var rom = new FileInputStream(args[0]);
        var cart = Cart.load(rom.readAllBytes(), args[0]);
        logger.info("MyNES");

        SwingUtilities.invokeLater(() -> {
            var chrViewer = new CHRViewer(cart.mapper());

            chrViewer.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            chrViewer.setVisible(true);
        });
    }
}
