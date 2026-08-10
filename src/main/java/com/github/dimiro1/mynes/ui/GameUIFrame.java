package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.ui.chrviewer.CHRViewerFrame;
import com.github.dimiro1.mynes.ui.input.ControllerSettingsDialog;
import com.github.dimiro1.mynes.ui.input.KeyboardInput;
import com.github.dimiro1.mynes.ui.palette.PaletteDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
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

    /**
     * Everything remembered between runs, and the only thing that writes the config file.
     */
    private final Config config;

    // Menus and items that outlive init(): they are enabled, ticked and read back as machines
    // come and go.
    private final JMenu machineMenu = new JMenu("Machine");
    private final JMenu debugMenu = new JMenu("Debug");
    private final JCheckBoxMenuItem machineMenuPause = new JCheckBoxMenuItem("Pause");
    private final JCheckBoxMenuItem debugMenuBackground = new JCheckBoxMenuItem("Show Background", true);
    private final JCheckBoxMenuItem debugMenuSprites = new JCheckBoxMenuItem("Show Sprites", true);

    private CHRViewerFrame chrViewerFrame;
    private Cart cart;
    private NES nes;
    private EmulatorRunner runner;

    public GameUIFrame() {
        super("MyNES");

        var filter = new FileNameExtensionFilter("iNES", "nes");
        fileChooser = new JFileChooser();
        fileChooser.addChoosableFileFilter(filter);
        fileChooser.setFileFilter(filter);

        config = Config.load(Config.DEFAULT_PATH);
        keyboardInput = new KeyboardInput(this, config.keyBindings());
        screen.setPalette(config.palette());

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

        machineMenu.setMnemonic(KeyEvent.VK_M);
        machineMenu.setEnabled(false);

        JMenuItem machineMenuReset = new JMenuItem("Reset", KeyEvent.VK_R);
        machineMenuReset.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, command));
        machineMenu.add(machineMenuReset);

        JMenuItem machineMenuPowerCycle = new JMenuItem("Power Cycle", KeyEvent.VK_C);
        machineMenuPowerCycle.setAccelerator(
                KeyStroke.getKeyStroke(KeyEvent.VK_R, command | InputEvent.SHIFT_DOWN_MASK));
        machineMenu.add(machineMenuPowerCycle);

        machineMenu.addSeparator();

        machineMenuPause.setMnemonic(KeyEvent.VK_P);
        machineMenuPause.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, command));
        machineMenu.add(machineMenuPause);

        debugMenu.setMnemonic(KeyEvent.VK_D);
        debugMenu.setEnabled(false);

        JMenuItem debugMenuCHRViewer = new JMenuItem("CHR Viewer", KeyEvent.VK_C);
        debugMenu.add(debugMenuCHRViewer);

        debugMenu.addSeparator();

        debugMenuBackground.setMnemonic(KeyEvent.VK_B);
        debugMenu.add(debugMenuBackground);

        debugMenuSprites.setMnemonic(KeyEvent.VK_S);
        debugMenu.add(debugMenuSprites);

        JMenu settingsMenu = new JMenu("Settings");
        settingsMenu.setMnemonic(KeyEvent.VK_S);

        JMenuItem settingsMenuController = new JMenuItem("Controller...", KeyEvent.VK_C);
        settingsMenu.add(settingsMenuController);

        JMenuItem settingsMenuPalette = new JMenuItem("Palette...", KeyEvent.VK_P);
        settingsMenu.add(settingsMenuPalette);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem helpMenuAbout = new JMenuItem("About", KeyEvent.VK_A);
        helpMenu.add(helpMenuAbout);

        menuBar.add(fileMenu);
        menuBar.add(machineMenu);
        menuBar.add(debugMenu);
        menuBar.add(settingsMenu);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);

        // Every key event in the application comes past here before anything else sees it, which
        // is how the game gets the arrow keys without taking them off the menu bar.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyboardInput);

        settingsMenuController.addActionListener(e ->
                new ControllerSettingsDialog(this, config.keyBindings(), updated -> {
                    config.setKeyBindings(updated);
                    keyboardInput.setBindings(updated);
                    saveConfig();
                }).setVisible(true));

        // The palette belongs to the television rather than to the machine, so this is front end
        // state all the way down: no ROM has to be loaded, nothing has to be posted to the
        // emulation thread, and a power cycle cannot lose it.
        settingsMenuPalette.addActionListener(e ->
                new PaletteDialog(this, config.palette(), chosen -> {
                    config.setPalette(chosen);
                    screen.setPalette(chosen);

                    if (chrViewerFrame != null) {
                        chrViewerFrame.setPalette(chosen);
                    }

                    saveConfig();
                }).setVisible(true));

        fileMenuOpen.addActionListener(e -> {
            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                try {
                    loadRom(fileChooser.getSelectedFile());
                } catch (IOException ex) {
                    logger.error("failed to load rom", ex);
                }
            }
        });

        // The reset button on the console: memory survives, the CPU restarts through its reset
        // vector. Posted rather than called because only the emulation thread touches the NES.
        machineMenuReset.addActionListener(e -> {
            if (runner != null) {
                runner.post(nes::reset);
            }
        });

        // Pulling the power instead: a brand new machine, built from the cartridge already in
        // the slot.
        machineMenuPowerCycle.addActionListener(e -> {
            if (cart != null) {
                startMachine(cart);
            }
        });

        machineMenuPause.addActionListener(e -> {
            if (runner == null) {
                return;
            }

            runner.setPaused(machineMenuPause.isSelected());

            // A button held down when the game froze would otherwise still be held on resume,
            // minutes later, whether or not the key is.
            if (machineMenuPause.isSelected()) {
                keyboardInput.releaseAll();
            }

            updateTitle();
        });

        debugMenuBackground.addActionListener(e -> {
            if (runner != null) {
                var ppu = nes.getPPU();
                var visible = debugMenuBackground.isSelected();
                runner.post(() -> ppu.setBackgroundLayerVisible(visible));
            }
        });

        debugMenuSprites.addActionListener(e -> {
            if (runner != null) {
                var ppu = nes.getPPU();
                var visible = debugMenuSprites.isSelected();
                runner.post(() -> ppu.setSpriteLayerVisible(visible));
            }
        });

        // The viewer reads the mapper's character memory and the PPU's palette RAM from this
        // thread while the emulation thread runs. Deliberately unsynchronised: reading an array
        // element cannot tear, so the worst case is a debug window showing a tile a frame out of
        // date.
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
                        chrViewerFrame = new CHRViewerFrame(this, cart, nes.getPPU(), config.palette());
                    }

                    chrViewerFrame.setVisible(true);
                }
        );

        helpMenuAbout.addActionListener(e -> JOptionPane.showMessageDialog(
                this,
                """
                MyNES
                A cycle accurate NES emulator written in Java.

                https://github.com/dimiro1/mynes""",
                "About MyNES",
                JOptionPane.INFORMATION_MESSAGE));

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

        startMachine(loaded);

        logger.info("loaded rom {}", selectedFile.getName());
    }

    /**
     * Builds a fresh machine around a cartridge and starts it, replacing whatever was running.
     * Both loading a ROM and cycling the power come through here; the only difference is whether
     * the cartridge is new.
     */
    private void startMachine(final Cart cart) {
        if (runner != null) {
            runner.stop();
        }

        // The old viewer is watching the old machine's mapper and palettes; it would keep showing
        // them forever. Closed rather than repointed, since it is a debug window.
        destroyCHRViewerFrame();

        this.cart = cart;
        nes = new NES(cart);

        // A fresh PPU has both layers on, but the menu remembers what the last one was told.
        // The runner has not started yet, so the machine is still this thread's to touch.
        nes.getPPU().setBackgroundLayerVisible(debugMenuBackground.isSelected());
        nes.getPPU().setSpriteLayerVisible(debugMenuSprites.isSelected());

        // Each machine brings its own controllers, so the keyboard has to be pointed at the new
        // one. Nothing races: the old runner has already stopped and this is the event dispatch
        // thread, which is the only thread the dispatcher runs on.
        keyboardInput.releaseAll();
        keyboardInput.setController(nes.getController1());

        machineMenuPause.setSelected(false);

        runner = new EmulatorRunner(nes, screen);
        runner.start();

        machineMenu.setEnabled(true);
        debugMenu.setEnabled(true);
        updateTitle();
    }

    /**
     * Writes the settings out after a dialog has changed one of them.
     * <p>
     * One writer, because saving truncates the file and writes the whole thing back: a dialog
     * saving its own section would drop everybody else's. The failure is worth a dialog rather
     * than a log line, since the alternative is the setting quietly not being there next time.
     */
    private void saveConfig() {
        try {
            config.save(Config.DEFAULT_PATH);
        } catch (IOException e) {
            logger.error("failed to save the settings", e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not save the settings to " + Config.DEFAULT_PATH,
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void updateTitle() {
        if (cart == null) {
            setTitle("MyNES");
            return;
        }

        var paused = runner != null && runner.isPaused();
        setTitle("MyNES - " + cart.filename() + (paused ? " (paused)" : ""));
    }

    private void destroyCHRViewerFrame() {
        if (chrViewerFrame != null) {
            logger.debug("closing chrViewerFrame");
            chrViewerFrame.dispose();
            chrViewerFrame = null;
        }
    }
}
