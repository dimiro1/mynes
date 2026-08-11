package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.state.BatteryRAM;
import com.github.dimiro1.mynes.state.SaveState;
import com.github.dimiro1.mynes.state.SaveStateException;
import com.github.dimiro1.mynes.ui.chrviewer.CHRViewerFrame;
import com.github.dimiro1.mynes.ui.input.ControllerSettingsDialog;
import com.github.dimiro1.mynes.ui.input.KeyboardInput;
import com.github.dimiro1.mynes.ui.palette.PaletteDialog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.function.IntConsumer;

public class GameUIFrame extends JFrame {
    private static final Logger logger = LoggerFactory.getLogger("UI");

    /**
     * How many save state slots there are. Nine because that is how many fit on the number row, and
     * because a tenth would be the one nobody could remember what they put in.
     */
    private static final int SLOTS = 9;

    /**
     * How often the cartridge RAM is checked and written out while a game is running.
     * <p>
     * There is a save on quit and one on changing cartridges, and neither helps the laptop that runs
     * out of power mid-dungeon. A minute of lost progress is a tolerable worst case, and the check
     * costs an {@link java.util.Arrays#equals} against a shadow copy -- so nothing is written unless
     * the game has actually saved something, and nothing is added to the hot path of every store to
     * $6000, which a dirty flag on the mapper would have been.
     */
    private static final int BATTERY_AUTOSAVE_MILLIS = 60_000;

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
    private final JCheckBoxMenuItem machineMenuFastForward = new JCheckBoxMenuItem("Fast Forward");
    private final JCheckBoxMenuItem machineMenuMute = new JCheckBoxMenuItem("Mute");
    private final JCheckBoxMenuItem debugMenuBackground = new JCheckBoxMenuItem("Show Background", true);
    private final JCheckBoxMenuItem debugMenuSprites = new JCheckBoxMenuItem("Show Sprites", true);

    /**
     * The Load State items, kept so the menu can relabel them with what is in each slot and grey out
     * the ones with nothing in them.
     */
    private final JMenuItem[] loadSlotItems = new JMenuItem[SLOTS];

    private final JMenuItem machineMenuQuickSave = new JMenuItem("Quick Save");
    private final JMenuItem machineMenuQuickLoad = new JMenuItem("Quick Load");

    private CHRViewerFrame chrViewerFrame;
    private Cart cart;
    private NES nes;
    private EmulatorRunner runner;

    /**
     * Where the cartridge came from, which {@link Cart#filename()} does not say: it is handed a bare
     * name. The slot files and the battery file are named from this, so both live beside the ROM.
     */
    private Path romPath;

    /**
     * Which slot the two quick items use. Whichever was last picked from either submenu, so the pair
     * of keys and the menus are one setting rather than two.
     */
    private int currentSlot = 1;

    /**
     * What the cartridge RAM held when it was last written to disk, so the autosave can tell whether
     * the game has saved anything since. Touched only on the emulation thread.
     */
    private byte[] batteryShadow = new byte[0];

    public GameUIFrame() {
        super("MyNES");

        var filter = new FileNameExtensionFilter("iNES", "nes");
        fileChooser = new JFileChooser();
        fileChooser.addChoosableFileFilter(filter);
        fileChooser.setFileFilter(filter);

        config = Config.load(Config.DEFAULT_PATH);
        keyboardInput = new KeyboardInput(this, config.keyBindings());
        screen.setPalette(config.palette());

        // Before init()'s pack(), so the window opens at the size it was left at rather than opening
        // at the default and then jumping.
        screen.setScale(config.screenScale());

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

        machineMenu.add(slotMenu("Save State", this::saveSlot, null));
        machineMenu.add(slotMenu("Load State", this::loadSlot, loadSlotItems));

        // Function keys, for three reasons. They sit in the same physical place on every keyboard
        // layout, which a letter does not -- this one is Colemak-DH. F5 and F7 are what ZSNES and
        // SNES9x used, so they are the keys a player already has in their fingers. And they need no
        // modifier, which matters here: Shift is bound to Select, so KeyboardInput deliberately does
        // not treat it as a shortcut modifier and any Shift+key shortcut would be a hazard.
        machineMenuQuickSave.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        machineMenu.add(machineMenuQuickSave);

        machineMenuQuickLoad.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));
        machineMenu.add(machineMenuQuickLoad);

        machineMenu.addSeparator();

        machineMenuPause.setMnemonic(KeyEvent.VK_P);
        machineMenuPause.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, command));
        machineMenu.add(machineMenuPause);

        machineMenuFastForward.setMnemonic(KeyEvent.VK_F);
        machineMenuFastForward.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, command));
        machineMenu.add(machineMenuFastForward);

        machineMenu.add(fastForwardSpeedMenu());

        machineMenu.addSeparator();

        // No accelerator. Command-M is the window manager's, and picking another letter for it
        // would mean picking one that is somewhere sensible on every keyboard layout, which is
        // not a promise this menu can make.
        machineMenuMute.setMnemonic(KeyEvent.VK_M);
        machineMenuMute.setSelected(config.muted());
        machineMenu.add(machineMenuMute);

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

        settingsMenu.add(screenSizeMenu());

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

        machineMenuQuickSave.addActionListener(e -> saveSlot(currentSlot));
        machineMenuQuickLoad.addActionListener(e -> loadSlot(currentSlot));

        // Which slots have something in them changes as the game is played, so the labels are worked
        // out when the menu opens rather than when it is built.
        machineMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(final MenuEvent e) {
                describeSlots();
            }

            @Override
            public void menuDeselected(final MenuEvent e) {
            }

            @Override
            public void menuCanceled(final MenuEvent e) {
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

        machineMenuFastForward.addActionListener(e -> applySpeed());

        // Remembered between runs, unlike whether fast forward is on: a machine that came back
        // silent is explained by the tick sitting next to this item, and a machine that came back
        // fast forwarding just looks broken.
        machineMenuMute.addActionListener(e -> {
            config.setMuted(machineMenuMute.isSelected());
            saveConfig();

            if (runner != null) {
                runner.setMuted(machineMenuMute.isSelected());
            }
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

                // After the runner has stopped, so the machine is this thread's to read. Last chance:
                // the process is about to go, and a battery game that was not written here is an hour
                // of somebody's evening.
                saveBattery();
            }

            // Cmd-tabbing away in the middle of a jump would otherwise leave the button held down
            // for as long as the window is gone: the release lands in whatever took the focus.
            @Override
            public void windowDeactivated(final WindowEvent e) {
                keyboardInput.releaseAll();
            }
        });

        // A minute is far too long to be worth its own thread, and a Swing timer only fires on the
        // event dispatch thread -- so all it does is post the real work to the machine's own thread,
        // the same way the CHR viewer's refresh does.
        var autosave = new Timer(BATTERY_AUTOSAVE_MILLIS, e -> {
            if (runner != null) {
                runner.post(this::autosaveBattery);
            }
        });

        autosave.setRepeats(true);
        autosave.start();

        pack();
        setLocationRelativeTo(null);
    }

    /**
     * Builds the Fast Forward Speed submenu, one item per speed {@link EmulationSpeed} offers.
     * <p>
     * How fast is a matter of taste and of what the computer manages, so it is a setting rather
     * than a number picked here: 8x is comfortable on a fast machine and unlimited is the whole
     * of whatever is left, while a slower one is better off at 2x, where every frame is still
     * drawn on time. Picking one applies it to a machine that is already fast forwarding, so the
     * speeds can be compared against the running game the way the palettes can.
     */
    private JMenu fastForwardSpeedMenu() {
        var menu = new JMenu("Fast Forward Speed");
        menu.setMnemonic(KeyEvent.VK_S);

        // The group is what makes them one choice rather than four independent ticks.
        var group = new ButtonGroup();

        for (var speed : EmulationSpeed.fastForwardChoices()) {
            var item = new JRadioButtonMenuItem(speed.label(), speed == config.fastForwardSpeed());

            item.addActionListener(e -> {
                config.setFastForwardSpeed(speed);
                saveConfig();
                applySpeed();
            });

            group.add(item);
            menu.add(item);
        }

        return menu;
    }

    /**
     * Builds the Screen Size submenu, one item per size {@link ScreenScale} offers.
     * <p>
     * No accelerators, for the same reason the save state slots have none: Command-1 to Command-4 is
     * spoken for everywhere else, and unlike a slot this is not something anybody reaches for in the
     * middle of a jump.
     */
    private JMenu screenSizeMenu() {
        var menu = new JMenu("Screen Size");
        menu.setMnemonic(KeyEvent.VK_S);

        var group = new ButtonGroup();

        for (var scale : ScreenScale.values()) {
            var item = new JRadioButtonMenuItem(scale.label(), scale == config.screenScale());

            item.addActionListener(e -> {
                config.setScreenScale(scale);
                saveConfig();
                applyScreenScale(scale);
            });

            group.add(item);
            menu.add(item);
        }

        return menu;
    }

    /**
     * Resizes the window around the picture.
     * <p>
     * The screen is the only thing in the frame, so packing it around the component's new preferred
     * size is the whole of it. The window is left where it is rather than centred again: stepping
     * through the sizes to compare them would otherwise walk the window across the display.
     * <p>
     * At 1x the window comes out wider than the picture, because five menus will not fit in 256
     * pixels and the menu bar is what the frame is then packed to. The picture is still drawn at
     * exactly one screen pixel per NES pixel, centred, with black either side of it.
     */
    private void applyScreenScale(final ScreenScale scale) {
        screen.setScale(scale);

        // A maximized window would keep the size the window manager gave it and quietly ignore the
        // one just asked for, leaving a tick in the menu against a size nobody is looking at.
        setExtendedState(Frame.NORMAL);

        pack();
    }

    /**
     * Fills the cartridge's RAM from its {@code .sav} file, if it has a battery and there is one.
     * <p>
     * Only ever called with the emulation thread stopped or not yet started.
     */
    private void loadBattery() {
        if (romPath == null || !BatteryRAM.isWorthSaving(nes)) {
            return;
        }

        var path = BatteryRAM.pathFor(romPath);

        try {
            var read = BatteryRAM.read(nes, path);

            if (read >= 0) {
                logger.info("restored {} bytes of save RAM from {}", read, path.getFileName());
            }
        } catch (IOException e) {
            // Worth a dialog rather than a log line: carrying on means the game says the save file is
            // corrupt, and the player deserves to know it was the emulator that could not read it.
            logger.error("could not read the save file", e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not read " + path.getFileName() + ": " + e.getMessage()
                            + "\n\nThe game will start as though its battery were flat.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Writes the cartridge's RAM out, if a real console would have kept it.
     * <p>
     * Only ever called with the emulation thread stopped, which is what makes reading the machine from
     * the event dispatch thread safe here.
     */
    private void saveBattery() {
        if (nes == null || romPath == null || !BatteryRAM.isWorthSaving(nes)) {
            return;
        }

        var path = BatteryRAM.pathFor(romPath);

        try {
            BatteryRAM.write(nes, path);
            logger.info("wrote save RAM to {}", path.getFileName());
        } catch (IOException e) {
            logger.error("could not write the save file", e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not write " + path.getFileName() + ": " + e.getMessage()
                            + "\n\nThe game's progress since it was last saved may be lost.",
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /**
     * Writes the cartridge RAM out if the game has changed it since the last time.
     * <p>
     * Runs on the emulation thread, which is what makes reading the mapper's array safe. Comparing
     * against a shadow copy rather than trusting a flag means a game that writes its save once an hour
     * costs one file write an hour, and one array comparison a minute the rest of the time.
     */
    private void autosaveBattery() {
        if (romPath == null || !BatteryRAM.isWorthSaving(nes)) {
            return;
        }

        var ram = nes.getBus().getMapper().prgRAM();

        if (Arrays.equals(ram, batteryShadow)) {
            return;
        }

        var path = BatteryRAM.pathFor(romPath);

        try {
            BatteryRAM.write(nes, path);
            batteryShadow = ram.clone();
            logger.info("the game saved, so {} was written", path.getFileName());
        } catch (IOException e) {
            report("Could not write " + path.getFileName(), e);
        }
    }

    /**
     * Builds one of the two slot submenus, nine items numbered from one.
     *
     * @param items where to keep the items for later relabelling, or null if they never change.
     */
    private JMenu slotMenu(final String title, final IntConsumer action, final JMenuItem[] items) {
        var menu = new JMenu(title);

        for (var slot = 1; slot <= SLOTS; slot++) {
            var item = new JMenuItem("Slot " + slot);
            var chosen = slot;

            // No accelerators on these eighteen. Command-1 to Command-9 is "switch tab" everywhere
            // else, and eighteen global shortcuts for something two keys already do would be
            // eighteen chances to collide with a game's controls.
            item.addActionListener(e -> {
                currentSlot = chosen;
                action.accept(chosen);
                describeQuickItems();
            });

            if (items != null) {
                items[slot - 1] = item;
            }

            menu.add(item);
        }

        return menu;
    }

    /**
     * Writes the machine into a slot.
     * <p>
     * The file is written on the emulation thread rather than this one, because that is the only
     * thread allowed to read the machine. It costs a few milliseconds against a 16.6ms frame, once, on
     * a key the player has just pressed -- so one frame may arrive late and the runner's own lag
     * allowance absorbs it. A failure has to find its way back here, since {@code post} returns
     * nothing to wait on.
     */
    private void saveSlot(final int slot) {
        if (runner == null || romPath == null) {
            return;
        }

        var path = slotPath(slot);

        runner.post(() -> {
            try {
                SaveState.write(nes, path);
                logger.info("saved slot {} at frame {}", slot, nes.getPPU().getFrame());
            } catch (IOException | SaveStateException ex) {
                report("Could not save slot " + slot, ex);
            }
        });
    }

    private void loadSlot(final int slot) {
        if (runner == null || romPath == null) {
            return;
        }

        var path = slotPath(slot);

        if (!Files.exists(path)) {
            // Worth saying out loud rather than doing nothing: the keys are one press apart, and a
            // Quick Load that silently did nothing would look like a broken emulator.
            JOptionPane.showMessageDialog(
                    this,
                    "There is nothing saved in slot " + slot + " for this cartridge.",
                    "Load State",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }

        runner.postStateChange(() -> {
            try {
                SaveState.read(nes, path);
                logger.info("loaded slot {}, now at frame {}", slot, nes.getPPU().getFrame());
            } catch (IOException | SaveStateException ex) {
                report("Could not load slot " + slot, ex);
            }
        });

        // The buttons the player was holding belong to the game they were playing a moment ago.
        keyboardInput.releaseAll();
    }

    /**
     * Puts what is in each slot onto its menu item, and greys out the empty ones.
     * <p>
     * This is what makes nine numbered slots usable instead of a guessing game, and it is why the
     * state's header is not compressed: nine files get their frame number read without any of them
     * being inflated.
     */
    private void describeSlots() {
        describeQuickItems();

        for (var slot = 1; slot <= SLOTS; slot++) {
            var item = loadSlotItems[slot - 1];
            var path = romPath == null ? null : slotPath(slot);

            if (path == null || !Files.exists(path)) {
                item.setText("Slot " + slot);
                item.setEnabled(false);
                continue;
            }

            item.setEnabled(true);

            try {
                var header = SaveState.header(path);
                var when = Files.getLastModifiedTime(path).toInstant()
                        .atZone(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("d MMM HH:mm"));

                item.setText("Slot " + slot + " — frame " + header.frame() + ", " + when);
            } catch (IOException | SaveStateException ex) {
                // A file that will not even give up its header is still offered, because refusing to
                // list it would hide the only clue that something is wrong with it.
                item.setText("Slot " + slot + " — unreadable");
            }
        }
    }

    private void describeQuickItems() {
        machineMenuQuickSave.setText("Quick Save (Slot " + currentSlot + ")");
        machineMenuQuickLoad.setText("Quick Load (Slot " + currentSlot + ")");
    }

    /**
     * Where a slot lives: beside the ROM, numbered, with {@code .mn} for "MyNES".
     */
    private Path slotPath(final int slot) {
        return SaveState.slotPath(romPath, slot);
    }

    /**
     * Tells the player about a failure that happened on the emulation thread.
     * <p>
     * Hopped back onto the event dispatch thread, because that is the only one allowed to put a dialog
     * on the screen and {@link EmulatorRunner#post} has no way of handing an exception back.
     */
    private void report(final String what, final Exception cause) {
        logger.error("{}", what, cause);

        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                this, what + ": " + cause.getMessage(), "Error", JOptionPane.ERROR_MESSAGE));
    }

    /**
     * Tells the running machine how fast to go, from the two things that decide it: whether fast
     * forward is switched on, and which speed it is set to.
     */
    private void applySpeed() {
        if (runner == null) {
            return;
        }

        runner.setSpeed(machineMenuFastForward.isSelected()
                ? config.fastForwardSpeed()
                : EmulationSpeed.NORMAL);

        updateTitle();
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

        // Where the file came from, which the Cart is only told the name of. The slots and the
        // battery file are named from this, so a game keeps its saves beside it.
        romPath = selectedFile.toPath().toAbsolutePath();

        startMachine(loaded);

        logger.info("loaded rom {}", selectedFile.getName());
    }

    /**
     * Builds a fresh machine around a cartridge and starts it, replacing whatever was running.
     * Both loading a ROM and cycling the power come through here; the only difference is whether
     * the cartridge is new.
     */
    private void startMachine(final Cart cart) {
        // Before the outgoing machine is let go of, and while its runner is stopped so it is safe to
        // read from here. Both changing cartridges and cycling the power come through here, and a
        // power cycle that lost the last hour of a game would be a cruel way to find that out.
        saveBattery();

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

        // The cartridge finds its battery already charged, which is what a real one did. In the same
        // window as the two lines above: the runner does not exist yet, so this thread owns the
        // machine.
        loadBattery();

        // What the autosave compares against from here on: whatever the battery arrived holding, so
        // the first check after a load does not rewrite an unchanged file.
        batteryShadow = nes.getBus().getMapper().prgRAM().clone();

        // Each machine brings its own controllers, so the keyboard has to be pointed at the new
        // one. Nothing races: the old runner has already stopped and this is the event dispatch
        // thread, which is the only thread the dispatcher runs on.
        keyboardInput.releaseAll();
        keyboardInput.setController(nes.getController1());

        // A machine that has just been switched on is running, and running at normal speed. Both
        // menu items have to agree with that; the runner is already built that way.
        machineMenuPause.setSelected(false);
        machineMenuFastForward.setSelected(false);

        runner = new EmulatorRunner(nes, screen);

        // Posted before the thread exists, so it is the first thing that runs on it: a machine
        // started with the sound off must not get a frame of it in first.
        runner.setMuted(machineMenuMute.isSelected());
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

        setTitle("MyNES - " + cart.filename() + machineState());
    }

    /**
     * What the title says the machine is doing, when it is doing anything other than simply
     * running. Pause wins over fast forward: a machine that is not running is not running fast.
     */
    private String machineState() {
        if (runner == null) {
            return "";
        }

        if (runner.isPaused()) {
            return " (paused)";
        }

        if (runner.getSpeed() != EmulationSpeed.NORMAL) {
            return " (fast forward)";
        }

        return "";
    }

    private void destroyCHRViewerFrame() {
        if (chrViewerFrame != null) {
            logger.debug("closing chrViewerFrame");
            chrViewerFrame.dispose();
            chrViewerFrame = null;
        }
    }
}
