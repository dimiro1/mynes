package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.cart.Cart;
import com.github.dimiro1.mynes.ui.chrviewer.CHRViewerFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class GameUIFrame extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger("UI");

    private final JFileChooser fileChooser;
    private CHRViewerFrame chrViewerFrame;
    private Cart cart;
    private NES nes;

    public GameUIFrame() {
        super("MyNES");

        var filter = new FileNameExtensionFilter("iNES", "nes");
        fileChooser = new JFileChooser();
        fileChooser.addChoosableFileFilter(filter);
        fileChooser.setFileFilter(filter);

        init();
    }

    private void init() {
        setPreferredSize(new Dimension(256, 224));

        var command = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        JMenuBar menuBar = new JMenuBar();

        JMenu fileMenu = new JMenu("File");
        fileMenu.setMnemonic(KeyEvent.VK_F);

        JMenuItem fileMenuOpen = new JMenuItem("Open...", KeyEvent.VK_O);
        fileMenuOpen.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_O, command));
        fileMenu.add(fileMenuOpen);

        JMenuItem fileMenuQuit = new JMenuItem("Quit", KeyEvent.VK_Q);
        fileMenuQuit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, command));
        fileMenu.add(fileMenuQuit);

        JMenu debugMenu = new JMenu("Debug");
        debugMenu.setMnemonic(KeyEvent.VK_D);

        JMenuItem debugMenuCHRViewer = new JMenuItem("CHR Viewer", KeyEvent.VK_C);

        debugMenu.add(debugMenuCHRViewer);
        debugMenu.setEnabled(false);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem helpMenuAbout = new JMenuItem("About", KeyEvent.VK_A);
        helpMenu.add(helpMenuAbout);

        menuBar.add(fileMenu);
        menuBar.add(debugMenu);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);

        fileMenuOpen.addActionListener(e -> {
            if (chrViewerFrame != null) {
                logger.debug("closing chrViewerFrame");
                destroyCHRViewerFrame();
            }

            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    loadRom(fileChooser.getSelectedFile());
                    debugMenu.setEnabled(true);
                } catch (IOException ex) {
                    logger.error("failed to load rom", ex);
                }
            }
        });


        debugMenuCHRViewer.addActionListener(
                e -> {
                    if (cart == null) {
                        logger.error("cartridge is not loaded");
                        JOptionPane.showMessageDialog(
                                this,
                                "Cartridge is not loaded",
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                        return;
                    }

                    if (chrViewerFrame == null) {
                        chrViewerFrame = new CHRViewerFrame(this, cart);
                    }

                    chrViewerFrame.setVisible(true);
                }
        );

        fileMenuQuit.addActionListener(e ->
                this.dispatchEvent(new WindowEvent(this, WindowEvent.WINDOW_CLOSING)));

        pack();
        setLocationRelativeTo(null);
    }

    private void loadRom(final File selectedFile) throws IOException {
        logger.info("loading rom {}", selectedFile.getName());

        var rom = new FileInputStream(selectedFile);
        cart = Cart.load(rom.readAllBytes(), selectedFile.getName());
        nes = new NES(cart);

        logger.info("loaded rom {}", selectedFile.getName());
    }

    private void destroyCHRViewerFrame() {
        chrViewerFrame.setVisible(false);
        chrViewerFrame = null;
    }
}
