package com.github.dimiro1.mynes.shots;

import com.formdev.flatlaf.FlatLightLaf;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Condition;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.headless.InputSchedule;
import com.github.dimiro1.mynes.headless.Session;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.state.SaveState;
import com.github.dimiro1.mynes.ui.AudioOutput;
import com.github.dimiro1.mynes.ui.EmulatorRunner;
import com.github.dimiro1.mynes.ui.GameUIFrame;
import com.github.dimiro1.mynes.ui.ScreenComponent;
import com.github.dimiro1.mynes.ui.debugger.DebuggerFrame;
import com.github.dimiro1.mynes.ui.ppuviewer.NametableViewerFrame;
import com.github.dimiro1.mynes.ui.ppuviewer.OAMViewerFrame;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.AbstractButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.event.MenuEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.Frame;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Takes the README's pictures.
 * <p>
 * Two kinds, and the difference is the whole reason this is a module rather than a script. A picture
 * of the <em>game</em> is a framebuffer, and {@link Session} writes one with no window anywhere --
 * the two filter pictures are that. A picture of a <em>window</em> has the window's own chrome in
 * it, which only the screen has, so those are the real Swing windows photographed with a
 * {@link Robot}. Everything is driven the way a player drives it -- the game window through its
 * menus, a viewer through its constructor -- and nothing reaches into a private field, so a
 * refactor that breaks this breaks it at compile time.
 * <p>
 * The machine behind every picture is put at its frame by the headless {@link Session}, which is
 * deterministic, so the same command takes the same pictures. The game window is the one thing that
 * runs on the clock: it is handed a save state taken at the frame wanted and photographed a few
 * frames after loading it.
 * <p>
 * The window's settings go to a temporary home rather than to {@code ~/.mynes}, because opening a
 * game from the menu writes the recent list and this must not rewrite the user's own.
 *
 * <pre>
 * mvn -q compile exec:exec@shots -Dshots.args="--roms DIR [--out shots] [--only name,name]"
 * </pre>
 */
public final class Shots {
    private static final Logger logger = System.getLogger("SHOTS");

    /**
     * Where the pictures go: the directory the README reads.
     */
    private static final Path DEFAULT_OUT = Path.of("shots");

    private static final String SMB = "Super Mario Bros. (World).nes";
    private static final String SMB3 = "Super Mario Bros. 3 (USA) (Rev 1).nes";
    private static final String TETRIS = "Tetris (Europe).nes";

    /**
     * Past the title and into 1-1, walking right with a jump every ninety frames, which clears the
     * first goomba.
     */
    private static final String SMB_INPUT = "60/40x3:start,220-700:right,300/90:a";

    /**
     * Five hundred frames in, the screen has scrolled past the first pipe -- so the scroll window
     * straddles two nametables, which is the picture the viewer is for.
     */
    private static final int SMB_FRAME = 500;

    /**
     * The other two sit on their title screens, which is where a game left alone stays. Super
     * Mario Bros. 3 takes six hundred frames to raise the curtain and fly Mario under the logo.
     */
    private static final int SMB3_FRAME = 600;
    private static final int TETRIS_FRAME = 500;

    /**
     * Where in the frame the nametable viewer's machine is stopped. Super Mario Bros. scrolls only
     * the part of the screen under its status bar, and does that by rewriting the scroll after a
     * sprite 0 hit on scanline 31 -- so at a frame boundary the register holds the status bar's
     * zero, and the picture wanted is the one a game is showing in the middle of a frame.
     */
    private static final int MID_FRAME_SCANLINE = 120;

    /**
     * How long a press is held, the headless mode's own default.
     */
    private static final int PRESS_FRAMES = 2;

    /**
     * Super Mario Bros.' NMI handler, and its OperMode byte, which is 1 while a level is being
     * played -- so the breakpoint stops on the first frame of the level rather than on the first
     * frame of the run.
     */
    private static final int SMB_NMI = 0x8082;
    private static final String SMB_PLAYING = "[$0770] == 1";

    private static final List<String> GENIE_CODES = List.of("SXIOPO", "AVPAZLGV", "GOSSIP");

    private static final int FRAMEBUFFER_SCALE = 2;

    /**
     * Where a window is put.
     */
    private static final int WINDOW_X = 80;
    private static final int WINDOW_Y = 80;

    /**
     * How long a window is given to be drawn before it is photographed. The look and feel animates,
     * and macOS fades a window in.
     */
    private static final int SETTLE_MILLIS = 800;

    /**
     * How long a menu action is given to have happened.
     */
    private static final int ACTION_MILLIS = 400;

    private static final int AWAIT_MILLIS = 5_000;

    private final Path roms;
    private final Path out;
    private final Path copies;
    private final Set<String> only;
    private final Robot robot;

    private @Nullable GameUIFrame game;

    /**
     * Which cartridge the game window has open, so that a dialog photographed over it can make
     * sure it is the one wanted without opening it twice.
     */
    private @Nullable String loaded;

    private Shots(final Path roms, final Path out, final Path home, final Set<String> only)
            throws Exception {
        this.roms = roms;
        this.out = out;
        this.copies = Files.createDirectories(home.resolve("roms"));
        this.only = only;
        this.robot = new Robot();
    }

    static void main(final String[] args) throws Exception {
        Path roms = null;
        var out = DEFAULT_OUT;
        Set<String> only = Set.of();

        for (var i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--roms" -> roms = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                case "--only" -> only = Set.of(args[++i].split(","));
                default -> {
                    System.err.println("usage: --roms DIR [--out DIR] [--only name,name]");
                    System.exit(2);
                }
            }
        }

        if (roms == null) {
            System.err.println("--roms DIR is where the cartridges are, and there is no default.");
            System.exit(2);
        }

        var home = Files.createTempDirectory("mynes-shots");

        // Before any class that reads it is loaded: the window's Config resolves
        // ~/.mynes/config.properties in a static initialiser.
        System.setProperty("user.home", home.toString());

        FlatLightLaf.setup();

        var shots = new Shots(roms, out, home, only);

        try {
            shots.takeAll();
        } finally {
            onEdt(() -> {
                for (var window : Window.getWindows()) {
                    window.dispose();
                }
            });
        }

        // The toolkit keeps the process alive otherwise.
        System.exit(0);
    }

    private interface Picture {
        void take() throws Exception;
    }

    private void takeAll() throws Exception {
        var pictures = new LinkedHashMap<String, Picture>();

        pictures.put("filter-ntsc", () -> framebuffer(SMB3, "", SMB3_FRAME, VideoFilter.NTSC, false, "filter-ntsc"));
        pictures.put("filter-crt", () -> framebuffer(SMB, SMB_INPUT, SMB_FRAME, VideoFilter.CRT, true, "filter-crt"));
        pictures.put("nametable-viewer", this::nametableViewer);
        pictures.put("oam-viewer", this::oamViewer);
        pictures.put("debugger", this::debugger);

        // The game window last, and Super Mario Bros. 3 last of those: the dialogs are photographed
        // over it, and it is the one whose title screen the README opens on.
        pictures.put("game-smb", () -> gameWindow(SMB, SMB_INPUT, SMB_FRAME, "game-smb"));
        pictures.put("game-tetris", () -> gameWindow(TETRIS, "", TETRIS_FRAME, "game-tetris"));
        pictures.put("game-smb3", () -> gameWindow(SMB3, "", SMB3_FRAME, "game-smb3"));
        pictures.put("palette-dialog", this::paletteDialog);
        pictures.put("chr-viewer", this::chrViewer);
        pictures.put("controller-dialog", this::controllerDialog);
        pictures.put("genie-dialog", this::genieDialog);

        for (var unknown : only) {
            if (!pictures.containsKey(unknown)) {
                throw new IllegalArgumentException(
                        "no picture called " + unknown + "; there are " + pictures.keySet());
            }
        }

        for (var entry : pictures.entrySet()) {
            if (only.isEmpty() || only.contains(entry.getKey())) {
                logger.log(Level.INFO, "taking " + entry.getKey());
                entry.getValue().take();
            }
        }
    }

    // ================================================================================== machines

    /**
     * A machine and the schedule that has been driving it, so that a picture can keep playing past
     * the frame it was put at.
     */
    private record Played(Session session, InputSchedule schedule, long frame) {
        Played advance(final long frames) throws IOException {
            var at = frame;

            for (var i = 0; i < frames; i++) {
                at++;
                session.setButtons(schedule.buttonsAt(at));
                session.advanceFrame();
            }

            return new Played(session, schedule, at);
        }
    }

    private Played play(final String rom, final String input, final long frames) throws IOException {
        var cart = Cart.load(Files.readAllBytes(roms.resolve(rom)), rom);
        var nes = new NES(cart);
        var session = new Session(
                nes,
                Palettes.defaultPalette(nes.getRegion()).colours(),
                VideoFilter.NONE,
                FilterStrength.defaultStrength(),
                false,
                null);

        var schedule = InputSchedule.parse(List.of(input), PRESS_FRAMES);

        return new Played(session, schedule, 0).advance(frames);
    }

    // ============================================================================== framebuffers

    private void framebuffer(
            final String rom,
            final String input,
            final long frames,
            final VideoFilter filter,
            final boolean warp,
            final String name) throws IOException {

        var session = play(rom, input, frames).session();

        session.setFilter(filter);
        session.setWarp(warp);
        session.screenshot(out.resolve(name + ".png"), true, FRAMEBUFFER_SCALE);
    }

    // =================================================================================== viewers

    private void nametableViewer() throws Exception {
        var session = play(SMB, SMB_INPUT, SMB_FRAME).session();
        var nes = session.nes();

        while (nes.getPPU().getScanline() < MID_FRAME_SCANLINE
                || nes.getPPU().getScanline() > 2 * MID_FRAME_SCANLINE) {
            session.stepInstructions(1);
        }
        var palette = Palettes.defaultPalette(nes.getRegion());
        var frame = new JFrame[1];

        onEdt(() -> {
            frame[0] = new NametableViewerFrame(null, nes, palette);
            show(frame[0]);
        });

        capture(frame[0], "nametable-viewer");
        onEdt(frame[0]::dispose);
    }

    private void oamViewer() throws Exception {
        var nes = play(SMB3, "", SMB3_FRAME).session().nes();
        var palette = Palettes.defaultPalette(nes.getRegion());
        var frame = new JFrame[1];

        onEdt(() -> {
            frame[0] = new OAMViewerFrame(null, nes.getPPU(), palette);
            show(frame[0]);
        });

        capture(frame[0], "oam-viewer");
        onEdt(frame[0]::dispose);
    }

    /**
     * A real stop rather than a posed one: the breakpoint is set on a running machine and the
     * machine is played until it fires, which is what fills the listing, the registers and the
     * points panel with the truth.
     */
    private void debugger() throws Exception {
        var played = play(SMB, SMB_INPUT, SMB_FRAME);
        var session = played.session();
        var debugger = session.debugger();

        debugger.addBreakpoint(SMB_NMI, Condition.parse(SMB_PLAYING));

        Debugger.Stop stop = null;
        var frame = played.frame();

        while (stop == null) {
            frame++;

            if (frame > played.frame() + 120) {
                throw new IllegalStateException("the breakpoint did not fire within two seconds");
            }

            session.setButtons(played.schedule().buttonsAt(frame));
            stop = session.advanceFrame().stop();
        }

        var nes = session.nes();
        var stopped = stop;
        var window = new DebuggerFrame[1];

        onEdt(() -> {
            // Never started: the window only needs something to hand its buttons, and the machine
            // is already exactly where the picture wants it. So no ring and no sound card either --
            // the latency is a number for a line that is never opened.
            var runner = new EmulatorRunner(
                    nes, new ScreenComponent(), debugger, 0, AudioOutput.DEFAULT_LATENCY_MS);

            window[0] = new DebuggerFrame(null, nes, runner, debugger);
            window[0].stopped(stopped);
            show(window[0]);
        });

        capture(window[0], "debugger");

        onEdt(() -> {
            // Told the machine is running before it goes, or it would try to resume through a
            // runner that was never started.
            window[0].running();
            window[0].dispose();
        });
    }

    // ============================================================================== game window

    /**
     * The window, opened the first time a picture wants it, over a settings file that lists the
     * three cartridges under File &gt; Open Recent and keeps the sound card closed.
     */
    private GameUIFrame game() throws Exception {
        if (game == null) {
            var properties = new Properties();
            properties.setProperty("audio.muted", "true");
            properties.setProperty("ui.status-bar", "false");

            var number = 1;

            for (var rom : List.of(SMB, SMB3, TETRIS)) {
                var copy = copies.resolve(rom);
                Files.copy(roms.resolve(rom), copy);
                properties.setProperty("recent." + number++, copy.toString());
            }

            var home = Path.of(System.getProperty("user.home"));
            var config = Files.createDirectories(home.resolve(".mynes")).resolve("config.properties");

            try (var writer = Files.newBufferedWriter(config)) {
                properties.store(writer, null);
            }

            onEdt(() -> {
                game = new GameUIFrame();
                show(game);
            });
        }

        return game;
    }

    private void gameWindow(
            final String rom, final String input, final long frames, final String name)
            throws Exception {
        load(rom, input, frames);
        capture(game(), name);
    }

    /**
     * Opens the cartridge from the recent list and loads a state taken at the frame wanted. Not
     * paused: the title would say so, and a title screen left to run for the moment it takes to
     * photograph is still the title screen.
     */
    private void load(final String rom, final String input, final long frames) throws Exception {
        var session = play(rom, input, frames).session();
        var game = game();

        // Beside the copy rather than beside the cartridge: a slot file is the one thing the window
        // writes next to a ROM, and the user's own slot one is not this harness's to overwrite.
        SaveState.write(session.nes(), SaveState.slotPath(copies.resolve(rom), 1));

        onEdt(() -> {
            // Off a menu that is being opened, since the recent list is rebuilt as it opens.
            var file = menu(game, "File");
            for (var listener : file.getMenuListeners()) {
                listener.menuSelected(new MenuEvent(file));
            }

            var stem = rom.substring(0, rom.length() - ".nes".length());
            item(item(file, "Open Recent"), stem).doClick();
        });

        Thread.sleep(ACTION_MILLIS);

        onEdt(() -> item(menu(game, "Machine"), "Quick Load").doClick());

        loaded = rom;
    }

    /**
     * The game the dialogs are photographed over, opened if the window is not already on it.
     */
    private GameUIFrame overSMB3() throws Exception {
        if (!SMB3.equals(loaded)) {
            load(SMB3, "", SMB3_FRAME);
        }

        return game();
    }

    // =================================================================================== dialogs

    /**
     * The window at three times rather than two, because at two the dialog is wider than the game
     * it is being compared against.
     */
    private void paletteDialog() throws Exception {
        var game = overSMB3();

        onEdt(() -> item(item(menu(game, "Settings"), "Screen Size"), "3").doClick());
        Thread.sleep(ACTION_MILLIS);

        var dialog = open(game, "Settings", "Palette...", "Palette");

        // The game window's own bounds, which the dialog sits inside at this size: the picture is
        // the dialog over the game it is changing.
        capture(game, "palette-dialog");
        onEdt(dialog::dispose);
    }

    private void chrViewer() throws Exception {
        var game = overSMB3();
        var viewer = open(game, "Debug", "CHR Viewer", "CHR Viewer");

        capture(viewer, "chr-viewer");
        onEdt(viewer::dispose);
    }

    private void controllerDialog() throws Exception {
        var game = overSMB3();
        var dialog = open(game, "Settings", "Controller...", "Controller");

        capture(dialog, "controller-dialog");
        onEdt(dialog::dispose);
    }

    private void genieDialog() throws Exception {
        var game = overSMB3();
        var dialog = open(game, "Hacks", "Game Genie...", "Game Genie");

        onEdt(() -> {
            var root = ((JDialog) dialog).getContentPane();
            var field = find(root, JTextField.class, null);
            var add = find(root, AbstractButton.class, "Add");

            for (var code : GENIE_CODES) {
                field.setText(code);
                add.doClick();
            }
        });

        capture(dialog, "genie-dialog");
        onEdt(dialog::dispose);
    }

    /**
     * Clicks a menu item and waits for the window it opens.
     * <p>
     * Posted rather than waited for, because a modal dialog's {@code setVisible} does not return
     * until the dialog closes -- the event thread keeps pumping underneath, which is what lets the
     * photograph be taken and the dialog be disposed from here.
     */
    private Window open(
            final GameUIFrame game, final String menu, final String item, final String title)
            throws Exception {

        SwingUtilities.invokeLater(() -> item(Shots.menu(game, menu), item).doClick());

        var deadline = System.currentTimeMillis() + AWAIT_MILLIS;

        while (System.currentTimeMillis() < deadline) {
            for (var window : Window.getWindows()) {
                if (window.isShowing() && title.equals(titleOf(window))) {
                    // Above the game window, which is itself on top of everything: a viewer that
                    // opened underneath it would be photographed as a rectangle of the game.
                    onEdt(() -> {
                        window.setAlwaysOnTop(true);
                        window.toFront();
                    });

                    return window;
                }
            }

            Thread.sleep(50);
        }

        throw new IllegalStateException("no window called " + title + " appeared");
    }

    private static @Nullable String titleOf(final Window window) {
        return switch (window) {
            case Frame frame -> frame.getTitle();
            case Dialog dialog -> dialog.getTitle();
            default -> null;
        };
    }

    // ==================================================================================== menus

    private static JMenu menu(final GameUIFrame game, final String text) {
        var bar = game.getJMenuBar();

        for (var i = 0; i < bar.getMenuCount(); i++) {
            var menu = bar.getMenu(i);

            if (menu != null && text.equals(menu.getText())) {
                return menu;
            }
        }

        throw new IllegalStateException("no menu called " + text);
    }

    /**
     * The item whose label is, or begins with, {@code text}. Begins with, because some labels carry
     * a state after them -- "Quick Load (Slot 1)" -- and a submenu is an item too.
     */
    private static <T extends JMenuItem> T item(final JMenu menu, final String text) {
        for (var i = 0; i < menu.getItemCount(); i++) {
            var item = menu.getItem(i);

            if (item != null && item.getText() != null && item.getText().startsWith(text)) {
                @SuppressWarnings("unchecked")
                var found = (T) item;
                return found;
            }
        }

        throw new IllegalStateException("no item called " + text + " under " + menu.getText());
    }

    private static <T extends Component> @Nullable T find(
            final Container root, final Class<T> type, final @Nullable String text) {

        for (var child : root.getComponents()) {
            if (type.isInstance(child)
                    && (text == null || child instanceof AbstractButton button
                    && text.equals(button.getText()))) {
                return type.cast(child);
            }

            if (child instanceof Container inner) {
                var found = find(inner, type, text);

                if (found != null) {
                    return found;
                }
            }
        }

        return null;
    }

    // ================================================================================== pictures

    private static void show(final Window window) {
        window.setLocation(WINDOW_X, WINDOW_Y);
        window.setAlwaysOnTop(true);
        window.setVisible(true);
        window.toFront();
    }

    private void capture(final Window window, final String name) throws Exception {
        var bounds = new Rectangle[1];

        onEdt(() -> bounds[0] = window.getBounds());

        capture(bounds[0], name);
    }

    /**
     * Photographs a piece of the screen.
     * <p>
     * Through macOS's own {@code screencapture} where there is one, and through the {@link Robot}
     * everywhere else. The difference is the pointer: the Robot's capture has it drawn in wherever
     * it happens to be, and moving it out of the way takes a permission a process Maven started
     * does not have, while {@code screencapture} leaves it out unless asked to put it in.
     * <p>
     * Either way the picture kept is the one in points rather than in device pixels: on a high
     * density display the screen holds two or three of those per point, and a two times window in
     * points is an exact multiple of the NES's picture, so nothing goes soft. The Robot offers that
     * size as one of its variants; {@code screencapture} gives the dense one, and it is shrunk here
     * by averaging each block of pixels, which is exact for a whole-number factor.
     */
    private void capture(final Rectangle bounds, final String name) throws Exception {
        // A sleep rather than Robot.waitForIdle, which waits for an event thread that a running
        // game never leaves idle.
        Thread.sleep(SETTLE_MILLIS);

        var image = screencapture(bounds);

        if (image == null) {
            image = robotCapture(bounds);
        }

        Files.createDirectories(out);

        var path = out.resolve(name + ".png");
        ImageIO.write(image, "png", path.toFile());

        logger.log(Level.INFO, "wrote " + path + ", " + image.getWidth() + "x" + image.getHeight());
    }

    private static @Nullable BufferedImage screencapture(final Rectangle bounds) throws Exception {
        var file = Files.createTempFile("mynes-shot", ".png");

        try {
            var region = bounds.x + "," + bounds.y + "," + bounds.width + "," + bounds.height;
            var process = new ProcessBuilder(
                    "screencapture", "-x", "-t", "png", "-R", region, file.toString())
                    .redirectErrorStream(true)
                    .start();

            if (process.waitFor() != 0) {
                return null;
            }

            var dense = ImageIO.read(file.toFile());

            if (dense == null || dense.getWidth() % bounds.width != 0) {
                return null;
            }

            return shrink(dense, dense.getWidth() / bounds.width);
        } catch (IOException e) {
            // Not macOS, or no such command: the Robot's turn.
            return null;
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static BufferedImage shrink(final BufferedImage dense, final int factor) {
        if (factor == 1) {
            return dense;
        }

        var width = dense.getWidth() / factor;
        var height = dense.getHeight() / factor;
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var block = factor * factor;

        for (var y = 0; y < height; y++) {
            for (var x = 0; x < width; x++) {
                var r = 0;
                var g = 0;
                var b = 0;

                for (var dy = 0; dy < factor; dy++) {
                    for (var dx = 0; dx < factor; dx++) {
                        var rgb = dense.getRGB(x * factor + dx, y * factor + dy);
                        r += (rgb >> 16) & 0xFF;
                        g += (rgb >> 8) & 0xFF;
                        b += rgb & 0xFF;
                    }
                }

                image.setRGB(x, y, (r / block) << 16 | (g / block) << 8 | b / block);
            }
        }

        return image;
    }

    private BufferedImage robotCapture(final Rectangle bounds) {
        var capture = robot.createMultiResolutionScreenCapture(bounds);
        Image chosen = null;

        for (var variant : capture.getResolutionVariants()) {
            if (chosen == null || variant.getWidth(null) < chosen.getWidth(null)) {
                chosen = variant;
            }
        }

        var image = new BufferedImage(
                chosen.getWidth(null), chosen.getHeight(null), BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.drawImage(chosen, 0, 0, null);
        graphics.dispose();

        return image;
    }

    private interface EdtWork {
        void run() throws Exception;
    }

    private static void onEdt(final EdtWork work) throws Exception {
        var failure = new ArrayList<Exception>(1);

        SwingUtilities.invokeAndWait(() -> {
            try {
                work.run();
            } catch (Exception e) {
                failure.add(e);
            }
        });

        if (!failure.isEmpty()) {
            throw failure.getFirst();
        }
    }
}
