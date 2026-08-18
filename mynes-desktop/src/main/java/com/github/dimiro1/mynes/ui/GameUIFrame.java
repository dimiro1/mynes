package com.github.dimiro1.mynes.ui;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.patch.IPSPatch;
import com.github.dimiro1.mynes.state.BatteryRAM;
import com.github.dimiro1.mynes.state.SaveState;
import com.github.dimiro1.mynes.state.SaveStateException;
import com.github.dimiro1.mynes.ui.chrviewer.CHRViewerFrame;
import com.github.dimiro1.mynes.ui.debugger.DebuggerFrame;
import com.github.dimiro1.mynes.ui.input.ControllerSettingsDialog;
import com.github.dimiro1.mynes.ui.input.KeyboardInput;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.function.IntConsumer;

public class GameUIFrame extends JFrame {
    private static final Logger logger = System.getLogger("UI");

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

    /**
     * The Open dialog, kept between openings so that the next one starts in the folder the last one
     * ended in.
     * <p>
     * The system's own dialog rather than Swing's, which is the one part of this a player already
     * knows how to use. FlatLaf falls back to a {@link JFileChooser} where there is no system dialog
     * to ask for, so it is not a decision about platforms.
     * <p>
     * It also blocks the application's input events while it is up, which matters more here than in
     * most programs: {@link KeyboardInput} sits on the keyboard focus manager and sees every
     * keystroke in the process, so the alternative is a dialog that plays the game while somebody
     * types a filename into it.
     */
    private final SystemFileChooser fileChooser;

    /**
     * The Open with Patch dialog's second half, kept apart from {@link #fileChooser} so that each
     * remembers its own folder: a patch and the cartridge it is for are hardly ever in the same
     * place, since one was downloaded this afternoon and the other has been on the disk for years.
     */
    private final SystemFileChooser patchChooser;

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

    /**
     * Screenshot, kept because it is the one item in an always-enabled menu that needs a machine.
     * There is nothing to photograph until one is running, and a File menu greyed out as a whole
     * would take Open with it.
     */
    private final JMenuItem fileMenuScreenshot = new JMenuItem("Screenshot", KeyEvent.VK_S);

    /**
     * The breakpoints, which belong to the window rather than to any one machine.
     * <p>
     * A power cycle and a region change both build a new NES, and a power cycle that forgot every
     * breakpoint would be infuriating -- you cycle the power precisely in order to hit them again.
     * So this outlives the machines, the way the Debug menu's layer ticks do, and is re-attached to
     * each one in {@link #startMachine}.
     */
    private final Debugger debugger = new Debugger();

    private CHRViewerFrame chrViewerFrame;
    private DebuggerFrame debuggerFrame;
    private Cart cart;
    private NES nes;
    private EmulatorRunner runner;

    /**
     * Where the cartridge came from, which {@link Cart#filename()} does not say: it is handed a bare
     * name. The slot files and the battery file are named from this, so both live beside the ROM.
     */
    private Path romPath;

    /**
     * The patch applied to it on the way in, or null. Kept because it is half of what the machine
     * running is: {@link #gamePath} names this game's files after it, and the title says so.
     */
    private Path patchPath;

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

        var filter = new SystemFileChooser.FileNameExtensionFilter("iNES", "nes");
        fileChooser = new SystemFileChooser();
        fileChooser.addChoosableFileFilter(filter);
        fileChooser.setFileFilter(filter);

        var patchFilter = new SystemFileChooser.FileNameExtensionFilter("IPS patch", "ips");
        patchChooser = new SystemFileChooser();
        patchChooser.addChoosableFileFilter(patchFilter);
        patchChooser.setFileFilter(patchFilter);

        config = Config.load(Config.DEFAULT_PATH);
        keyboardInput = new KeyboardInput(this, config.keyBindings());
        screen.setPalette(config.palette(currentRegion()));

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

        JMenuItem fileMenuOpenPatched = new JMenuItem("Open with Patch...", KeyEvent.VK_P);
        fileMenuOpenPatched.setAccelerator(
                KeyStroke.getKeyStroke(KeyEvent.VK_O, command | InputEvent.SHIFT_DOWN_MASK));
        fileMenu.add(fileMenuOpenPatched);

        // A function key, for the reasons the two quick state items are on function keys: it is in
        // the same physical place on every keyboard layout, it is the key every emulator since ZSNES
        // has taken a picture with, and it needs no modifier -- Shift being Select, a shortcut
        // carrying one is a hazard here.
        fileMenuScreenshot.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0));
        fileMenuScreenshot.setEnabled(false);
        fileMenu.add(fileMenuScreenshot);

        fileMenu.addSeparator();

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

        machineMenu.add(regionMenu());

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

        JMenuItem debugMenuDebugger = new JMenuItem("Debugger", KeyEvent.VK_D);
        debugMenu.add(debugMenuDebugger);

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
        settingsMenu.add(screenshotSizeMenu());

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
        //
        // It is remembered against the kind of machine that is running, though, because the two
        // PPUs do not generate the same colours. Somebody comparing palettes over a European game
        // is choosing what their European games look like.
        settingsMenuPalette.addActionListener(e ->
                new PaletteDialog(this, config.palette(currentRegion()), chosen -> {
                    config.setPalette(currentRegion(), chosen);
                    screen.setPalette(chosen);

                    if (chrViewerFrame != null) {
                        chrViewerFrame.setPalette(chosen);
                    }

                    saveConfig();
                }).setVisible(true));

        fileMenuScreenshot.addActionListener(e -> takeScreenshot());

        fileMenuOpen.addActionListener(e -> {
            if (fileChooser.showOpenDialog(this) == SystemFileChooser.APPROVE_OPTION) {
                open(fileChooser.getSelectedFile(), null);
            }
        });

        // Two dialogs, the cartridge first and the patch second, which is the order the two are
        // thought about in: a romhack is a patch for a particular game rather than a thing you
        // choose first and find a game for.
        fileMenuOpenPatched.addActionListener(e -> {
            if (fileChooser.showOpenDialog(this) != SystemFileChooser.APPROVE_OPTION) {
                return;
            }

            var rom = fileChooser.getSelectedFile();

            if (patchChooser.showOpenDialog(this) == SystemFileChooser.APPROVE_OPTION) {
                open(rom, patchChooser.getSelectedFile());
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

            // Unticking is a resume rather than a plain unpause, because it also has to forget
            // whatever the debugger was still waiting for. Without that, unticking Pause after a
            // breakpoint would stop again one instruction later and look broken.
            if (machineMenuPause.isSelected()) {
                runner.setPaused(true);

                // A button held down when the game froze would otherwise still be held on resume,
                // minutes later, whether or not the key is.
                keyboardInput.releaseAll();
            } else {
                runner.resume();

                if (debuggerFrame != null) {
                    debuggerFrame.running();
                }
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
                        logger.log(Level.ERROR, "cartridge is not loaded");
                        JOptionPane.showMessageDialog(
                                this,
                                "Cartridge is not loaded",
                                "Error",
                                JOptionPane.ERROR_MESSAGE
                        );
                        return;
                    }

                    if (chrViewerFrame == null) {
                        chrViewerFrame = new CHRViewerFrame(
                                this, cart, nes.getPPU(), config.palette(currentRegion()));
                    }

                    chrViewerFrame.setVisible(true);
                }
        );

        debugMenuDebugger.addActionListener(e -> {
            if (cart == null) {
                logger.log(Level.ERROR, "cartridge is not loaded");
                JOptionPane.showMessageDialog(
                        this,
                        "Cartridge is not loaded",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                );
                return;
            }

            if (debuggerFrame == null) {
                debuggerFrame = new DebuggerFrame(this, nes, runner, debugger);
            }

            debuggerFrame.setVisible(true);
        });

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
     * Builds the Region submenu: believe the cartridge, or insist on one machine or the other.
     * <p>
     * Sitting under Power Cycle because that is what picking one does. The chips are built around
     * their region and a running machine cannot be rewired, so changing this starts the game again
     * from power on -- through the same path as loading a ROM, which saves the battery first, so
     * what it costs is the current life rather than the save file.
     * <p>
     * There is a menu at all because the header field for this is left at zero in nearly every
     * dump, so Automatic answers NTSC for most European cartridges. A game running 17% fast with
     * the music too high is the symptom, and this is the cure.
     */
    private JMenu regionMenu() {
        var menu = new JMenu("Region");
        menu.setMnemonic(KeyEvent.VK_G);

        var group = new ButtonGroup();

        for (var setting : RegionSetting.values()) {
            var item = new JRadioButtonMenuItem(setting.label(), setting == config.region());

            item.addActionListener(e -> {
                if (setting == config.region()) {
                    return;
                }

                config.setRegion(setting);
                saveConfig();

                // Nothing to restart when nothing is running, and the setting is still remembered
                // for the next cartridge.
                if (cart != null) {
                    startMachine(cart);
                }
            });

            group.add(item);
            menu.add(item);
        }

        return menu;
    }

    /**
     * Which machine is running, or which one a cartridge would be run on if one were loaded.
     * <p>
     * The palette is asked this rather than the config directly, because Automatic has no answer
     * until there is a cartridge to ask -- and with no cartridge at all, NTSC is the right thing to
     * paint the empty screen with.
     */
    private Region currentRegion() {
        if (nes != null) {
            return nes.getRegion();
        }

        return cart == null ? Region.NTSC : config.region().resolve(cart);
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
     * Builds the Screenshot Size submenu: how many times File &gt; Screenshot magnifies the picture
     * on its way into the file.
     * <p>
     * The same four multiples the window offers, asked separately because they are asked for
     * different reasons -- the window is as big as the display allows, and a file is as big as
     * whatever is going to look at it. Picking one changes nothing on screen, so unlike the window's
     * sizes there is nothing to apply: the next picture is simply written at it.
     */
    private JMenu screenshotSizeMenu() {
        // H rather than the S in "Screenshot", which Screen Size above it already has.
        var menu = new JMenu("Screenshot Size");
        menu.setMnemonic(KeyEvent.VK_H);

        var group = new ButtonGroup();

        for (var scale : ScreenScale.values()) {
            var item = new JRadioButtonMenuItem(scale.label(), scale == config.screenshotScale());

            item.addActionListener(e -> {
                config.setScreenshotScale(scale);
                saveConfig();
            });

            group.add(item);
            menu.add(item);
        }

        return menu;
    }

    /**
     * Writes the picture on screen to a PNG beside the ROM.
     * <p>
     * The emulation thread is not asked for anything, which is what separates this from a save
     * state: {@link ScreenComponent} is already holding the last frame it was handed, in colour
     * indices, along with the palette it draws them with -- so the whole picture is on this thread
     * already. Which is also why it works on a machine that is paused or stopped at a breakpoint,
     * when there is no running thread to post to and the picture is exactly what somebody stopped
     * the machine to look at.
     * <p>
     * The file is written from the event dispatch thread. Encoding 1024x896 pixels is a few
     * milliseconds, on a key the player has just pressed, and the game is on another thread and
     * carries on regardless.
     */
    private void takeScreenshot() {
        var image = screen.snapshot(config.screenshotScale());

        if (image == null || romPath == null) {
            // The item is greyed out until a machine starts, which leaves the moment between the
            // machine starting and its first finished frame: about a sixtieth of a second of
            // nothing to photograph.
            return;
        }

        var path = Screenshots.pathFor(gamePath(), LocalDateTime.now());

        try {
            ImageIO.write(image, "png", path.toFile());
            // Not "at frame N": the machine is the emulation thread's, and a log line is nowhere
            // near reason enough to read it from this one.
            logger.log(Level.INFO, "wrote " + path.getFileName());
        } catch (IOException e) {
            logger.log(Level.ERROR, "could not write the screenshot", e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not write " + path.getFileName() + ": " + e.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);
        }
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

        var path = BatteryRAM.pathFor(gamePath());

        try {
            var read = BatteryRAM.read(nes, path);

            if (read >= 0) {
                logger.log(Level.INFO, "restored " + read + " bytes of save RAM from " + path.getFileName());
            }
        } catch (IOException e) {
            // Worth a dialog rather than a log line: carrying on means the game says the save file is
            // corrupt, and the player deserves to know it was the emulator that could not read it.
            logger.log(Level.ERROR, "could not read the save file", e);
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

        var path = BatteryRAM.pathFor(gamePath());

        try {
            BatteryRAM.write(nes, path);
            logger.log(Level.INFO, "wrote save RAM to " + path.getFileName());
        } catch (IOException e) {
            logger.log(Level.ERROR, "could not write the save file", e);
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

        var path = BatteryRAM.pathFor(gamePath());

        try {
            BatteryRAM.write(nes, path);
            batteryShadow = ram.clone();
            logger.log(Level.INFO, "the game saved, so " + path.getFileName() + " was written");
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
                logger.log(Level.INFO, "saved slot " + slot + " at frame " + nes.getPPU().getFrame());
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
                logger.log(Level.INFO, "loaded slot " + slot + ", now at frame " + nes.getPPU().getFrame());
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
        return SaveState.slotPath(gamePath(), slot);
    }

    /**
     * Tells the player about a failure that happened on the emulation thread.
     * <p>
     * Hopped back onto the event dispatch thread, because that is the only one allowed to put a dialog
     * on the screen and {@link EmulatorRunner#post} has no way of handing an exception back.
     */
    private void report(final String what, final Exception cause) {
        logger.log(Level.ERROR, what, cause);

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
     * The menu's way in: load a cartridge, and tell the player if it will not load.
     * <p>
     * A dialog rather than a log line, because the player has just picked the file out of a chooser
     * and is owed an answer about it. Everything {@link Cart#load} and {@link IPSPatch#read} throw is
     * unchecked, so the two are caught together and the machine already running is left alone.
     */
    private void open(final File rom, final File patch) {
        try {
            loadRom(rom, patch);
        } catch (IOException | RuntimeException e) {
            report("Could not load " + rom.getName(), e);
        }
    }

    /**
     * Loads a ROM and starts running it, replacing whatever was running before.
     * <p>
     * The cartridge is parsed before the running machine is touched, so a file that turns out not
     * to be a ROM leaves the current game playing.
     *
     * @param patchFile an IPS patch to apply to the ROM first, or null. It is applied to the bytes
     *                  on their way in and nowhere else: the file the player owns is never written
     *                  to, and closing the emulator leaves no patched copy of it behind.
     */
    private void loadRom(final File selectedFile, final File patchFile) throws IOException {
        logger.log(Level.INFO, "loading rom " + selectedFile.getName());

        byte[] image;
        try (var rom = new FileInputStream(selectedFile)) {
            image = rom.readAllBytes();
        }

        if (patchFile != null) {
            // Before Cart.load rather than after, since a patch is entitled to rewrite the header
            // and so to change the mapper, the size of the banks, or anything else it decides.
            var patch = IPSPatch.read(Files.readAllBytes(patchFile.toPath()), patchFile.getName());

            image = patch.applyTo(image);

            logger.log(Level.INFO, "applied " + patch.records() + " records from "
                    + patchFile.getName());
        }

        var loaded = Cart.load(image, selectedFile.getName());

        startMachine(
                loaded,
                selectedFile.toPath().toAbsolutePath(),
                patchFile == null ? null : patchFile.toPath().toAbsolutePath());

        logger.log(Level.INFO, "loaded rom " + selectedFile.getName());
    }

    /**
     * What this game's own files are named after: the patch when there is one, the ROM otherwise.
     * <p>
     * A hack is a different game from the cartridge it was cut against, and it has to be for the one
     * reason that matters: fifty hours of the original's battery save must not be overwritten by an
     * afternoon with a romhack that happens to have been loaded from the same {@code .nes}. Naming
     * them apart is what stops that, and a save state carries the cartridge's digest anyway, so one
     * that did wander across is refused rather than loaded.
     */
    private Path gamePath() {
        return patchPath == null ? romPath : patchPath;
    }

    /**
     * Builds a fresh machine around a cartridge and starts it, replacing whatever was running.
     * Both loading a ROM and cycling the power come through here; the only difference is whether
     * the cartridge is new.
     */
    private void startMachine(final Cart cart) {
        startMachine(cart, romPath, patchPath);
    }

    /**
     * The same, for a cartridge that has just been loaded from somewhere.
     *
     * @param rom   where the .nes file was, which is what the Cart is not told.
     * @param patch the IPS patch applied to it, or null.
     */
    private void startMachine(final Cart cart, final Path rom, final Path patch) {
        // Before the outgoing machine is let go of, and while its runner is stopped so it is safe to
        // read from here. Both changing cartridges and cycling the power come through here, and a
        // power cycle that lost the last hour of a game would be a cruel way to find that out.
        //
        // Above the two lines below for the same reason it is above the rest of this method: the
        // fields still name the outgoing game, and saving after they had moved would write the
        // outgoing game's RAM into the incoming game's .sav -- which the incoming game would then
        // read back as its own progress.
        saveBattery();

        // The slots, the battery file and the screenshots are named from these two, so a game keeps
        // its saves beside it -- and a hack keeps its own beside the patch, rather than writing over
        // the original's.
        romPath = rom;
        patchPath = patch;

        if (runner != null) {
            runner.stop();
        }

        // The old viewer is watching the old machine's mapper and palettes; it would keep showing
        // them forever. Closed rather than repointed, since it is a debug window.
        destroyCHRViewerFrame();

        // A new cartridge deserves a clean slate, but a power cycle does not: the breakpoints are
        // the reason somebody cycles the power. Asked before the field is reassigned, since that is
        // the whole of the difference between the two.
        var sameCartridge = cart == this.cart;

        if (!sameCartridge) {
            debugger.clear();
            destroyDebuggerFrame();
        }

        this.cart = cart;
        nes = new NES(cart, config.region().resolve(cart));

        logger.log(Level.INFO, "running " + cart.filename() + " as " + nes.getRegion().label());

        // Which television this machine is plugged into. Only here, rather than everywhere a
        // palette is chosen, because this is the one moment the kind of machine can change.
        screen.setPalette(config.palette(nes.getRegion()));

        // A fresh PPU has both layers on, but the menu remembers what the last one was told.
        // The runner has not started yet, so the machine is still this thread's to touch.
        nes.getPPU().setBackgroundLayerVisible(debugMenuBackground.isSelected());
        nes.getPPU().setSpriteLayerVisible(debugMenuSprites.isSelected());

        // The watchpoints have to be wired to this machine's MMU rather than the last one's. Same
        // window as the two lines above: the runner does not exist yet, so this thread owns it.
        debugger.attach(nes);

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

        runner = new EmulatorRunner(nes, screen, debugger);
        runner.setStopListener(this::stopped);

        if (debuggerFrame != null) {
            debuggerFrame.setMachine(nes, runner);
        }

        // Posted before the thread exists, so it is the first thing that runs on it: a machine
        // started with the sound off must not get a frame of it in first.
        runner.setMuted(machineMenuMute.isSelected());
        runner.start();

        machineMenu.setEnabled(true);
        debugMenu.setEnabled(true);
        fileMenuScreenshot.setEnabled(true);
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
            logger.log(Level.ERROR, "failed to save the settings", e);
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

        setTitle("MyNES - " + cart.filename() + patched() + machineKind() + machineState());
    }

    /**
     * Which hack is playing, when one is.
     * <p>
     * The cartridge's own name is still first: a patched game is that game plus a patch, and a title
     * bar naming only the patch would leave nothing to say which ROM it was applied to.
     */
    private String patched() {
        return patchPath == null ? "" : " + " + patchPath.getFileName();
    }

    /**
     * The kind of machine, when it is not the usual one.
     * <p>
     * Only PAL is worth the words. A machine's region is invisible from the picture until something
     * is wrong, and when it is wrong -- a game running fast because the header said nothing -- this
     * is the line that says which way to reach for.
     */
    private String machineKind() {
        return nes != null && nes.getRegion() == Region.PAL ? " (PAL)" : "";
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
            logger.log(Level.DEBUG, "closing chrViewerFrame");
            chrViewerFrame.dispose();
            chrViewerFrame = null;
        }
    }

    private void destroyDebuggerFrame() {
        if (debuggerFrame != null) {
            logger.log(Level.DEBUG, "closing debuggerFrame");
            debuggerFrame.dispose();
            debuggerFrame = null;
        }
    }

    /**
     * The machine has stopped somewhere it was asked to. Called on the event dispatch thread with
     * the machine already halted, which is the one moment reading it is safe.
     * <p>
     * The Pause tick is brought into line here because a breakpoint pauses the machine just as
     * surely as the menu item does, and a frozen emulator with an unticked Pause box would be a
     * puzzle rather than a debugger.
     */
    private void stopped(final Debugger.Stop stop) {
        machineMenuPause.setSelected(true);
        keyboardInput.releaseAll();
        updateTitle();

        if (debuggerFrame != null) {
            debuggerFrame.stopped(stop);
        }
    }
}
