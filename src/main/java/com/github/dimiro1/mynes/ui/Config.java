package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.ui.input.KeyBindings;
import com.github.dimiro1.mynes.ui.palette.NESPalette;
import com.github.dimiro1.mynes.ui.palette.Palettes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Everything the emulator remembers between runs, and the only thing that writes the file it
 * remembers it in.
 * <p>
 * One file, one owner. {@code save} truncates {@code ~/.mynes/config.properties} and writes the
 * whole thing back, so a second writer would quietly drop whatever the first one had put there --
 * picking a palette would cost the user their key bindings. Everything settable goes through here
 * instead, and the settings dialogs only say what changed.
 * <p>
 * Nothing in the file is trusted. It is meant to be edited by hand as much as through the menu,
 * and an entry that is missing or that this version does not understand falls back to its default
 * and says so in the log, one entry at a time, so a botched edit costs a setting rather than a
 * startup.
 */
public final class Config {
    private static final Logger logger = LoggerFactory.getLogger("UI");

    /**
     * Where the settings live.
     */
    public static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".mynes", "config.properties");

    private static final String PALETTE_KEY = "video.palette";
    private static final String SCALE_KEY = "video.scale";
    private static final String FAST_FORWARD_KEY = "emulation.fast-forward";
    private static final String MUTED_KEY = "audio.muted";

    private static final String HEADER = """
            # MyNES settings.
            #
            # The menus rewrite this file, dropping anything added by hand. An entry that is
            # missing or that this version does not understand falls back to its default and
            # says so in the log.

            """;

    private static final String PALETTE_HEADER = """
            # The palette the picture is drawn with. Settings > Palette... has the list; the
            # value is the id shown there in lower case with dashes, such as nes-classic.
            """;

    private static final String SCALE_HEADER = """
            # How many screen pixels wide a picture pixel is drawn, and so how big the window
            # opens: 1, 2, 3 or 4. Settings > Screen Size is the same setting, and the window
            # can still be dragged to any size at all from there.
            """;

    private static final String EMULATION_HEADER = """
            # How fast Machine > Fast Forward runs the machine: 2x, 4x, 8x, or unlimited, which
            # is as fast as this computer manages. Anything the computer cannot keep up with
            # runs at whatever it does manage.
            """;

    private static final String AUDIO_HEADER = """
            # Whether Machine > Mute is on. true or false; anything else is taken as false, which
            # is sound switched on.
            """;

    private KeyBindings keyBindings;
    private NESPalette palette;
    private ScreenScale screenScale;
    private EmulationSpeed fastForwardSpeed;
    private boolean muted;

    private Config(
            final KeyBindings keyBindings,
            final NESPalette palette,
            final ScreenScale screenScale,
            final EmulationSpeed fastForwardSpeed,
            final boolean muted) {
        this.keyBindings = keyBindings;
        this.palette = palette;
        this.screenScale = screenScale;
        this.fastForwardSpeed = fastForwardSpeed;
        this.muted = muted;
    }

    /**
     * Reads the settings from {@code path}, filling in the default for anything the file does not
     * answer for. Never throws: no config file is worth refusing to start over.
     *
     * @param path the file to read, usually {@link #DEFAULT_PATH}.
     */
    public static Config load(final Path path) {
        var properties = new Properties();

        if (!Files.isRegularFile(path)) {
            logger.info("no settings file at {}, using the defaults", path);
        } else {
            try (var in = Files.newInputStream(path)) {
                properties.load(in);
                logger.info("loaded settings from {}", path);
            } catch (IOException | IllegalArgumentException e) {
                // IllegalArgumentException is a malformed unicode escape somewhere in the file.
                logger.warn("could not read {}, using the defaults", path, e);
            }
        }

        // An empty Properties answers for nothing, so a missing or unreadable file lands on the
        // same defaults every entry would have fallen back to one at a time.
        return new Config(
                KeyBindings.from(properties),
                paletteFrom(properties),
                screenScaleFrom(properties),
                fastForwardSpeedFrom(properties),
                mutedFrom(properties));
    }

    /**
     * Whether the sound is off. Unlike the other entries there is nothing to fall back to and
     * nothing to warn about: anything that is not {@code true} is somebody who wants to hear the
     * game, which is also what a missing entry means.
     */
    private static boolean mutedFrom(final Properties properties) {
        return Boolean.parseBoolean(properties.getProperty(MUTED_KEY, "").trim());
    }

    private static NESPalette paletteFrom(final Properties properties) {
        var id = properties.getProperty(PALETTE_KEY);

        if (id == null) {
            return Palettes.defaultPalette();
        }

        return Palettes.byId(id.trim());
    }

    private static ScreenScale screenScaleFrom(final Properties properties) {
        var id = properties.getProperty(SCALE_KEY);

        if (id == null) {
            return ScreenScale.defaultScale();
        }

        return ScreenScale.byId(id.trim());
    }

    private static EmulationSpeed fastForwardSpeedFrom(final Properties properties) {
        var id = properties.getProperty(FAST_FORWARD_KEY);

        if (id == null) {
            return EmulationSpeed.defaultFastForward();
        }

        return EmulationSpeed.fastForwardById(id.trim());
    }

    /**
     * Writes the settings to {@code path}, creating the directory if it is not there yet.
     * <p>
     * Written by hand rather than through {@link Properties#store} so that the entries come out in
     * a fixed, readable order with their explanations attached, instead of whatever order the hash
     * table holds them in.
     *
     * @param path the file to write, usually {@link #DEFAULT_PATH}.
     */
    public void save(final Path path) throws IOException {
        var text = new StringBuilder(HEADER);

        text.append(PALETTE_HEADER)
                .append(PALETTE_KEY)
                .append('=')
                .append(palette.id())
                .append("\n\n");

        text.append(SCALE_HEADER)
                .append(SCALE_KEY)
                .append('=')
                .append(screenScale.id())
                .append("\n\n");

        text.append(EMULATION_HEADER)
                .append(FAST_FORWARD_KEY)
                .append('=')
                .append(fastForwardSpeed.id())
                .append("\n\n");

        text.append(AUDIO_HEADER)
                .append(MUTED_KEY)
                .append('=')
                .append(muted)
                .append("\n\n");

        keyBindings.appendTo(text);

        var parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // ISO-8859-1 is what Properties.load reads a stream as. Everything written here is ASCII
        // either way; this only keeps the two ends of the round trip agreeing on paper.
        Files.writeString(path, text, StandardCharsets.ISO_8859_1);

        logger.info("saved settings to {}", path);
    }

    public KeyBindings keyBindings() {
        return keyBindings;
    }

    public void setKeyBindings(final KeyBindings keyBindings) {
        this.keyBindings = keyBindings;
    }

    public NESPalette palette() {
        return palette;
    }

    public void setPalette(final NESPalette palette) {
        this.palette = palette;
    }

    /**
     * How big the picture is drawn, and so how big the window opens. Remembered, since a window that
     * came back the wrong size on every run would be the first thing to fix every run.
     */
    public ScreenScale screenScale() {
        return screenScale;
    }

    public void setScreenScale(final ScreenScale screenScale) {
        this.screenScale = screenScale;
    }

    /**
     * How fast Fast Forward runs the machine. Whether it is switched on is not remembered: a
     * machine that started fast forwarding on its own, because of something done in a session
     * weeks ago, would just look broken.
     */
    public EmulationSpeed fastForwardSpeed() {
        return fastForwardSpeed;
    }

    public void setFastForwardSpeed(final EmulationSpeed fastForwardSpeed) {
        this.fastForwardSpeed = fastForwardSpeed;
    }

    /**
     * Whether the sound is off. Remembered, unlike whether fast forward is on: somebody who plays
     * with the sound down wants it down tomorrow as well, and a game that comes back silent is
     * explained by the tick next to Machine &gt; Mute.
     */
    public boolean muted() {
        return muted;
    }

    public void setMuted(final boolean muted) {
        this.muted = muted;
    }
}
