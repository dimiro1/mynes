package com.github.dimiro1.mynes.ui;

import com.formdev.flatlaf.FlatLightLaf;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    static void main() {
        FlatLightLaf.setup();

        logger.info("MyNES");

        SwingUtilities.invokeLater(() -> {
            var frame = new GameUIFrame();
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });
    }
}