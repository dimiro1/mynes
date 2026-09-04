package com.github.dimiro1.mynes.ui;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.archive.Archive;
import com.github.dimiro1.mynes.archive.InvalidArchiveException;
import com.github.dimiro1.mynes.cheat.GameGenie;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.debug.Tracer;
import com.github.dimiro1.mynes.patch.IPSPatch;
import com.github.dimiro1.mynes.state.BatteryRAM;
import com.github.dimiro1.mynes.state.Movie;
import com.github.dimiro1.mynes.state.MovieException;
import com.github.dimiro1.mynes.state.Rewind;
import com.github.dimiro1.mynes.state.SaveState;
import com.github.dimiro1.mynes.state.SaveStateException;
import com.github.dimiro1.mynes.ui.chrviewer.CHRViewerFrame;
import com.github.dimiro1.mynes.ui.debugger.DebuggerFrame;
import com.github.dimiro1.mynes.ui.input.ControllerSettingsDialog;
import com.github.dimiro1.mynes.ui.ppuviewer.NametableViewerFrame;
import com.github.dimiro1.mynes.ui.ppuviewer.OAMViewerFrame;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.FrameRenderer;
import com.github.dimiro1.mynes.video.VideoFilter;
import com.github.dimiro1.mynes.ui.input.KeyboardInput;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.MenuEvent;
import javax.swing.event.MenuListener;
import java.awt.*;
import java.awt.event.ActionEvent;
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
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
// Explicitly, because java.awt.* is on demand above and brings a List of its own with it.
import java.util.List;
import java.util.function.IntConsumer;
import java.util.stream.Collectors;

public class GameUIFrame extends JFrame {
    private static final Logger logger = System.getLogger("UI");

    /**
     * How many save state slots there are. Nine because that is how many fit on the number row, and
     * because a tenth would be the one nobody could remember what they put in.
     */
    private static final int SLOTS = 9;

    /**
     * What a cartridge inside a zip is called. The only one: this runs iNES and NES 2.0 images, and
     * a zip full of names nobody recognises is better refused with a list of what was in it than
     * opened on whichever file happened to be first.
     */
    private static final String ROM_EXTENSION = "nes";

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
     * How often the status bar is worked out again, and so the window the frame rate is measured
     * over.
     * <p>
     * A second, because the counter it divides is a whole number: half a second catches either 30
     * frames or 31, which is a reading two frames wide before the machine has done anything, and a
     * quarter is four. A second brings that to one frame, which is what
     * {@link FrameRate#sample}'s deadband then absorbs. Nothing is lost by waiting, since
     * everything on the bar apart from the rate is put there by whatever changed it -- this tick
     * is the measurement, not the refresh.
     */
    private static final int STATUS_INTERVAL_MILLIS = 1_000;

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

    /**
     * The Record and Play dialogs, kept apart from the two above so that movies remember their own
     * folder: a session somebody recorded is not usually filed beside the cartridge it was recorded
     * from.
     */
    private final SystemFileChooser movieChooser;

    /**
     * Where a trace goes. Its own chooser, like the two above, so that it remembers the directory
     * traces are being kept in rather than wherever a ROM was last opened from.
     */
    private final SystemFileChooser traceChooser;

    private final ScreenComponent screen = new ScreenComponent();
    private final StatusBar statusBar = new StatusBar();
    private final KeyboardInput keyboardInput;

    /**
     * Where the number on the bar comes from. Belongs to the window rather than to a machine, so
     * that it survives the moment one is swapped for another -- {@link FrameRate#reset} is how it
     * is told that happened.
     */
    private final FrameRate frameRate = new FrameRate();

    /**
     * Everything remembered between runs, and the only thing that writes the config file.
     */
    private final Config config;

    // Menus and items that outlive init(): they are enabled, ticked and read back as machines
    // come and go.
    private final JMenu machineMenu = new JMenu("Machine");
    private final JMenu debugMenu = new JMenu("Debug");
    private final JCheckBoxMenuItem machineMenuPause = new JCheckBoxMenuItem("Pause");
    private final JCheckBoxMenuItem machineMenuPauseInBackground =
            new JCheckBoxMenuItem("Pause in Background");
    private final JCheckBoxMenuItem machineMenuFastForward = new JCheckBoxMenuItem("Fast Forward");
    private final JCheckBoxMenuItem machineMenuMute = new JCheckBoxMenuItem("Mute");
    private final JCheckBoxMenuItem debugMenuBackground = new JCheckBoxMenuItem("Show Background", true);
    private final JCheckBoxMenuItem debugMenuSprites = new JCheckBoxMenuItem("Show Sprites", true);
    private final JCheckBoxMenuItem hacksMenuUnlimitedSprites =
            new JCheckBoxMenuItem("Unlimited Sprites");

    /**
     * The five sound channels, ticked when they can be heard -- the same way round as the two layer
     * switches above, and the same kind of thing: what a machine somebody is watching is allowed to
     * show them.
     * <p>
     * Kept for the reason those are: they outlive the machines, and {@link #startMachine} replays
     * every one of them onto whichever one is built next.
     */
    private final Map<APUChannel, JCheckBoxMenuItem> debugMenuChannels =
            new EnumMap<>(APUChannel.class);

    /**
     * The Volume items, kept so that Louder and Quieter can move the dot. Setting it rather than
     * clicking it, which fires no listener, is what keeps the three ways of picking a volume down to
     * one path.
     */
    private final Map<Volume, JRadioButtonMenuItem> machineMenuVolumes = new EnumMap<>(Volume.class);

    /**
     * The one item in the Hacks menu that needs a cartridge. The menu as a whole is deliberately not
     * gated, because everything else in it is a remembered preference with something to change before
     * a ROM is open -- and a code is written for one particular game, so there is nothing to type
     * until there is a game to type it for.
     */
    private final JMenuItem debugMenuTrace = new JMenuItem("Start Trace...");
    private final JMenuItem debugMenuStopTrace = new JMenuItem("Stop Trace");
    private final JMenuItem hacksMenuGameGenie = new JMenuItem("Game Genie...");
    private final JMenuItem settingsMenuPalette = new JMenuItem("Palette...", KeyEvent.VK_P);
    private final JCheckBoxMenuItem settingsMenuWarp = new JCheckBoxMenuItem("Curved Glass");
    private final JCheckBoxMenuItem settingsMenuOverscan = new JCheckBoxMenuItem("Show Overscan");
    private final JCheckBoxMenuItem settingsMenuLeftEdge = new JCheckBoxMenuItem("Show Left Edge");
    private final JCheckBoxMenuItem settingsMenuTvAspect =
            new JCheckBoxMenuItem("TV Aspect Ratio");
    private final JCheckBoxMenuItem settingsMenuFullScreen = new JCheckBoxMenuItem("Full Screen");
    private final JCheckBoxMenuItem settingsMenuStatusBar = new JCheckBoxMenuItem("Status Bar");

    /**
     * The Video Filter items, kept one at a time rather than as the submenu they are in, because
     * only one of them is ever greyed out: the decoder is the 2C02's and a PAL machine has to do
     * without it, while the palette and the tube are both every machine's.
     */
    private final Map<VideoFilter, JRadioButtonMenuItem> settingsMenuVideoFilters =
            new EnumMap<>(VideoFilter.class);

    /**
     * The Load State items, kept so the menu can relabel them with what is in each slot and grey out
     * the ones with nothing in them.
     */
    private final JMenuItem[] loadSlotItems = new JMenuItem[SLOTS];

    private final JMenuItem machineMenuQuickSave = new JMenuItem("Quick Save");
    private final JMenuItem machineMenuQuickLoad = new JMenuItem("Quick Load");

    /**
     * The four movie items, and the two things a movie will not survive.
     * <p>
     * Power Cycle and Region both build a new machine, and a take in progress lives in the runner
     * that would be torn down -- so both are greyed out while one is running rather than allowed to
     * lose it silently. There is no accelerator on any of these: the function keys are spoken for,
     * and Shift is Select, so a Shift shortcut is a hazard here.
     */
    private final JMenuItem machineMenuRecord = new JMenuItem("Record Movie...");
    private final JMenuItem machineMenuStopRecording = new JMenuItem("Stop Recording");
    private final JMenuItem machineMenuPlay = new JMenuItem("Play Movie...");
    private final JMenuItem machineMenuStopPlayback = new JMenuItem("Stop Playback");
    private final JMenuItem machineMenuPowerCycle = new JMenuItem("Power Cycle", KeyEvent.VK_C);

    /**
     * Built in {@link #init()} rather than here, because {@link #regionMenu()} reads the config and
     * a field initialiser runs before the constructor has loaded it.
     */
    private JMenu machineMenuRegion;

    /**
     * The Overclock submenu, built there for the same reason and kept for a second one: it is greyed
     * out while a movie is running, exactly as the Game Genie item is. A movie pins the overclock
     * when it starts, and this is the one hack that decides how much of its work the game gets
     * through in a frame.
     */
    private JMenu hacksMenuOverclock;
    private JMenu settingsMenuFilterStrength;

    /**
     * The Screen Size submenu, kept because full screen greys it out: the four sizes pack the
     * window around a whole multiple of the picture, and a window filling the display is at none
     * of them. A tick against a size nobody is looking at is the thing {@link #applyScreenScale}
     * already steps around for a maximized window, and this is the same step taken earlier.
     */
    private JMenu settingsMenuScreenSize;

    /**
     * The games somebody has opened before. Built afresh each time the File menu is pulled down
     * rather than kept in step with the list, because half of what it shows is not the list: a
     * cartridge can be moved, renamed or unplugged without the emulator being anywhere near it.
     */
    private final JMenu fileMenuOpenRecent = new JMenu("Open Recent");

    /**
     * The two pictures, kept because they are the items in an always-enabled menu that need a
     * machine. There is nothing to photograph until one is running, and a File menu greyed out as a
     * whole would take Open with it.
     */
    private final JMenuItem fileMenuScreenshot = new JMenuItem("Screenshot", KeyEvent.VK_S);
    private final JMenuItem fileMenuCopyScreenshot =
            new JMenuItem("Copy Screenshot", KeyEvent.VK_C);

    /**
     * The breakpoints, which belong to the window rather than to any one machine.
     * <p>
     * A power cycle and a region change both build a new NES, and a power cycle that forgot every
     * breakpoint would be infuriating -- you cycle the power precisely in order to hit them again.
     * So this outlives the machines, the way the Debug menu's layer ticks do, and is re-attached to
     * each one in {@link #startMachine}.
     */
    private final Debugger debugger = new Debugger();

    /**
     * The device in the cartridge slot, which outlives the machines for the reason the breakpoints
     * do: a power cycle is exactly when somebody wants to watch a code take effect from the reset
     * vector. Only ever touched on the emulation thread, or in the window inside
     * {@link #startMachine} where the runner does not exist yet.
     */
    private final GameGenie genie = new GameGenie();

    /**
     * What the dialog is seeded from, and the one copy this thread may read.
     * <p>
     * Two fields for one idea, and deliberately: the device above is read by the emulation thread on
     * every instruction the processor fetches, so reading its list here would be a race. This is the
     * authority, {@link #startMachine} replays it into whatever machine is built next, and the two
     * are only ever written together.
     */
    private List<GameGenieCode> genieCodes = List.of();

    private CHRViewerFrame chrViewerFrame;
    private NametableViewerFrame nametableViewerFrame;
    private OAMViewerFrame oamViewerFrame;
    private DebuggerFrame debuggerFrame;

    /**
     * The trace being written, or null when none is. Not kept across cartridges: a file of one
     * game's instructions with another game's appended is a file nobody can read.
     */
    private Tracer tracer;

    private Path tracePath;
    private Cart cart;
    private NES nes;
    private EmulatorRunner runner;

    /**
     * Where the cartridge came from, which {@link Cart#filename()} does not say: it is handed a bare
     * name. The slot files and the battery file are named from this, so both live beside the ROM.
     */
    private Path romPath;

    /**
     * The name of the file inside {@link #romPath} the cartridge came out of, when that was a zip,
     * and null when it was the cartridge itself.
     * <p>
     * Kept because it is what {@link #gamePath} names this game's files after. A zip is a folder as
     * far as the saves are concerned: two games in one archive that were both filed under the
     * archive would share a {@code .sav} and nine save slots, and each would be reading the other's
     * progress as its own.
     */
    private String romEntry;

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

    /**
     * Whether a movie is being recorded, and where it is going when it stops.
     * <p>
     * The destination is asked for up front, before a single frame is recorded, which is the shape
     * {@code --record FILE} has and the shape that means somebody who forgets to stop cleanly has
     * still said where it goes. The recorder itself lives on the emulation thread; these two are the
     * window's own copy of "is one running", since it cannot read that field.
     */
    private boolean movieRecording;

    private Path recordingTo;

    private boolean moviePlaying;

    /**
     * A movie waiting for the machine that is about to be built to start playing it.
     * <p>
     * Playback always goes through a power cycle, whether the movie is anchored or not: a movie from
     * power on needs a machine that has not run, and one with a state inside it is going to replace
     * the machine anyway. One path rather than two, and the codes and the muting are put in place in
     * {@link #startMachine} where every other per-machine thing already is.
     */
    private Movie pendingMovie;

    /**
     * The overclock the machine is actually running, which is the menu's answer except while a
     * movie is playing: one pins its own when it starts, and the status bar describes the machine
     * rather than the menu. Written wherever the PPU's is.
     */
    private Overclock overclock = Overclock.NONE;

    /**
     * Where the window was before it filled the display, so that leaving full screen puts it back
     * where it was rather than wherever the graphics device chooses to drop it.
     */
    private @Nullable Rectangle windowedBounds;

    /**
     * Whether the pause in force is one this window took on its way into the background, rather
     * than one somebody asked for.
     * <p>
     * The difference is what may be undone. Coming back to the front lets go of this one and of
     * nothing else: a Pause somebody ticked, and a breakpoint -- which ticks the same box, see
     * {@link #stopped} -- both survive a trip to another application, because neither of them is
     * over.
     */
    private boolean pausedInBackground;

    public GameUIFrame() {
        super("MyNES");

        // Both, in one filter rather than two: a collection is a folder of zips, and one that has
        // been half unpacked is a folder of both. Picking between two filters to see the file that
        // is right there is a question nobody wants asked of them.
        var filter = new SystemFileChooser.FileNameExtensionFilter("NES cartridge", "nes", "zip");
        fileChooser = new SystemFileChooser();
        fileChooser.addChoosableFileFilter(filter);
        fileChooser.setFileFilter(filter);

        var patchFilter = new SystemFileChooser.FileNameExtensionFilter("IPS patch", "ips");
        patchChooser = new SystemFileChooser();
        patchChooser.addChoosableFileFilter(patchFilter);
        patchChooser.setFileFilter(patchFilter);

        var movieFilter = new SystemFileChooser.FileNameExtensionFilter("MyNES movie", "mnm");
        movieChooser = new SystemFileChooser();
        movieChooser.addChoosableFileFilter(movieFilter);
        movieChooser.setFileFilter(movieFilter);

        var traceFilter = new SystemFileChooser.FileNameExtensionFilter("Trace log", "log");
        traceChooser = new SystemFileChooser();
        traceChooser.addChoosableFileFilter(traceFilter);
        traceChooser.setFileFilter(traceFilter);

        config = Config.load(Config.DEFAULT_PATH);
        keyboardInput = new KeyboardInput(this, config.keyBindings());

        // Once, unlike the bindings: there is no dialog that moves this one, only the file.
        keyboardInput.setRewindKey(config.rewindKey());

        screen.setPalette(config.palette(currentRegion()));

        // Before init()'s pack(), so the window opens at the size it was left at rather than opening
        // at the default and then jumping. All three of these, because how big the picture is is
        // the magnification times however many scanlines are being shown by however wide the shape
        // of a pixel makes a line.
        applyPixelAspect();
        screen.setOverscan(config.overscan());
        screen.setLeftEdge(config.leftEdge());
        screen.setScale(config.screenScale());

        init();
    }

    private void init() {
        add(screen, BorderLayout.CENTER);

        // Straight onto the field rather than through applyStatusBar, which resizes the window: at
        // this point there is no window to resize, and pack() below is about to take the row into
        // account or not according to exactly this.
        statusBar.setVisible(config.statusBar());
        add(statusBar, BorderLayout.SOUTH);

        // On the window rather than on the picture, because the whole of it is what somebody aims a
        // cartridge at: a drop that worked over the middle and not over the status bar or the menu
        // bar would look broken rather than particular. Neither of those has a drop target of its
        // own, so AWT walks up to this one.
        // Nothing inside the archive is named here: a dropped zip goes through the same Open
        // that the menu's does, which is where the one cartridge in it is found and where a zip
        // holding several asks which.
        setTransferHandler(new RomDrop(rom -> open(rom, null, null)));

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

        // Under both of the things it is a shortcut for, since either kind of game can end up in
        // it. No accelerator and no numbers down the side: the list is read rather than counted,
        // and this window has already spent its unmodified function keys.
        fileMenuOpenRecent.setMnemonic(KeyEvent.VK_R);
        fileMenu.add(fileMenuOpenRecent);

        // A function key, for the reasons the two quick state items are on function keys: it is in
        // the same physical place on every keyboard layout, it is the key every emulator since ZSNES
        // has taken a picture with, and it needs no modifier -- Shift being Select, a shortcut
        // carrying one is a hazard here.
        fileMenuScreenshot.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F12, 0));
        fileMenuScreenshot.setEnabled(false);
        fileMenu.add(fileMenuScreenshot);

        // The screenshot key with the copy modifier on it, rather than Cmd-C, and for the reason
        // the function keys are here at all: a letter is somewhere different on every keyboard
        // layout. Cmd-C reads as the obvious choice and is not one -- on Colemak-DH the letter c
        // sits on the key labelled X, so the key somebody actually presses for copy sends Cmd-D
        // and nothing happens, which looks exactly like a broken shortcut. F12 is in the same
        // place on every layout there is, and this is F12's other half.
        fileMenuCopyScreenshot.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F12, command));
        fileMenuCopyScreenshot.setEnabled(false);
        fileMenu.add(fileMenuCopyScreenshot);

        fileMenu.addSeparator();

        JMenuItem fileMenuQuit = new JMenuItem("Quit", KeyEvent.VK_Q);
        fileMenuQuit.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Q, command));
        fileMenu.add(fileMenuQuit);

        machineMenu.setMnemonic(KeyEvent.VK_M);
        machineMenu.setEnabled(false);

        JMenuItem machineMenuReset = new JMenuItem("Reset", KeyEvent.VK_R);
        machineMenuReset.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_R, command));
        machineMenu.add(machineMenuReset);

        machineMenuPowerCycle.setAccelerator(
                KeyStroke.getKeyStroke(KeyEvent.VK_R, command | InputEvent.SHIFT_DOWN_MASK));
        machineMenu.add(machineMenuPowerCycle);

        machineMenuRegion = regionMenu();
        machineMenu.add(machineMenuRegion);

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

        // Beside the save states, because they answer the two halves of the same question: a slot
        // is where the machine got to and a movie is how it got there.
        machineMenuRecord.setMnemonic(KeyEvent.VK_E);
        machineMenuRecord.setEnabled(false);
        machineMenu.add(machineMenuRecord);

        machineMenuStopRecording.setEnabled(false);
        machineMenu.add(machineMenuStopRecording);

        machineMenuPlay.setMnemonic(KeyEvent.VK_Y);
        machineMenuPlay.setEnabled(false);
        machineMenu.add(machineMenuPlay);

        machineMenuStopPlayback.setEnabled(false);
        machineMenu.add(machineMenuStopPlayback);

        machineMenu.addSeparator();

        machineMenuPause.setMnemonic(KeyEvent.VK_P);
        machineMenuPause.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_P, command));
        machineMenu.add(machineMenuPause);

        // Under the item it is a setting on, which is the shape Fast Forward and Fast Forward
        // Speed have below it. No accelerator: it is a habit somebody picks once, not something
        // reached for mid-game.
        machineMenuPauseInBackground.setMnemonic(KeyEvent.VK_B);
        machineMenuPauseInBackground.setSelected(config.pauseInBackground());
        machineMenu.add(machineMenuPauseInBackground);

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

        machineMenu.add(volumeMenu());

        debugMenu.setMnemonic(KeyEvent.VK_D);
        debugMenu.setEnabled(false);

        JMenuItem debugMenuDebugger = new JMenuItem("Debugger", KeyEvent.VK_D);
        debugMenu.add(debugMenuDebugger);

        JMenuItem debugMenuCHRViewer = new JMenuItem("CHR Viewer", KeyEvent.VK_C);
        debugMenu.add(debugMenuCHRViewer);

        JMenuItem debugMenuNametableViewer = new JMenuItem("Nametable Viewer", KeyEvent.VK_N);
        debugMenu.add(debugMenuNametableViewer);

        JMenuItem debugMenuOAMViewer = new JMenuItem("OAM Viewer", KeyEvent.VK_O);
        debugMenu.add(debugMenuOAMViewer);

        debugMenu.addSeparator();

        // Its own item rather than a tick, because starting one asks where it should go and stopping
        // one does not -- the shape of Record Movie... in the Machine menu, and for the same reason.
        debugMenuTrace.setMnemonic(KeyEvent.VK_T);
        debugMenu.add(debugMenuTrace);

        debugMenuStopTrace.setEnabled(false);
        debugMenu.add(debugMenuStopTrace);

        debugMenu.addSeparator();

        debugMenuBackground.setMnemonic(KeyEvent.VK_B);
        debugMenu.add(debugMenuBackground);

        debugMenuSprites.setMnemonic(KeyEvent.VK_S);
        debugMenu.add(debugMenuSprites);

        debugMenu.add(soundChannelsMenu());

        // Not gated on a machine, unlike Debug: mostly these are preferences that are remembered and
        // re-applied to whatever runs next, so there is something to change before a ROM is open. The
        // Game Genie is the exception and gates itself, being written for one particular game.
        // Mnemonic A rather than H, which is Help's.
        JMenu hacksMenu = new JMenu("Hacks");
        hacksMenu.setMnemonic(KeyEvent.VK_A);

        hacksMenuUnlimitedSprites.setMnemonic(KeyEvent.VK_U);
        hacksMenuUnlimitedSprites.setSelected(config.unlimitedSprites());
        hacksMenu.add(hacksMenuUnlimitedSprites);

        hacksMenuOverclock = overclockMenu();
        hacksMenu.add(hacksMenuOverclock);

        hacksMenuGameGenie.setMnemonic(KeyEvent.VK_G);
        hacksMenuGameGenie.setEnabled(false);
        hacksMenu.add(hacksMenuGameGenie);

        JMenu settingsMenu = new JMenu("Settings");
        settingsMenu.setMnemonic(KeyEvent.VK_S);

        JMenuItem settingsMenuController = new JMenuItem("Controller...", KeyEvent.VK_C);
        settingsMenu.add(settingsMenuController);

        settingsMenu.add(settingsMenuPalette);

        settingsMenu.add(videoFilterMenu());

        // Beside the filters rather than in with them, and above the sizes: what it changes is how
        // much of the frame is a picture, which is the same question they answer differently and
        // not the same question as how big that picture is drawn. It takes the window's height
        // with it, which is why it is not in the group below either.
        settingsMenuOverscan.setMnemonic(KeyEvent.VK_O);
        settingsMenuOverscan.setSelected(config.overscan());
        settingsMenu.add(settingsMenuOverscan);

        // Beside it, because it is the other half of the same question -- how much of the frame is
        // a picture -- even though it is asked the other way up. What the chip clips down the left
        // edge it was told to clip, so those columns are drawn until somebody says otherwise,
        // where the sixteen scanlines are hidden until somebody says otherwise.
        settingsMenuLeftEdge.setMnemonic(KeyEvent.VK_L);
        settingsMenuLeftEdge.setSelected(config.leftEdge());
        settingsMenu.add(settingsMenuLeftEdge);

        // Under both of them, because it is the third thing that decides the shape of the picture
        // and the only one of the three that is not a crop: those two say which of the chip's rows
        // and columns are picture, and this says how wide one of those columns is drawn. None of
        // them is a setting on a filter -- all three filters draw whatever these three say -- which
        // is why the group sits between Video Filter and the sizes rather than inside either.
        settingsMenuTvAspect.setMnemonic(KeyEvent.VK_T);
        settingsMenuTvAspect.setSelected(config.tvAspect());
        settingsMenuTvAspect.addActionListener(e -> {
            config.setTvAspect(settingsMenuTvAspect.isSelected());
            saveConfig();
            applyTvAspect();
        });
        settingsMenu.add(settingsMenuTvAspect);

        settingsMenuScreenSize = screenSizeMenu();
        settingsMenu.add(settingsMenuScreenSize);
        settingsMenu.add(screenshotSizeMenu());

        // Beside the status bar rather than among the sizes above, for the reason given there:
        // both of these change the shape of the window and neither changes the picture in it.
        // A function key, and the one every browser and every emulator since ZSNES has used --
        // which also means it needs no modifier, and Shift being Select, a shortcut carrying one
        // is a hazard here.
        settingsMenuFullScreen.setMnemonic(KeyEvent.VK_F);
        settingsMenuFullScreen.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0));
        settingsMenu.add(settingsMenuFullScreen);

        // Under the sizes rather than beside the palette: what this changes is the shape of the
        // window, not the picture in it.
        settingsMenuStatusBar.setMnemonic(KeyEvent.VK_B);
        settingsMenuStatusBar.setSelected(config.statusBar());
        settingsMenu.add(settingsMenuStatusBar);

        JMenu helpMenu = new JMenu("Help");
        helpMenu.setMnemonic(KeyEvent.VK_H);

        JMenuItem helpMenuAbout = new JMenuItem("About", KeyEvent.VK_A);
        helpMenu.add(helpMenuAbout);

        menuBar.add(fileMenu);
        menuBar.add(machineMenu);
        menuBar.add(debugMenu);
        menuBar.add(hacksMenu);
        menuBar.add(settingsMenu);
        menuBar.add(helpMenu);

        setJMenuBar(menuBar);

        // Once the menu bar exists, because this greys two of its items out. The filter itself is
        // a setting the config already holds; what is being applied here is the pair of
        // consequences.
        applyVideoFilter();

        // Every key event in the application comes past here before anything else sees it, which
        // is how the game gets the arrow keys without taking them off the menu bar.
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(keyboardInput);

        settingsMenuOverscan.addActionListener(e -> {
            config.setOverscan(settingsMenuOverscan.isSelected());
            saveConfig();
            applyCrop();
        });

        settingsMenuLeftEdge.addActionListener(e -> {
            config.setLeftEdge(settingsMenuLeftEdge.isSelected());
            saveConfig();
            applyCrop();
        });

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

                    if (nametableViewerFrame != null) {
                        nametableViewerFrame.setPalette(chosen);
                    }

                    if (oamViewerFrame != null) {
                        oamViewerFrame.setPalette(chosen);
                    }

                    saveConfig();
                }).setVisible(true));

        settingsMenuFullScreen.addActionListener(
                e -> applyFullScreen(settingsMenuFullScreen.isSelected()));

        // The way out for somebody who reached full screen with the mouse and does not know which
        // key put them there. On the root pane rather than as a second accelerator, which a menu
        // item has no room for.
        //
        // KeyboardInput sees it first and will keep it if somebody has put a controller button on
        // Escape, the same way the game wins over rewind. That is the right way round: a key bound
        // to a button is a button.
        getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "leave-full-screen");
        getRootPane().getActionMap().put("leave-full-screen", new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                leaveFullScreen();
            }
        });

        machineMenuPauseInBackground.addActionListener(e -> {
            config.setPauseInBackground(machineMenuPauseInBackground.isSelected());
            saveConfig();
        });

        settingsMenuStatusBar.addActionListener(e -> {
            config.setStatusBar(settingsMenuStatusBar.isSelected());
            saveConfig();
            applyStatusBar(settingsMenuStatusBar.isSelected());
        });

        fileMenuScreenshot.addActionListener(e -> takeScreenshot());
        fileMenuCopyScreenshot.addActionListener(e -> copyScreenshot());

        fileMenuOpen.addActionListener(e -> {
            if (fileChooser.showOpenDialog(this) == SystemFileChooser.APPROVE_OPTION) {
                open(fileChooser.getSelectedFile(), null, null);
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
                open(rom, null, patchChooser.getSelectedFile());
            }
        });

        // The reset button on the console: memory survives, the CPU restarts through its reset
        // vector. Posted rather than called because only the emulation thread touches the NES.
        machineMenuReset.addActionListener(e -> {
            if (runner != null) {
                // Through the runner rather than posted straight at the machine, because a movie
                // being recorded has to be told about a reset before the machine sees it -- and one
                // posted command is the only way to be sure of that order.
                runner.reset();
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

        machineMenuRecord.addActionListener(e -> startRecording());
        machineMenuStopRecording.addActionListener(e -> stopRecording());
        machineMenuPlay.addActionListener(e -> playMovie());

        // Only the runner is told: it ends the playback and hands the news back here, which is the
        // same path the last frame of a movie takes. One place decides what stopping looks like.
        machineMenuStopPlayback.addActionListener(e -> {
            if (runner != null) {
                runner.stopPlayback();
            }
        });

        // For the reason the slots are relabelled when the Machine menu opens: what is on the disk
        // moves without anybody telling the emulator about it.
        fileMenu.addMenuListener(new MenuListener() {
            @Override
            public void menuSelected(final MenuEvent e) {
                describeRecent();
            }

            @Override
            public void menuDeselected(final MenuEvent e) {
            }

            @Override
            public void menuCanceled(final MenuEvent e) {
            }
        });

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

            describeMachine();
        });

        machineMenuFastForward.addActionListener(e -> applySpeed());

        // Remembered between runs, unlike whether fast forward is on: a machine that came back
        // silent is explained by the tick sitting next to this item, and a machine that came back
        // fast forwarding just looks broken.
        machineMenuMute.addActionListener(e -> {
            config.setMuted(machineMenuMute.isSelected());
            saveConfig();
            updateStatusBar();

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

        // Remembered between runs, unlike the two layer switches above: those are a debug view of
        // the machine that is running, and this is how somebody wants their games to look.
        hacksMenuUnlimitedSprites.addActionListener(e -> {
            config.setUnlimitedSprites(hacksMenuUnlimitedSprites.isSelected());
            saveConfig();
            updateStatusBar();

            if (runner != null) {
                var ppu = nes.getPPU();
                var unlimited = hacksMenuUnlimitedSprites.isSelected();
                runner.post(() -> ppu.setUnlimitedSprites(unlimited));
            }
        });

        // Not remembered between runs, unlike the tick above: a code is written for one cartridge and
        // would be nonsense applied to the next one, so there is nowhere sensible to keep it. The
        // whole list goes over on every change and is replayed onto the device, which keeps the rule
        // about what two codes for one address mean in the device rather than agreed between two.
        hacksMenuGameGenie.addActionListener(e ->
                new GameGenieDialog(this, genieCodes, updated -> {
                    genieCodes = updated;

                    updateStatusBar();

                    if (runner != null) {
                        runner.post(() -> {
                            genie.clear();
                            updated.forEach(genie::add);
                        });
                    }
                }).setVisible(true));

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

        debugMenuNametableViewer.addActionListener(e -> {
            if (noCartridge()) {
                return;
            }

            if (nametableViewerFrame == null) {
                nametableViewerFrame = new NametableViewerFrame(
                        this, nes, config.palette(currentRegion()));
            }

            nametableViewerFrame.setVisible(true);
        });

        debugMenuOAMViewer.addActionListener(e -> {
            if (noCartridge()) {
                return;
            }

            if (oamViewerFrame == null) {
                oamViewerFrame = new OAMViewerFrame(
                        this, nes.getPPU(), config.palette(currentRegion()));
            }

            oamViewerFrame.setVisible(true);
        });

        debugMenuTrace.addActionListener(e -> startTrace());
        debugMenuStopTrace.addActionListener(e -> stopTrace());

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

                // And a trace holds up to sixty-four kilobytes of instructions that have not reached
                // the disk yet, which is the end of whatever the file was opened to look at.
                stopTrace();
            }

            // Cmd-tabbing away in the middle of a jump would otherwise leave the button held down
            // for as long as the window is gone: the release lands in whatever took the focus.
            @Override
            public void windowDeactivated(final WindowEvent e) {
                keyboardInput.releaseAll();
                pauseForBackground(e.getOppositeWindow());
            }

            @Override
            public void windowActivated(final WindowEvent e) {
                resumeFromBackground();
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

        // The frame rate is the one thing on the bar that nothing announces: it is a measurement
        // rather than a setting, so it has to be gone and looked at. Everything else the bar says
        // is worked out on the same tick as well, which is the backstop for a state nobody thought
        // to refresh it from.
        var status = new Timer(STATUS_INTERVAL_MILLIS, e -> {
            measureFrameRate();
            updateStatusBar();
        });

        status.setRepeats(true);
        status.start();

        // Once before the first tick, so the bar opens describing the settings the next cartridge
        // will run under rather than blank.
        updateStatusBar();

        pack();
        setLocationRelativeTo(null);
    }

    /**
     * Builds the Volume submenu: two steps and five positions.
     * <p>
     * Louder and Quieter are in here with the positions rather than out in the Machine menu,
     * because they are the same setting reached the other way round -- and because they are the
     * only two things in this program anybody adjusts while looking at the screen rather than at
     * the menu, which is what the accelerators are for. Command with the two keys either side of
     * the minus sign, which sit in the same physical place on every keyboard layout the way the
     * function keys do and the letters do not.
     * <p>
     * The dot moves whichever way the volume was changed, so a Louder that lands on 75% ticks 75%.
     */
    private JMenu volumeMenu() {
        var menu = new JMenu("Volume");
        menu.setMnemonic(KeyEvent.VK_V);

        var command = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        var louder = new JMenuItem("Louder", KeyEvent.VK_L);
        louder.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_EQUALS, command));
        louder.addActionListener(e -> applyVolume(config.volume().louder()));
        menu.add(louder);

        var quieter = new JMenuItem("Quieter", KeyEvent.VK_Q);
        quieter.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_MINUS, command));
        quieter.addActionListener(e -> applyVolume(config.volume().quieter()));
        menu.add(quieter);

        menu.addSeparator();

        // The group is what makes them one choice rather than five independent ticks.
        var group = new ButtonGroup();

        for (var volume : Volume.values()) {
            var item = new JRadioButtonMenuItem(volume.label(), volume == config.volume());

            item.addActionListener(e -> applyVolume(volume));

            group.add(item);
            menu.add(item);
            machineMenuVolumes.put(volume, item);
        }

        return menu;
    }

    /**
     * Remembers a volume, ticks it, and tells the running machine.
     * <p>
     * One path for all three ways of picking one, because Louder and Quieter have to move the dot
     * as well -- and a listener that fired on the way would set the volume it was leaving.
     */
    private void applyVolume(final Volume volume) {
        config.setVolume(volume);
        saveConfig();

        machineMenuVolumes.get(volume).setSelected(true);
        updateStatusBar();

        if (runner != null) {
            runner.setVolume(volume);
        }
    }

    /**
     * Builds the Sound Channels submenu, one tick per voice the APU mixes.
     * <p>
     * Beside Show Background and Show Sprites rather than anywhere near Mute, because it is the same
     * kind of thing as those and not the same kind of thing as that: Mute is how loudly the machine
     * is played and these change what it is playing. Ticked means audible, the way ticked means
     * visible above.
     * <p>
     * What it is for is the question "which voice is that?". Untick four of them and the fifth is
     * alone; untick one and hear what the music loses. Nothing a game can observe moves -- see
     * {@link APUChannel} -- so the machine goes on running exactly as it was.
     */
    private JMenu soundChannelsMenu() {
        var menu = new JMenu("Sound Channels");
        menu.setMnemonic(KeyEvent.VK_U);

        for (var channel : APUChannel.values()) {
            var item = new JCheckBoxMenuItem(channel.label(), true);

            item.addActionListener(e -> {
                if (runner != null) {
                    runner.setChannelMuted(channel, !item.isSelected());
                }
            });

            menu.add(item);
            debugMenuChannels.put(channel, item);
        }

        return menu;
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
     * Builds the Overclock submenu, one item per percentage {@link OverclockSetting} offers.
     * <p>
     * A percentage rather than a number of scanlines because that is the question a player is
     * asking: a game that drops frames wants more time to do its work in, and how many lines that
     * comes to depends on which machine it turns out to be running on. Picking one applies it to the
     * machine already running, so a scene that slows down can be watched doing it and then not.
     * <p>
     * <strong>Unlike the tick above it, this one changes what the game does.</strong> Nothing about
     * the picture is faked -- the beam idles through extra blanking lines and draws the same frame
     * -- but the main loop gets more cycles between one NMI and the next, so a game whose logic
     * overran its frame stops skipping one. Which means an overclocked run is not the game as it
     * shipped, and the every-other-frame stutter some of them were written around goes with it.
     */
    private JMenu overclockMenu() {
        var menu = new JMenu("Overclock");
        menu.setMnemonic(KeyEvent.VK_O);

        // The group is what makes them one choice rather than five independent ticks.
        var group = new ButtonGroup();

        for (var setting : OverclockSetting.values()) {
            var item = new JRadioButtonMenuItem(setting.label(), setting == config.overclock());

            item.addActionListener(e -> {
                config.setOverclock(setting);
                saveConfig();

                if (runner != null) {
                    // Resolved here rather than on the emulation thread: the region is what turns a
                    // percentage into scanlines, and it belongs to the machine this thread owns.
                    overclock = setting.resolve(nes.getRegion());

                    // Copied out of the field before the lambda closes over it. A lambda reading
                    // the field would read it on the emulation thread, whenever the queue got to
                    // it, which is neither this thread's value nor safe to ask for.
                    var chosen = overclock;
                    var ppu = nes.getPPU();

                    runner.post(() -> ppu.setOverclock(chosen));
                    updateStatusBar();
                }
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
     * Builds the Video Filter submenu: how a frame of colour indices becomes a picture.
     * <p>
     * Beside the palette rather than in the Hacks menu, because it is not one. A hack is the
     * console doing something it did not do; this is a television doing what it always did, and the
     * question the decoder answers -- what colour is entry $21 -- is the palette's question asked a
     * second way. Which is why those two are mutually exclusive and why {@link #applyVideoFilter}
     * greys the palette out while the decoder is on. The tube is not a rival answer to that
     * question, so it leaves the palette alone. Below the separator are the two settings on the
     * items above rather than rivals to them: the strength, which both filters read, and the curve
     * of the glass, which only the tube has.
     */
    private JMenu videoFilterMenu() {
        var menu = new JMenu("Video Filter");
        menu.setMnemonic(KeyEvent.VK_V);

        var group = new ButtonGroup();

        for (var filter : VideoFilter.values()) {
            var item = new JRadioButtonMenuItem(filter.label(), filter == config.videoFilter());

            item.addActionListener(e -> {
                config.setVideoFilter(filter);
                saveConfig();
                applyVideoFilter();
            });

            group.add(item);
            menu.add(item);
            settingsMenuVideoFilters.put(filter, item);
        }

        settingsMenuFilterStrength = filterStrengthMenu();

        settingsMenuWarp.setSelected(config.warp());
        settingsMenuWarp.addActionListener(e -> {
            config.setWarp(settingsMenuWarp.isSelected());
            saveConfig();
            applyVideoFilter();
        });

        menu.addSeparator();
        menu.add(settingsMenuFilterStrength);
        menu.add(settingsMenuWarp);

        return menu;
    }

    /**
     * Builds the Strength submenu: how hard the filter above is applied -- how much of the detail
     * the decoder's chroma trap costs it gives back, or how dark the tube's gaps go.
     * <p>
     * Inside Video Filter rather than beside it, and below a separator, because it is a setting on
     * the items above it rather than another thing to choose between them --
     * {@link #applyVideoFilter} greys it out whenever the item in force has no use for it.
     */
    private JMenu filterStrengthMenu() {
        var menu = new JMenu("Strength");
        var group = new ButtonGroup();

        for (var strength : FilterStrength.values()) {
            var item = new JRadioButtonMenuItem(
                    strength.label(), strength == config.filterStrength());

            item.addActionListener(e -> {
                config.setFilterStrength(strength);
                saveConfig();
                applyVideoFilter();
            });

            group.add(item);
            menu.add(item);
        }

        return menu;
    }

    /**
     * Points the screen at whichever filter is in force, and greys out whatever the choice makes
     * meaningless.
     * <p>
     * Two things can make the choice meaningless. A PAL machine is one: the 2C07 draws a different
     * signal and the decoder here is not it, so that item alone goes grey and the picture falls
     * back to the palette -- while the tick stays where it was, because the setting is somebody's
     * preference about NTSC games and a European cartridge should not take it away. The tube keeps
     * its item, since every one of these machines was plugged into one. The palette dialog is the
     * other: a decoder works its colours out from the signal and never opens the table, so offering
     * a choice of table while it is running would be offering a setting that does nothing.
     */
    private void applyVideoFilter() {
        var filter = currentVideoFilter();

        screen.setVideoFilter(filter, config.filterStrength(), config.warp());
        settingsMenuVideoFilters.get(VideoFilter.NTSC).setEnabled(currentRegion() != Region.PAL);
        settingsMenuFilterStrength.setEnabled(filter != VideoFilter.NONE);
        settingsMenuWarp.setEnabled(filter == VideoFilter.CRT);
        settingsMenuPalette.setEnabled(filter != VideoFilter.NTSC);

        updateStatusBar();
    }

    /**
     * Shows the scanlines a television hid behind its bezel and the columns the chip clips down the
     * left edge, or stops, and gives the window the rows and the columns rather than taking them
     * out of the picture.
     * <p>
     * Packed the way a screen size is, and for the reason {@link ScreenComponent#setOverscan}
     * gives: the picture really is a different size, and a window that kept its own would answer a
     * request to see more of the frame by making everything else smaller. Unlike
     * {@link #applyScreenScale} a maximized window is left maximized -- there is no tick here
     * against a size nobody is looking at, only a picture that lays itself out again inside
     * whatever room the window manager gave it.
     * <p>
     * A full screen window is that case and one step further: packing one would hand it back a
     * size while the display still held it, so the rows and the columns come out of the picture
     * there instead. Unlike Screen Size neither of these is greyed out for it -- how much of the
     * frame is a picture is a question worth asking at any size, and it is only the packing that a
     * full screen window has no use for. What takes them there is the size the window will be given
     * back, so that leaving full screen does not land on a shape decided before they were asked
     * for.
     */
    private void applyCrop() {
        // Asked of the content pane on either side of the change, the way applyStatusBar asks it
        // and for the same reason: the difference is the rows and the columns, however big the
        // magnification makes them.
        var before = getContentPane().getPreferredSize();

        screen.setOverscan(config.overscan());
        screen.setLeftEdge(config.leftEdge());

        var after = getContentPane().getPreferredSize();

        if (settingsMenuFullScreen.isSelected()) {
            growWindowedBounds(after.width - before.width, after.height - before.height);
        } else {
            pack();
        }

        updateStatusBar();
    }

    /**
     * Tells the screen how wide to draw a pixel: the television's shape, or the square one the
     * framebuffer holds.
     * <p>
     * The number is the region's -- 8:7 on the 2C02 and about 1.386:1 on the 2C07 -- so it is
     * worked out here, where which machine is running is known, rather than in the component, which
     * has never been told. Which also means this has to run again whenever the machine changes, and
     * it does: {@link #startMachine} calls it beside the palette and the filter, for the same
     * reason both of those are called there.
     * <p>
     * <strong>It does not resize the window, which is where it parts company with
     * {@link #applyTvAspect}.</strong> The picture really is wider and the component says so, but
     * the two callers are asking different questions: the menu item is somebody asking for a
     * differently shaped picture, where a European cartridge arriving has merely moved the number
     * and is no reason to resize a window somebody had put somewhere.
     */
    private void applyPixelAspect() {
        screen.setPixelAspect(config.tvAspect()
                ? currentRegion().pixelAspect()
                : FrameRenderer.SQUARE_PIXELS);
    }

    /**
     * The same, for the tick rather than for the cartridge: the shape changes and the window is
     * given the columns rather than having them taken out of the picture.
     * <p>
     * {@link #applyCrop} down to the arithmetic -- it measures the content pane on either side of
     * the change and hands a full screen window's share to the size waiting for it, because a
     * display decides how big a full screen window is and the four screen sizes are greyed out
     * there for the same reason. What differs is only what moved: that one takes rows and columns
     * off the frame, and this one stretches the columns that are left.
     */
    private void applyTvAspect() {
        var before = getContentPane().getPreferredSize();

        applyPixelAspect();

        var after = getContentPane().getPreferredSize();

        if (settingsMenuFullScreen.isSelected()) {
            growWindowedBounds(after.width - before.width, after.height - before.height);
        } else {
            pack();
        }

        updateStatusBar();
    }

    /**
     * Which filter the picture is actually being drawn with, which is not always the one the menu
     * has ticked: a PAL machine draws a signal this decoder is not for, so <em>that</em> choice
     * falls back to the palette while the tick stays where it was. Nothing else falls back, the
     * tube included. Both the screen and the status bar ask this rather than the config, so that
     * neither can describe a picture nobody is looking at.
     */
    private VideoFilter currentVideoFilter() {
        var filter = config.videoFilter();

        return filter == VideoFilter.NTSC && currentRegion() == Region.PAL
                ? VideoFilter.NONE
                : filter;
    }

    /**
     * Shows the status bar or hides it, and gives the window the row rather than taking it out of
     * the picture.
     * <p>
     * The height is added to the window instead of the whole thing being packed, which is the
     * difference between a window that keeps whatever size it has been dragged to and one that
     * snaps back to a whole multiple of the picture every time this is ticked. A maximized window
     * cannot be resized at all and simply lays itself out again, which costs it the row, and a
     * full screen one is the same case.
     */
    private void applyStatusBar(final boolean show) {
        if (statusBar.isVisible() == show) {
            return;
        }

        // Asked of the content pane rather than of the bar, because a BorderLayout leaves a hidden
        // component out of its own preferred size: the difference between the two answers is the
        // row, however tall a look and feel decided that is, and neither reading depends on what a
        // component that is not being laid out would say about itself.
        var before = getContentPane().getPreferredSize().height;

        statusBar.setVisible(show);

        var after = getContentPane().getPreferredSize().height;

        // A full screen window is as big as the display and nothing else, so the row has to come
        // out of the picture there the way it does for a maximized one -- and the size waiting for
        // it takes the row instead.
        if (settingsMenuFullScreen.isSelected()) {
            growWindowedBounds(0, after - before);
        } else if (getExtendedState() == Frame.NORMAL) {
            setSize(getWidth(), getHeight() + after - before);
        }

        validate();
    }

    /**
     * Reads the frame counter and puts what the machine has been running at onto the bar.
     * <p>
     * From the timer and from nowhere else, which is the whole of why it is not part of
     * {@link #updateStatusBar}. A rate is measured across the gap between two readings, so one
     * taken because somebody opened a menu would measure a tenth of a second of a machine -- six
     * frames, which rounds to anything -- and would leave the tick after it measuring nine tenths.
     * The description has no such memory and can be rewritten as often as anything changes.
     */
    private void measureFrameRate() {
        if (runner == null) {
            statusBar.setFrameRate(FrameRate.UNKNOWN);
            return;
        }

        statusBar.setFrameRate(frameRate.sample(runner.getFramesRun(), System.nanoTime()));
    }

    /**
     * Puts what the machine is and what it is doing onto the status bar.
     * <p>
     * Called from {@link #describeMachine} whenever something changes, so that a setting somebody
     * has just picked is on the bar before they have let go of the menu, and again on the timer as
     * the backstop for anything nobody thought to call it from. It runs on the event dispatch
     * thread and asks the emulation thread for nothing: all of this is the window's own state.
     * <p>
     * The description is written even with no cartridge loaded, because every part of it is a
     * setting the next one will run under. The activity is not: there is nothing being done to a
     * machine that does not exist.
     * <p>
     * The whole of it is rebuilt each time rather than the changed part, because most of what goes
     * into it only ever appears in the tooltip -- and a screen size or a rewind length that had to
     * remember to announce itself to the bar would be the one that eventually did not.
     */
    private void updateStatusBar() {
        // With no machine there is no overclock on one, so the menu's answer stands in -- otherwise
        // the bar would say a machine is running the hardware's timing while the Hacks menu has
        // +50% ticked. Everything else here is read the same way for the same reason: what is on
        // the bar before a cartridge is loaded is what the next one will run under.
        var extra = runner == null ? config.overclock().resolve(currentRegion()) : overclock;

        statusBar.setMachine(new StatusBar.Machine(
                currentRegion(),
                config.region(),
                extra,
                genieCodes.size(),
                config.unlimitedSprites(),
                currentVideoFilter(),
                config.filterStrength(),
                config.warp(),
                config.overscan(),
                config.leftEdge(),
                config.tvAspect(),
                config.palette(currentRegion()).name(),
                config.screenScale(),
                config.screenshotScale(),
                config.fastForwardSpeed(),
                config.rewindSeconds(),
                config.muted(),
                config.volume(),
                config.audioLatencyMs()));

        statusBar.setActivity(machineState());
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
                updateStatusBar();
            });

            group.add(item);
            menu.add(item);
        }

        return menu;
    }

    /**
     * Builds the Screenshot Size submenu: how many times File &gt; Screenshot magnifies the picture
     * on its way out, into the file or onto the clipboard.
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
                updateStatusBar();
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
     * Puts the same picture on the system clipboard instead, at the same magnification.
     * <p>
     * The other half of taking a picture, and the half the file is in the way of: a screenshot
     * headed straight for a message, a bug report or a wiki is one nobody wants a copy of left
     * beside the ROM afterwards. Which is why it is the screenshot size that decides how big this
     * one is, rather than a setting of its own -- the question a picture leaving the emulator asks
     * is the same question whichever way it leaves.
     * <p>
     * The picture comes off the frame this thread is already holding, exactly as
     * {@link #takeScreenshot} takes it and for the same reasons, so it works on a machine that is
     * paused or stopped at a breakpoint. There is no ROM path in it: nothing here is named after
     * the game, because nothing here is a file.
     */
    private void copyScreenshot() {
        var image = screen.snapshot(config.screenshotScale());

        if (image == null) {
            // The same sixtieth of a second between a machine starting and its first finished
            // frame that takeScreenshot steps around.
            return;
        }

        try {
            Toolkit.getDefaultToolkit()
                    .getSystemClipboard()
                    .setContents(new ImageSelection(image), null);

            logger.log(Level.INFO, "copied the picture to the clipboard");
        } catch (IllegalStateException e) {
            // Another program has the clipboard open and has not given it back. Rare, and worth a
            // dialog rather than a log line: the failure is otherwise invisible until somebody
            // pastes and gets whatever they copied before this.
            logger.log(Level.ERROR, "could not reach the clipboard", e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not put the picture on the clipboard: " + e.getMessage(),
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
        // one just asked for, leaving a tick in the menu against a size nobody is looking at. Full
        // screen is the same problem and is stopped earlier, by the greyed-out submenu.
        setExtendedState(Frame.NORMAL);

        pack();
    }

    /**
     * Gives the window the whole display, or gives the display back.
     * <p>
     * There is nothing here about the picture, which is the point: {@link ScreenComponent} has
     * always fitted the frame to whatever size it is given, centred and letterboxed, so a window
     * the size of a display is a window somebody has dragged very large. What full screen adds is
     * the part a corner cannot be dragged to -- the display's own edges, with the desktop behind
     * it gone.
     * <p>
     * The menu bar goes with it rather than being hidden. It is the only way back for somebody who
     * arrived here with the mouse, and hiding it would take the accelerators down with it: an
     * accelerator belongs to the menu bar that carries it, so a hidden menu would cost F11 the key
     * that leaves.
     * <p>
     * Not remembered between runs, unlike the four sizes and unlike every other tick in this menu.
     * Those are answers to "how should the emulator look"; this is where the window is at the
     * moment, which is the same kind of thing as where it has been dragged to and how large it has
     * been made -- and neither of those is written down either.
     */
    private void applyFullScreen(final boolean full) {
        var device = getGraphicsConfiguration().getDevice();

        // The four sizes pack the window around a whole multiple of the picture, which is not a
        // thing a window filling the display can be at. Greyed out rather than left to fight it.
        settingsMenuScreenSize.setEnabled(!full);

        if (full) {
            windowedBounds = getBounds();
            device.setFullScreenWindow(this);
            return;
        }

        device.setFullScreenWindow(null);

        // Where it was, rather than where coming out of full screen happens to leave it. The
        // platforms disagree about that -- some restore the bounds and some do not -- and a window
        // that came back somewhere else every time would be the emulator's doing either way.
        if (windowedBounds != null) {
            setBounds(windowedBounds);
        }
    }

    /**
     * What Escape does, and it does nothing at all the rest of the time -- a key that left full
     * screen and also acted somewhere else would be a key nobody could press safely.
     * <p>
     * The tick is moved rather than clicked: {@link AbstractButton#doClick()} holds the event
     * dispatch thread for the length of a keypress it is pretending to make, which is the frame
     * and a bit the window would spend not drawing the game.
     */
    private void leaveFullScreen() {
        if (!settingsMenuFullScreen.isSelected()) {
            return;
        }

        settingsMenuFullScreen.setSelected(false);
        applyFullScreen(false);
    }

    /**
     * Gives the size waiting for the end of full screen the change the live window would have
     * taken.
     * <p>
     * The four ticks that move the window's size -- the status bar, the overscan, the left edge
     * and the TV aspect ratio -- cannot move a full screen window's, because the display decides
     * that one. Without this the window would come back at the size it had before whichever of
     * them was moved, and the row, the sixteen lines, the eight columns or the stretch would come
     * out of the picture rather than out of the window.
     * <p>
     * By the difference rather than to the packed size, which is the choice {@link #applyStatusBar}
     * makes for the same reason: a window that has been dragged wider should still be that much
     * wider than its picture when it comes back.
     */
    private void growWindowedBounds(final int wider, final int taller) {
        if (windowedBounds != null) {
            windowedBounds.width += wider;
            windowedBounds.height += taller;
        }
    }

    /**
     * Stops the game when the window goes behind another application, if that is what somebody
     * asked for.
     * <p>
     * <strong>Only another application counts.</strong> {@code gained} is the window that took the
     * focus, and it is null exactly when that window is not one of this program's -- so the
     * debugger, the two PPU viewers, the CHR viewer and every dialog leave the machine running.
     * That is not politeness: <b>Settings &gt; Palette...</b> previews a palette over the running
     * game and the CHR viewer refreshes from a machine that is being clocked, and both would show
     * a still picture instead if reaching for them stopped it.
     * <p>
     * A machine that is already stopped is left alone, which is what keeps this from being a second
     * answer to a question somebody else has answered: a ticked Pause and a breakpoint both mean
     * the machine is meant to be standing still, and neither is over because the window went away.
     */
    private void pauseForBackground(final @Nullable Window gained) {
        if (gained != null
                || runner == null
                || !machineMenuPauseInBackground.isSelected()
                || machineMenuPause.isSelected()) {
            return;
        }

        pausedInBackground = true;

        // Not through the Pause item, which is somebody's own answer and has to still be there
        // when they come back. The title bar and the status bar read the machine rather than the
        // tick, so both say Paused either way.
        runner.setPaused(true);
        describeMachine();
    }

    /**
     * Starts it again on the way back, and only if this is what stopped it.
     * <p>
     * {@link EmulatorRunner#setPaused} rather than {@link EmulatorRunner#resume}, which is the
     * whole of the difference between coming back from another application and unticking Pause:
     * resuming also forgets whatever the debugger was waiting for, and a window going into the
     * background never told it to wait for anything.
     */
    private void resumeFromBackground() {
        if (!pausedInBackground) {
            return;
        }

        pausedInBackground = false;

        // Something else may have stopped the machine while nobody was looking -- a breakpoint the
        // debugger window ran into, most likely, since that window is reachable while this one is
        // in the background. Whatever it was, it is still true.
        if (runner == null || machineMenuPause.isSelected()) {
            return;
        }

        runner.setPaused(false);
        describeMachine();
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

                // Inside the runnable rather than beside it, so a load that was refused leaves a
                // recording in progress describing the timeline it is still on.
                runner.noteMachineJumped();

                logger.log(Level.INFO, "loaded slot " + slot + ", now at frame " + nes.getPPU().getFrame());
            } catch (IOException | SaveStateException ex) {
                report("Could not load slot " + slot, ex);
            }
        });

        // The buttons the player was holding belong to the game they were playing a moment ago.
        keyboardInput.releaseAll();
    }

    // ==================================================================================== movies

    /**
     * Starts writing the session down, having first asked where it is going.
     * <p>
     * The destination up front rather than at the end, which is the shape {@code --record FILE} has:
     * somebody who plays for twenty minutes and then closes the window has still said where the take
     * belongs. It also means the answer to "am I recording?" is a file on disk rather than something
     * held only in memory.
     */
    private void startRecording() {
        if (runner == null || romPath == null || movieRecording || moviePlaying) {
            return;
        }

        movieChooser.setSelectedFile(defaultMoviePath().toFile());

        if (movieChooser.showSaveDialog(this) != SystemFileChooser.APPROVE_OPTION) {
            return;
        }

        recordingTo = movieChooser.getSelectedFile().toPath();
        movieRecording = true;

        // The pad moves to the emulation thread's own latch from here on. A press that reached the
        // controller half way through a frame would be written down as belonging to a frame it was
        // only half of, and the replay of it would be a different game.
        keyboardInput.setLatching(true);
        runner.startRecording(genieCodes);

        logger.log(Level.INFO, "recording to " + recordingTo.getFileName());

        updateMovieItems();
        describeMachine();
    }

    private void stopRecording() {
        if (runner == null || !movieRecording) {
            return;
        }

        var path = recordingTo;

        // The runner has already logged whatever went wrong and hopped back to this thread, so this
        // is only the dialog.
        runner.stopRecording(path, ex -> JOptionPane.showMessageDialog(
                this,
                "Could not write " + path.getFileName() + ": " + ex.getMessage(),
                "Error",
                JOptionPane.ERROR_MESSAGE));

        movieRecording = false;
        recordingTo = null;
        keyboardInput.setLatching(false);

        updateMovieItems();
        describeMachine();
    }

    /**
     * Opens a movie and plays it, from the beginning of the machine it was recorded on.
     * <p>
     * The header is read on this thread first -- sixty-eight bytes, no inflation -- so a movie from
     * another cartridge or another machine is refused while the game somebody is playing is still
     * playing. Only once it is going to work is the machine replaced.
     */
    private void playMovie() {
        if (cart == null || movieRecording) {
            return;
        }

        if (movieChooser.showOpenDialog(this) != SystemFileChooser.APPROVE_OPTION) {
            return;
        }

        var path = movieChooser.getSelectedFile().toPath();
        final Movie movie;

        try {
            var header = Movie.header(path);

            if (!header.romSHA256().equals(cart.sha256())) {
                refuseMovie(path, "It was recorded on another cartridge -- mapper "
                        + header.mapperNumber() + " " + header.romSHA256().substring(0, 12)
                        + ", where this one is mapper " + cart.mapperNumber() + " "
                        + cart.sha256().substring(0, 12) + ".");
                return;
            }

            if (header.region() != currentRegion()) {
                refuseMovie(path, "It was recorded on a " + header.region().label()
                        + " machine and this one is " + currentRegion().label()
                        + ". The cartridge is right, but a frame is not the same length on the two.");
                return;
            }

            movie = Movie.read(path);
        } catch (IOException | MovieException ex) {
            report("Could not read " + path.getFileName(), ex);
            return;
        }

        logger.log(Level.INFO, "playing " + path.getFileName() + ", " + movie.frameCount()
                + " frames" + (movie.anchored() ? " from a state inside it" : " from power on")
                + (movie.genie().isEmpty() ? ""
                : ", with " + movie.genie().size() + " Game Genie codes it was recorded with")
                + (movie.overclock().isNone() ? ""
                : ", and " + movie.overclock() + ", which it was recorded with"));

        // Consumed by startMachine, after the codes have been replayed and before the thread starts.
        pendingMovie = movie;

        startMachine(cart);
    }

    private void refuseMovie(final Path path, final String why) {
        JOptionPane.showMessageDialog(
                this,
                path.getFileName() + " will not play here.\n\n" + why,
                "Play Movie",
                JOptionPane.WARNING_MESSAGE);
    }

    /**
     * The movie has run out, or somebody stopped it. Called on the event dispatch thread, from the
     * runner, whichever of the two it was -- so there is one description of what stopping looks
     * like.
     * <p>
     * Control goes straight back to the keyboard with no pause and no dialog: the frame after the
     * last frame of a replay is the first frame of a game somebody is now playing.
     */
    private void playbackEnded(final EmulatorRunner from) {
        // Which machine's playback ended. The news arrives here a moment after the fact, and by then
        // the runner it came from may already have been replaced -- by one that is itself playing a
        // different movie, which this would otherwise stop before it had drawn a frame.
        if (from != runner || !moviePlaying) {
            return;
        }

        moviePlaying = false;
        keyboardInput.setPlaybackMuted(false);
        keyboardInput.setLatching(false);

        // The machine spent the replay on the movie's overclock, which may not be the menu's. The
        // runner is still alive and the game is somebody's again from the next frame, so the menu's
        // answer goes back on -- which is also what makes the greyed-out submenu tell the truth
        // about what is running the moment it comes back.
        overclock = config.overclock().resolve(nes.getRegion());

        // Copied out of the field for the reason the overclock menu copies it: a lambda that read
        // the field would read it on the emulation thread and at some later moment.
        var restored = overclock;
        var ppu = nes.getPPU();

        runner.post(() -> ppu.setOverclock(restored));

        updateMovieItems();
        describeMachine();
    }

    /**
     * Where a movie goes when nobody has said. Beside the ROM and named after it, the way the slots,
     * the battery file and the screenshots are.
     */
    private Path defaultMoviePath() {
        var name = gamePath().getFileName().toString();
        var dot = name.lastIndexOf('.');

        return gamePath().resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".mnm");
    }

    /**
     * Which of the movie items can be used, and which two things a movie stops somebody doing.
     * <p>
     * Power Cycle and Region both build a new machine, and the take lives in the runner that would
     * be torn down. The Game Genie goes with them for a different reason: a movie pins the codes
     * when it starts, so changing them half way through would leave a file that cannot be replayed
     * and does not say so. Overclock is pinned the same way and greyed out for the same reason,
     * with more at stake -- it is the one hack a replay's frames actually depend on.
     */
    private void updateMovieItems() {
        var busy = movieRecording || moviePlaying;

        machineMenuRecord.setEnabled(runner != null && !busy);
        machineMenuStopRecording.setEnabled(movieRecording);
        machineMenuPlay.setEnabled(cart != null && !busy);
        machineMenuStopPlayback.setEnabled(moviePlaying);

        machineMenuPowerCycle.setEnabled(!busy);
        machineMenuRegion.setEnabled(!busy);
        hacksMenuGameGenie.setEnabled(cart != null && !busy);
        hacksMenuOverclock.setEnabled(!busy);
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

    /**
     * Puts the games somebody has opened onto Open Recent, and greys out the ones whose files are
     * not where they were left.
     * <p>
     * Greyed rather than dropped, which is the same answer {@link #describeSlots} gives for a slot
     * with nothing in it: a cartridge on a volume that is not mounted this afternoon is still the
     * game somebody was playing, and a list that quietly shortened itself every time a drive was
     * unplugged would be worse than one with a dead entry in it.
     */
    private void describeRecent() {
        fileMenuOpenRecent.removeAll();

        var recent = config.recentRoms();

        // A submenu that opens onto an empty box reads as a broken menu rather than as an answer,
        // so before anything has been opened there is nothing to pull down at all.
        fileMenuOpenRecent.setEnabled(!recent.isEmpty());

        if (recent.isEmpty()) {
            return;
        }

        for (var game : recent) {
            var item = new JMenuItem(game.label());

            // The labels are file names, so two cartridges called the same thing in two folders are
            // two entries that read identically. This is the only thing that tells them apart.
            item.setToolTipText(game.describe());
            item.setEnabled(game.isThere());
            item.addActionListener(e -> open(
                    game.rom().toFile(),
                    game.entry(),
                    game.patch() == null ? null : game.patch().toFile()));

            fileMenuOpenRecent.add(item);
        }

        fileMenuOpenRecent.addSeparator();

        // The list is the only thing in the config file nobody chose, so it is the only thing worth
        // a way of taking back -- and where somebody has been playing is not always something they
        // want the next person at the computer to read off a menu.
        var clear = new JMenuItem("Clear Menu");

        clear.addActionListener(e -> {
            config.clearRecentRoms();
            saveConfig();
        });

        fileMenuOpenRecent.add(clear);
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

        describeMachine();
    }

    /**
     * The menu's way in: load a cartridge, and tell the player if it will not load.
     * <p>
     * A dialog rather than a log line, because the player has just picked the file out of a chooser
     * and is owed an answer about it. Everything {@link Cart#load},
     * {@link IPSPatch#read(byte[], String)} and {@link Archive#open} throw is unchecked, so the
     * three are caught together and the machine already running is left alone.
     *
     * @param entry which file inside {@code rom} is the cartridge, when Open Recent already knows.
     *              Null for a file picked out of the chooser, which is where the archive is looked
     *              in and, if it holds several, where the question is asked.
     */
    private void open(final File rom, final String entry, final File patch) {
        try {
            loadRom(rom, entry, patch);
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
     *                  to, and closing the emulator leaves no patched copy of it behind. Applied to
     *                  what came out of the zip when the file was one, which is the only thing an
     *                  offset in a patch could be counted from.
     */
    private void loadRom(final File selectedFile, final String wantedEntry, final File patchFile)
            throws IOException {
        logger.log(Level.INFO, "loading rom " + selectedFile.getName());

        byte[] file;
        try (var rom = new FileInputStream(selectedFile)) {
            file = rom.readAllBytes();
        }

        var unzipped = unzip(selectedFile, file, wantedEntry);

        if (unzipped == null) {
            // The player closed the dialog asking which cartridge in the archive they meant, which
            // is an answer. The machine already running is left alone.
            return;
        }

        var image = unzipped.bytes();
        var name = unzipped.name();

        if (patchFile != null) {
            // Before Cart.load rather than after, since a patch is entitled to rewrite the header
            // and so to change the mapper, the size of the banks, or anything else it decides.
            var patch = IPSPatch.read(Files.readAllBytes(patchFile.toPath()), patchFile.getName());

            image = patch.applyTo(image);

            logger.log(Level.INFO, "applied " + patch.records() + " records from "
                    + patchFile.getName());
        }

        // The name inside the archive rather than the archive's, because the only thing the Cart is
        // told its filename for is the title bar, and what belongs there is the game. Where the file
        // came from is romPath, which is the zip.
        var loaded = Cart.load(image, name == null
                ? selectedFile.getName() : Archive.fileNameOf(name));

        startMachine(
                loaded,
                selectedFile.toPath().toAbsolutePath(),
                name,
                patchFile == null ? null : patchFile.toPath().toAbsolutePath());

        // After the cartridge has loaded rather than when the file was picked, so that a file which
        // turned out not to be one is not offered again from the menu. Written out at once, since
        // the alternative is a list that only survives a tidy exit.
        config.addRecentRom(new RecentRom(
                selectedFile.toPath(), name,
                patchFile == null ? null : patchFile.toPath()));
        saveConfig();

        logger.log(Level.INFO, "loaded rom " + selectedFile.getName());
    }

    /**
     * A cartridge image, and the name it had inside the zip it came out of.
     *
     * @param name null when the file the player picked was the cartridge, which is what
     *             {@link #romEntry} then holds too.
     */
    private record Unzipped(byte[] bytes, String name) {
    }

    /**
     * Takes the cartridge out of the zip, when the file the player picked is one.
     * <p>
     * Decided by what is in the file rather than by what it is called, so a cartridge saved as
     * {@code game.zip.nes} still opens and a zip renamed on the way through a mail server still
     * does. Nothing is unpacked to disk: the bytes go straight on to the patcher and to
     * {@link Cart#load}, so opening a game leaves nothing behind to tidy up -- which is the promise
     * Open with Patch already makes about the ROM it does not write to.
     *
     * @param wanted the name Open Recent remembered, or null to work it out. A name the archive no
     *               longer holds is not an error: the zip has been repacked since, and asking again
     *               is a better answer than refusing to open a file that is plainly there.
     * @return what to run, or null when the player closed the question about which cartridge they
     *         meant.
     * @throws InvalidArchiveException if the zip will not open, or holds no cartridge.
     */
    private Unzipped unzip(final File file, final byte[] bytes, final String wanted) {
        if (!Archive.looksLikeOne(bytes)) {
            return new Unzipped(bytes, null);
        }

        var archive = Archive.open(bytes, file.getName());

        if (wanted != null) {
            for (var candidate : archive.files()) {
                if (candidate.name().equals(wanted)) {
                    return new Unzipped(candidate.bytes(), candidate.name());
                }
            }

            logger.log(Level.WARNING,
                    file.getName() + " no longer holds " + wanted + ", asking again");
        }

        var cartridges = archive.endingIn(ROM_EXTENSION);

        if (cartridges.isEmpty()) {
            throw new InvalidArchiveException(file.getName(), "nothing in it is named like a"
                    + " cartridge, only " + namesOf(archive.files()));
        }

        if (cartridges.size() == 1) {
            return new Unzipped(cartridges.getFirst().bytes(), cartridges.getFirst().name());
        }

        // Asked rather than guessed at. The first entry in a zip is whichever the packer happened to
        // write first, so opening it would be opening a different game from the one somebody meant
        // -- and quietly, since a title bar naming the archive would look exactly the same.
        var names = cartridges.stream().map(Archive.Entry::name).toArray(String[]::new);
        var chosen = JOptionPane.showInputDialog(
                this,
                file.getName() + " holds " + names.length + " cartridges.",
                "Open",
                JOptionPane.QUESTION_MESSAGE,
                null,
                names,
                names[0]);

        for (var candidate : cartridges) {
            if (candidate.name().equals(chosen)) {
                return new Unzipped(candidate.bytes(), candidate.name());
            }
        }

        // The dialog answers with one of the names it was given or with null, so getting here is
        // the player having closed it -- which is an answer, and leaves the machine already running
        // exactly where it was.
        return null;
    }

    /**
     * What was in an archive that turned out to hold no cartridge, for the dialog that says so.
     */
    private static String namesOf(final List<Archive.Entry> entries) {
        return entries.isEmpty() ? "nothing at all"
                : entries.stream().map(Archive.Entry::name).collect(Collectors.joining(", "));
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
        if (patchPath != null) {
            return patchPath;
        }

        // Beside the zip, named after the cartridge inside it, which is where the saves would have
        // landed had the player unpacked the archive themselves. Only the last segment of the name
        // is used, so an archive that files its cartridge under a folder still saves beside itself
        // rather than into a folder that is not there.
        return romEntry == null
                ? romPath : romPath.resolveSibling(Archive.fileNameOf(romEntry));
    }

    /**
     * Builds a fresh machine around a cartridge and starts it, replacing whatever was running.
     * Both loading a ROM and cycling the power come through here; the only difference is whether
     * the cartridge is new.
     */
    private void startMachine(final Cart cart) {
        startMachine(cart, romPath, romEntry, patchPath);
    }

    /**
     * The same, for a cartridge that has just been loaded from somewhere.
     *
     * @param rom   where the file was, which is what the Cart is not told. The zip rather than the
     *              cartridge when the player opened one.
     * @param entry the name the cartridge had inside that zip, or null when the file was the
     *              cartridge.
     * @param patch the IPS patch applied to it, or null.
     */
    private void startMachine(
            final Cart cart, final Path rom, final String entry, final Path patch) {
        // Before the outgoing machine is let go of, and while its runner is stopped so it is safe to
        // read from here. Both changing cartridges and cycling the power come through here, and a
        // power cycle that lost the last hour of a game would be a cruel way to find that out.
        //
        // Above the two lines below for the same reason it is above the rest of this method: the
        // fields still name the outgoing game, and saving after they had moved would write the
        // outgoing game's RAM into the incoming game's .sav -- which the incoming game would then
        // read back as its own progress.
        saveBattery();

        // Power Cycle and Region are greyed out while a movie is running, so the only way here with
        // one in progress is a new cartridge -- which is a decision worth honouring rather than
        // refusing. The take lives in the runner about to be stopped, so it goes with it; said out
        // loud, because a recording that vanished silently would look like a bug.
        if (movieRecording) {
            logger.log(Level.WARNING, "a movie was being recorded and the machine is being replaced,"
                    + " so the take was dropped");
        }

        movieRecording = false;
        recordingTo = null;
        moviePlaying = false;
        keyboardInput.setLatching(false);
        keyboardInput.setPlaybackMuted(false);

        // The slots, the battery file and the screenshots are named from these two, so a game keeps
        // its saves beside it -- and a hack keeps its own beside the patch, rather than writing over
        // the original's.
        romPath = rom;
        romEntry = entry;
        patchPath = patch;

        if (runner != null) {
            runner.stop();
        }

        // The old viewers are watching the old machine's memory and palettes; they would keep
        // showing them forever. Closed rather than repointed, since they are debug windows whose
        // contents are entirely derived from the machine.
        destroyCHRViewerFrame();
        destroyPPUViewerFrames();

        // And a trace of one machine with another machine's instructions appended is a file nobody
        // can read. Stopped here rather than at the new machine, so the last few thousand buffered
        // lines are on disk before anything else happens.
        stopTrace();

        // A new cartridge deserves a clean slate, but a power cycle does not: the breakpoints are
        // the reason somebody cycles the power. Asked before the field is reassigned, since that is
        // the whole of the difference between the two.
        var sameCartridge = cart == this.cart;

        if (!sameCartridge) {
            debugger.clear();
            destroyDebuggerFrame();

            // And the codes go with it, for the stronger version of the same reason: a breakpoint on
            // the wrong game is merely useless, where a code written for one cartridge is an
            // arbitrary byte written over another one.
            genieCodes = List.of();
        }

        this.cart = cart;
        nes = new NES(cart, config.region().resolve(cart));

        logger.log(Level.INFO, "running " + cart.filename() + " as " + nes.getRegion().label());

        // Which television this machine is plugged into. Only here, rather than everywhere a
        // palette is chosen, because this is the one moment the kind of machine can change.
        screen.setPalette(config.palette(nes.getRegion()));

        // And how wide a pixel is, which is the third thing the kind of machine decides: the two
        // consoles put a different number of pixels into the same line, so a television drew them
        // different shapes. The window is left at whatever size it has -- a cartridge being
        // European is not somebody asking for a differently shaped window.
        applyPixelAspect();

        // And which decoder, for the same reason and one more: the NTSC filter has no meaning on a
        // 2C07, so this is where a European cartridge takes it away and an American one gives it
        // back. After the shape rather than before, since its call to updateStatusBar is what tells
        // the bar about both.
        applyVideoFilter();

        // A fresh PPU has both layers on and no hacks, and a fresh APU has all five voices in the
        // mixer, but the menus remember what the last machine was told. The runner has not started
        // yet, so the machine is still this thread's to touch.
        nes.getPPU().setBackgroundLayerVisible(debugMenuBackground.isSelected());
        nes.getPPU().setSpriteLayerVisible(debugMenuSprites.isSelected());
        nes.getPPU().setUnlimitedSprites(hacksMenuUnlimitedSprites.isSelected());

        for (var channel : APUChannel.values()) {
            nes.getAPU().setChannelMuted(channel, !debugMenuChannels.get(channel).isSelected());
        }

        // A movie carries its own, for the reason it carries the codes and a sharper one: this is
        // how much of its work the game gets through in a frame, so a replay at another setting is
        // a replay of a different game. Off the config rather than off the last machine because the
        // region can have changed under it, and a percentage is only scanlines once there is a
        // machine to ask.
        overclock = pendingMovie != null
                ? pendingMovie.overclock()
                : config.overclock().resolve(nes.getRegion());

        nes.getPPU().setOverclock(overclock);

        // The watchpoints have to be wired to this machine's MMU rather than the last one's. Same
        // window as the two lines above: the runner does not exist yet, so this thread owns it.
        debugger.attach(nes);

        // A movie carries the codes it was recorded with, and they win: the cartridge a code was
        // played against is byte for byte the cartridge it was not, so this is the only thing that
        // can put the cheat back -- and a replay against a different set of codes is a replay of
        // nothing.
        if (pendingMovie != null) {
            genieCodes = pendingMovie.genie();
        }

        // And so does the cartridge slot. Replayed from the window's own list rather than left to
        // whatever the device happened to be holding, so that there is one answer to what the codes
        // are: attach first, so a code put in here reaches this machine's MMU and not the last one's.
        genie.attach(nes);
        genie.clear();
        genieCodes.forEach(genie::add);

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

        // Seconds is what the setting says and frames is what a ring holds, and only here is it
        // known which machine the cartridge turned out to run on -- 1803 frames for thirty seconds
        // of NTSC against 1500 of PAL. Zero seconds builds no ring at all.
        //
        // The rate goes with it: the new runner's frame counter starts again from zero, so an
        // interval measured across the changeover would be the outgoing machine's rate divided by
        // however long building this one took.
        frameRate.reset();

        runner = new EmulatorRunner(nes, screen, debugger,
                Rewind.framesFor(nes.getRegion(), config.rewindSeconds()),
                config.audioLatencyMs());
        runner.setStopListener(this::stopped);

        // Per machine, like the controller above and for the same reason: each one keeps its own
        // history, and the key must not still be rewinding a game that has been switched off.
        keyboardInput.setRewind(runner::setRewinding);

        // And so is where a recorded frame's buttons come from, for the same reason again.
        runner.setFrameInputSource(keyboardInput::heldMask);

        var playing = runner;
        runner.setPlaybackEndedListener(() -> playbackEnded(playing));

        if (pendingMovie != null) {
            // Posted before the thread exists, so the anchor is in place before a single frame runs.
            runner.startPlayback(pendingMovie);
            pendingMovie = null;

            moviePlaying = true;

            // The keyboard is kept off the game entirely while a movie plays -- a bumped key would
            // stop it being the recorded session -- except for rewind, which is the gesture that
            // takes it back.
            keyboardInput.setPlaybackMuted(true);
            keyboardInput.setLatching(true);
        }

        if (debuggerFrame != null) {
            debuggerFrame.setMachine(nes, runner);
        }

        // Posted before the thread exists, so they are the first things that run on it: a machine
        // started with the sound off, or quiet, must not get a frame of it at full volume in first.
        runner.setMuted(machineMenuMute.isSelected());
        runner.setVolume(config.volume());
        runner.start();

        machineMenu.setEnabled(true);
        debugMenu.setEnabled(true);
        fileMenuScreenshot.setEnabled(true);
        fileMenuCopyScreenshot.setEnabled(true);
        updateMovieItems();
        describeMachine();
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

    /**
     * Brings both descriptions of the machine into line: the one in the title bar, which is for a
     * window list and a dock, and the one along the bottom, which is for whoever is playing.
     * <p>
     * One call rather than two at each of the places something changes, because the two say
     * overlapping things and a caller that remembered one of them would leave the window
     * disagreeing with itself.
     */
    private void describeMachine() {
        updateTitle();
        updateStatusBar();
    }

    private void updateTitle() {
        if (cart == null) {
            setTitle("MyNES");
            return;
        }

        var state = machineState();

        setTitle("MyNES - " + cart.filename() + patched() + machineKind()
                + (state.isEmpty() ? "" : " (" + state.toLowerCase(Locale.ROOT) + ")"));
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
     * What the machine is doing, when it is doing anything other than simply running. Pause wins
     * over fast forward: a machine that is not running is not running fast.
     * <p>
     * One sentence for two places. The status bar shows it as it is and the title bar puts it in
     * brackets in lower case, which is what keeps the two from drifting into describing the same
     * machine differently.
     */
    private String machineState() {
        if (runner == null) {
            return "";
        }

        if (runner.isPaused()) {
            return "Paused";
        }

        // Above fast forward, because what the machine is doing to a file is a bigger surprise than
        // how fast it is going -- and a recording somebody has forgotten about is the one state
        // worth being reminded of on every glance at the window.
        if (moviePlaying) {
            return "Playback";
        }

        if (movieRecording) {
            return "Recording";
        }

        // Below the movie and above the speed, for the reason the movie is above the speed: a file
        // growing at a couple of megabytes a second is a bigger surprise than how fast the game is
        // going, and a trace somebody started an hour ago is the state most worth being reminded of.
        if (tracer != null) {
            return "Tracing";
        }

        if (runner.getSpeed() != EmulationSpeed.NORMAL) {
            return "Fast forward";
        }

        return "";
    }

    /**
     * Whether there is no machine to look at, having said so.
     * <p>
     * The Debug menu is greyed out until a cartridge is loaded, so this only fires if something has
     * gone wrong -- which is exactly when a dialog is worth more than a silent no.
     */
    private boolean noCartridge() {
        if (cart != null) {
            return false;
        }

        logger.log(Level.ERROR, "cartridge is not loaded");
        JOptionPane.showMessageDialog(
                this,
                "Cartridge is not loaded",
                "Error",
                JOptionPane.ERROR_MESSAGE);

        return true;
    }

    /**
     * Starts writing down every instruction the CPU runs.
     * <p>
     * <b>It is expensive in disk rather than in time.</b> A frame is around thirty thousand
     * instructions at about ninety bytes a line, so this writes a couple of megabytes a second of
     * play and a minute of it is well over a gigabyte. There is no limit from the window, unlike the
     * interactive session's {@code trace PATH LINES}: somebody here is watching the file grow and
     * can stop it, where a script is not.
     * <p>
     * The listener goes on from the emulation thread, which is the thread that walks the list.
     */
    private void startTrace() {
        if (runner == null || tracer != null) {
            return;
        }

        traceChooser.setSelectedFile(defaultTracePath().toFile());

        if (traceChooser.showSaveDialog(this) != SystemFileChooser.APPROVE_OPTION) {
            return;
        }

        var path = traceChooser.getSelectedFile().toPath();

        try {
            tracer = Tracer.to(path, nes.getPPU(), 0);
        } catch (IOException ex) {
            logger.log(Level.ERROR, "failed to open the trace", ex);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not write " + path.getFileName() + ": " + ex.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE);

            return;
        }

        tracePath = path;

        var writing = tracer;
        runner.post(() -> nes.getCPU().addEventListener(writing));

        logger.log(Level.INFO, "tracing to " + path.getFileName());

        debugMenuTrace.setEnabled(false);
        debugMenuStopTrace.setEnabled(true);
        describeMachine();
    }

    /**
     * Stops it and closes the file, from whichever thread owns the machine.
     * <p>
     * Two paths because there are two callers and they are in different worlds. The menu item stops
     * a running machine, so the listener has to come off on the emulation thread and the file has to
     * be closed after it does -- closing from here could land in the middle of a line. A new
     * cartridge stops one with the runner already halted, and then the machine is this thread's, the
     * same way saving the battery on the way out is.
     */
    private void stopTrace() {
        if (tracer == null) {
            return;
        }

        var stopping = tracer;
        var path = tracePath;

        tracer = null;
        tracePath = null;

        if (runner != null && runner.isRunning()) {
            runner.post(() -> finishTrace(stopping, path));
        } else {
            finishTrace(stopping, path);
        }

        debugMenuTrace.setEnabled(true);
        debugMenuStopTrace.setEnabled(false);
        describeMachine();
    }

    /**
     * Takes the tracer off the CPU and closes its file. Runs on whichever thread owns the machine.
     */
    private void finishTrace(final Tracer stopping, final Path path) {
        nes.getCPU().removeEventListener(stopping);

        try {
            stopping.close();
        } catch (IOException ex) {
            logger.log(Level.ERROR, "failed to close the trace", ex);
        }

        var failure = stopping.failure();

        logger.log(Level.INFO, "traced " + stopping.lines() + " instructions to " + path);

        if (failure != null) {
            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                    this,
                    "The trace stopped early: " + failure.getMessage(),
                    "Error",
                    JOptionPane.ERROR_MESSAGE));
        }
    }

    private Path defaultTracePath() {
        var name = gamePath().getFileName().toString();
        var dot = name.lastIndexOf('.');

        return gamePath().resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".log");
    }

    private void destroyCHRViewerFrame() {
        if (chrViewerFrame != null) {
            logger.log(Level.DEBUG, "closing chrViewerFrame");
            chrViewerFrame.dispose();
            chrViewerFrame = null;
        }
    }

    private void destroyPPUViewerFrames() {
        if (nametableViewerFrame != null) {
            logger.log(Level.DEBUG, "closing nametableViewerFrame");
            nametableViewerFrame.dispose();
            nametableViewerFrame = null;
        }

        if (oamViewerFrame != null) {
            logger.log(Level.DEBUG, "closing oamViewerFrame");
            oamViewerFrame.dispose();
            oamViewerFrame = null;
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
        describeMachine();

        if (debuggerFrame != null) {
            debuggerFrame.stopped(stop);
        }
    }
}
