package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.ui.chrviewer.CHRViewerFrame;
import com.github.dimiro1.mynes.ui.input.ControllerSettingsDialog;
import com.github.dimiro1.mynes.ui.input.KeyBindings;
import com.github.dimiro1.mynes.ui.input.KeyboardInput;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;

public class GameUIFrame extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger("UI");

    private final JFileChooser fileChooser;
    private final ScreenComponent screen = new ScreenComponent();
    private final KeyboardInput keyboardInput;
    private CHRViewerFrame chrViewerFrame;
    private KeyBindings keyBindings;
    private Cart cart;
    private NES nes;
    private EmulatorRunner runner;

    public GameUIFrame() {
        super("MyNES");

        var filter = new FileNameExtensionFilter("iNES", "nes");
        fileChooser = new JFileChooser();
        fileChooser.addChoosableFileFilter(filter);
        fileChooser.setFileFilter(filter);

        keyBindings = KeyBindings.load(KeyBindings.DEFAULT_PATH);
        keyboardInput = new KeyboardInput(this, keyBindings);

        init();
    }

    private void init() {
        add(screen, BorderLayout.CENTER);

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

        JMenu settingsMenu = new JMenu("Settings");
        settingsMenu.setMnemonic(KeyEvent.VK_S);

        JMenuItem settingsMenuController = new JMenuItem("Controller...", KeyEvent.VK_C);
        settingsMenu.add(settingsMenuController);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem helpMenuAbout = new JMenuItem("About", KeyEvent.VK_A);
        helpMenu.add(helpMenuAbout);

        menuBar.add(fileMenu);
        menuBar.add(debugMenu);
        menuBar.add(settingsMenu);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);

        // Every key event in the application comes past here before anything else sees it, which
        // is how the game gets the arrow keys without taking them off the menu bar.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyboardInput);

        settingsMenuController.addActionListener(e ->
                new ControllerSettingsDialog(this, keyBindings, updated -> {
                    keyBindings = updated;
                    keyboardInput.setBindings(updated);
                }).setVisible(true));

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


        // The viewer reads the mapper's character memory from this thread while the emulation
        // thread runs. Deliberately unsynchronised: reading an array element cannot tear, so the
        // worst case is a debug window showing a tile a frame out of date.
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

        // Quit goes through here too, and Main closes on WINDOW_CLOSING.
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(final WindowEvent e) {
                if (runner != null) {
                    runner.stop();
                }
            }

            // Cmd-tabbing away in the middle of a jump would otherwise leave the button held down
            // for as long as the window is gone: the release lands in whatever took the focus.
            @Override
            public void windowDeactivated(final WindowEvent e) {
                keyboardInput.releaseAll();
            }
        });

        pack();
        setLocationRelativeTo(null);
    }

    /**
     * Loads a ROM and starts running it, replacing whatever was running before.
     * <p>
     * The cartridge is parsed before the running machine is touched, so a file that turns out not
     * to be a ROM leaves the current game playing.
     */
    private void loadRom(final File selectedFile) throws IOException {
        logger.info("loading rom {}", selectedFile.getName());

        Cart loaded;
        try (var rom = new FileInputStream(selectedFile)) {
            loaded = Cart.load(rom.readAllBytes(), selectedFile.getName());
        }

        if (runner != null) {
            runner.stop();
        }

        cart = loaded;
        nes = new NES(cart);

        // Each machine brings its own controllers, so the keyboard has to be pointed at the new
        // one. Nothing races: the old runner has already stopped and this is the event dispatch
        // thread, which is the only thread the dispatcher runs on.
        keyboardInput.releaseAll();
        keyboardInput.setController(nes.getController1());

        runner = new EmulatorRunner(nes, screen);
        runner.start();

        logger.info("loaded rom {}", selectedFile.getName());
    }

    private void destroyCHRViewerFrame() {
        chrViewerFrame.setVisible(false);
        chrViewerFrame = null;
    }
}
