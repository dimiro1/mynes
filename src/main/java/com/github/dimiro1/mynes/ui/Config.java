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
    private static final String FAST_FORWARD_KEY = "emulation.fast-forward";

    private static final String HEADER = """
            # MyNES settings.
            #
            # The menus rewrite this file, dropping anything added by hand. An entry that is
            # missing or that this version does not understand falls back to its default and
            # says so in the log.

            """;

    private static final String VIDEO_HEADER = """
            # The palette the picture is drawn with. Settings > Palette... has the list; the
            # value is the id shown there in lower case with dashes, such as nes-classic.
            """;

    private static final String EMULATION_HEADER = """
            # How fast Machine > Fast Forward runs the machine: 2x, 4x, 8x, or unlimited, which
            # is as fast as this computer manages. Anything the computer cannot keep up with
            # runs at whatever it does manage.
            """;

    private KeyBindings keyBindings;
    private NESPalette palette;
    private EmulationSpeed fastForwardSpeed;

    private Config(
            final KeyBindings keyBindings,
            final NESPalette palette,
            final EmulationSpeed fastForwardSpeed) {
        this.keyBindings = keyBindings;
        this.palette = palette;
        this.fastForwardSpeed = fastForwardSpeed;
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
                fastForwardSpeedFrom(properties));
    }

    private static NESPalette paletteFrom(final Properties properties) {
        var id = properties.getProperty(PALETTE_KEY);

        if (id == null) {
            return Palettes.defaultPalette();
        }

        return Palettes.byId(id.trim());
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

        text.append(VIDEO_HEADER)
                .append(PALETTE_KEY)
                .append('=')
                .append(palette.id())
                .append("\n\n");

        text.append(EMULATION_HEADER)
                .append(FAST_FORWARD_KEY)
                .append('=')
                .append(fastForwardSpeed.id())
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
}
