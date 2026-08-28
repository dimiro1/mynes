package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.ui.input.KeyBindings;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.VideoFilter;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
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
    private static final Logger logger = System.getLogger("UI");

    /**
     * Where the settings live.
     */
    public static final Path DEFAULT_PATH =
            Path.of(System.getProperty("user.home"), ".mynes", "config.properties");

    private static final String PALETTE_KEY = "video.palette";
    private static final String FILTER_KEY = "video.filter";
    private static final String PAL_PALETTE_KEY = "video.palette.pal";
    private static final String SCALE_KEY = "video.scale";
    private static final String SCREENSHOT_SCALE_KEY = "video.screenshot.scale";
    private static final String STATUS_BAR_KEY = "ui.status-bar";
    private static final String REGION_KEY = "emulation.region";
    private static final String FAST_FORWARD_KEY = "emulation.fast-forward";
    private static final String MUTED_KEY = "audio.muted";
    private static final String UNLIMITED_SPRITES_KEY = "hacks.unlimited-sprites";
    private static final String OVERCLOCK_KEY = "hacks.overclock";
    private static final String REWIND_SECONDS_KEY = "rewind.seconds";
    private static final String REWIND_KEY_KEY = "rewind.key";

    /**
     * How much history rewind keeps unless the file says otherwise. Long enough to undo the jump
     * that went wrong and short enough that nobody has to think about the memory.
     */
    private static final int DEFAULT_REWIND_SECONDS = 30;

    /**
     * The ceiling, which is a guard against a typo rather than a considered limit. Five minutes of
     * NTSC is 18,000 states; an extra nought on the end of the entry would be an hour of them, and
     * the first anybody would know of it is the emulator running out of heap in the middle of a
     * game.
     */
    private static final int MAX_REWIND_SECONDS = 300;

    /**
     * Backspace, for the reason the quick save and load keys are function keys: it sits in the same
     * physical place on every keyboard layout, which a letter does not. It is also already the
     * "undo the last thing" key everywhere else on the machine, and it is nowhere near the eight the
     * game wants.
     */
    private static final int DEFAULT_REWIND_KEY = KeyEvent.VK_BACK_SPACE;

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
            # One for each kind of machine, because the PAL PPU generates its colours from a
            # different burst phase and an NTSC table is simply wrong for it. Picking a palette
            # while a PAL game is running sets the second of these.
            """;

    private static final String FILTER_HEADER = """
            # How a frame becomes colours: none to look each pixel up in the palette above, or
            # ntsc to rebuild the composite signal the chip drew and decode it, which is where
            # colour bleed, dot crawl and artefact colours come from. The palette is not consulted
            # while ntsc is set, and it is ignored on a PAL machine, whose signal is a different
            # signal. Settings > Video Filter is the same setting.
            """;

    private static final String SCALE_HEADER = """
            # How many screen pixels wide a picture pixel is drawn, and so how big the window
            # opens: 1, 2, 3 or 4. Settings > Screen Size is the same setting, and the window
            # can still be dragged to any size at all from there.
            """;

    private static final String SCREENSHOT_SCALE_HEADER = """
            # How many times File > Screenshot magnifies the picture on its way into the file:
            # 1, 2, 3 or 4. 1 is the 256x224 the machine drew and is what to keep; the rest are
            # for a picture headed somewhere that will not scale it with square pixels. The files
            # land beside the ROM, named after it and stamped with the time.
            """;

    private static final String STATUS_BAR_HEADER = """
            # Whether the row along the bottom of the window is shown: the frame rate the machine
            # is really running at, which console it is, whatever hacks are on it, and what it is
            # doing. Settings > Status Bar is the same setting. Unlike every other yes-or-no entry
            # here a missing one means true, since the bar is on until somebody turns it off.
            """;

    private static final String REGION_HEADER = """
            # Which machine to run cartridges on: auto, ntsc or pal. Auto believes the header,
            # which nearly every dump leaves blank -- so a European game that comes out 17% fast
            # with the music too high is one to set to pal by hand. Machine > Region is the same
            # setting, and changing it there restarts the game.
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

    private static final String HACKS_HEADER = """
            # Things the console does not do, from the Hacks menu, and both off unless this says
            # otherwise. Unlimited sprites draws the sprites the chip would have dropped, so a
            # scanline holding more than eight of them stops flickering -- a change to the picture
            # and to nothing the game can see. It is true or false; anything that is not true is
            # off.
            #
            # The overclock is off, 25, 50, 100 or 200: that many percent of a frame in extra idle
            # scanlines, so a game whose main loop overruns its frame stops dropping one. A
            # percentage rather than a line count because a frame is 262 lines on NTSC and 312 on
            # PAL, and the setting should mean the same on both. Unlike the tick above this changes
            # the machine's timing and so what the game does.
            """;

    private static final String REWIND_HEADER = """
            # Holding the rewind key runs the game backwards through the last few seconds of it.
            # rewind.seconds is how many of them to keep -- 0 switches the whole thing off, and
            # anything over 300 is taken as 300. The cost is memory and about two milliseconds a
            # frame, both of which a machine holding no history pays none of.
            #
            # rewind.key is a VK_ name from java.awt.event.KeyEvent, the same as the controller
            # bindings below; an empty value leaves rewind with no key on it. There is no menu item
            # for this one, so this file is where it is remapped.
            """;

    private KeyBindings keyBindings;
    private NESPalette palette;
    private NESPalette palPalette;
    private VideoFilter videoFilter;
    private ScreenScale screenScale;
    private ScreenScale screenshotScale;
    private boolean statusBar;
    private RegionSetting region;
    private EmulationSpeed fastForwardSpeed;
    private boolean muted;
    private boolean unlimitedSprites;
    private OverclockSetting overclock;
    private int rewindSeconds;
    private int rewindKey;

    private Config(
            final KeyBindings keyBindings,
            final NESPalette palette,
            final NESPalette palPalette,
            final VideoFilter videoFilter,
            final ScreenScale screenScale,
            final ScreenScale screenshotScale,
            final boolean statusBar,
            final RegionSetting region,
            final EmulationSpeed fastForwardSpeed,
            final boolean muted,
            final boolean unlimitedSprites,
            final OverclockSetting overclock,
            final int rewindSeconds,
            final int rewindKey) {
        this.keyBindings = keyBindings;
        this.palette = palette;
        this.palPalette = palPalette;
        this.videoFilter = videoFilter;
        this.screenScale = screenScale;
        this.screenshotScale = screenshotScale;
        this.statusBar = statusBar;
        this.region = region;
        this.fastForwardSpeed = fastForwardSpeed;
        this.muted = muted;
        this.unlimitedSprites = unlimitedSprites;
        this.overclock = overclock;
        this.rewindSeconds = rewindSeconds;
        this.rewindKey = rewindKey;
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
            logger.log(Level.INFO, "no settings file at " + path + ", using the defaults");
        } else {
            try (var in = Files.newInputStream(path)) {
                properties.load(in);
                logger.log(Level.INFO, "loaded settings from " + path);
            } catch (IOException | IllegalArgumentException e) {
                // IllegalArgumentException is a malformed unicode escape somewhere in the file.
                logger.log(Level.WARNING, "could not read " + path + ", using the defaults", e);
            }
        }

        // An empty Properties answers for nothing, so a missing or unreadable file lands on the
        // same defaults every entry would have fallen back to one at a time.
        return new Config(
                KeyBindings.from(properties),
                paletteFrom(properties, PALETTE_KEY, Region.NTSC),
                paletteFrom(properties, PAL_PALETTE_KEY, Region.PAL),
                videoFilterFrom(properties),
                screenScaleFrom(properties, SCALE_KEY, ScreenScale.defaultScale()),
                screenScaleFrom(properties, SCREENSHOT_SCALE_KEY, ScreenScale.defaultScreenshotScale()),
                statusBarFrom(properties),
                regionFrom(properties),
                fastForwardSpeedFrom(properties),
                flagFrom(properties, MUTED_KEY),
                flagFrom(properties, UNLIMITED_SPRITES_KEY),
                OverclockSetting.byId(
                        properties.getProperty(OVERCLOCK_KEY, OverclockSetting.OFF.id()).trim()),
                rewindSecondsFrom(properties),
                KeyBindings.codeOf(
                        properties.getProperty(REWIND_KEY_KEY),
                        DEFAULT_REWIND_KEY,
                        REWIND_KEY_KEY));
    }

    /**
     * How many seconds of history to keep.
     * <p>
     * Two ways of being wrong and two different answers, because they are not the same mistake.
     * Something that is not a number at all says nothing about what was wanted, so it falls back to
     * the default like every other entry here. A number outside the range is a wish that can be
     * granted approximately, so it is clamped -- and a negative one clamps to zero, which is the
     * nearest thing to "less than none" the feature has.
     */
    private static int rewindSecondsFrom(final Properties properties) {
        var value = properties.getProperty(REWIND_SECONDS_KEY);

        if (value == null) {
            return DEFAULT_REWIND_SECONDS;
        }

        int seconds;

        try {
            seconds = Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            logger.log(Level.WARNING, value.trim() + " is not a number of seconds, "
                    + REWIND_SECONDS_KEY + " falls back to " + DEFAULT_REWIND_SECONDS);
            return DEFAULT_REWIND_SECONDS;
        }

        var clamped = Math.clamp(seconds, 0, MAX_REWIND_SECONDS);

        if (clamped != seconds) {
            logger.log(Level.WARNING,
                    REWIND_SECONDS_KEY + " is " + seconds + ", which is outside 0 to "
                            + MAX_REWIND_SECONDS + " -- keeping " + clamped + " seconds");
        }

        return clamped;
    }

    /**
     * One of the plain yes-or-no entries. Unlike the others there is nothing to fall back to and
     * nothing to warn about: anything that is not {@code true} is somebody who wants the ordinary
     * behaviour, which is also what a missing entry means.
     */
    private static boolean flagFrom(final Properties properties, final String key) {
        return Boolean.parseBoolean(properties.getProperty(key, "").trim());
    }

    /**
     * The one yes-or-no entry whose default is yes, which is why it cannot go through
     * {@link #flagFrom}: everything else here is a thing the emulator does only when asked, and the
     * status bar is a thing it does until told not to. A value that is not {@code true} still means
     * no, the same as everywhere else -- it is only a missing entry the two disagree about.
     */
    private static boolean statusBarFrom(final Properties properties) {
        var value = properties.getProperty(STATUS_BAR_KEY);

        return value == null || Boolean.parseBoolean(value.trim());
    }

    private static NESPalette paletteFrom(
            final Properties properties, final String key, final Region region) {
        var id = properties.getProperty(key);

        if (id == null) {
            return Palettes.defaultPalette(region);
        }

        return Palettes.byId(id.trim());
    }

    /**
     * Which filter to colour with, falling back with a word in the log rather than refusing to
     * start, the way every other entry in this file does.
     */
    private static VideoFilter videoFilterFrom(final Properties properties) {
        var id = properties.getProperty(FILTER_KEY);

        if (id == null) {
            return VideoFilter.NONE;
        }

        var filter = VideoFilter.byId(id);

        if (filter == null) {
            logger.log(Level.WARNING,
                    id.trim() + " is not a video filter, falling back to " + VideoFilter.NONE.id());

            return VideoFilter.NONE;
        }

        return filter;
    }

    private static RegionSetting regionFrom(final Properties properties) {
        var id = properties.getProperty(REGION_KEY);

        if (id == null) {
            return RegionSetting.defaultSetting();
        }

        return RegionSetting.byId(id.trim());
    }

    /**
     * One reader for the two sizes, since they are the same four multiples asked about twice. Only
     * what a missing entry means differs, which is the {@code fallback}.
     */
    private static ScreenScale screenScaleFrom(
            final Properties properties, final String key, final ScreenScale fallback) {
        var id = properties.getProperty(key);

        if (id == null) {
            return fallback;
        }

        return ScreenScale.byId(id.trim(), fallback);
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
                .append('\n')
                .append(PAL_PALETTE_KEY)
                .append('=')
                .append(palPalette.id())
                .append("\n\n");

        text.append(FILTER_HEADER)
                .append(FILTER_KEY)
                .append('=')
                .append(videoFilter.id())
                .append("\n\n");

        text.append(SCALE_HEADER)
                .append(SCALE_KEY)
                .append('=')
                .append(screenScale.id())
                .append("\n\n");

        text.append(SCREENSHOT_SCALE_HEADER)
                .append(SCREENSHOT_SCALE_KEY)
                .append('=')
                .append(screenshotScale.id())
                .append("\n\n");

        text.append(STATUS_BAR_HEADER)
                .append(STATUS_BAR_KEY)
                .append('=')
                .append(statusBar)
                .append("\n\n");

        text.append(REGION_HEADER)
                .append(REGION_KEY)
                .append('=')
                .append(region.id())
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

        text.append(HACKS_HEADER)
                .append(UNLIMITED_SPRITES_KEY)
                .append('=')
                .append(unlimitedSprites)
                .append('\n')
                .append(OVERCLOCK_KEY)
                .append('=')
                .append(overclock.id())
                .append("\n\n");

        text.append(REWIND_HEADER)
                .append(REWIND_SECONDS_KEY)
                .append('=')
                .append(rewindSeconds)
                .append('\n')
                .append(REWIND_KEY_KEY)
                .append('=')
                .append(KeyBindings.nameOf(rewindKey))
                .append("\n\n");

        keyBindings.appendTo(text);

        var parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        // ISO-8859-1 is what Properties.load reads a stream as. Everything written here is ASCII
        // either way; this only keeps the two ends of the round trip agreeing on paper.
        Files.writeString(path, text, StandardCharsets.ISO_8859_1);

        logger.log(Level.INFO, "saved settings to " + path);
    }

    public KeyBindings keyBindings() {
        return keyBindings;
    }

    public void setKeyBindings(final KeyBindings keyBindings) {
        this.keyBindings = keyBindings;
    }

    /**
     * The palette a machine of this kind is drawn with.
     * <p>
     * Two settings rather than one, because the two chips do not make the same colours: an NTSC
     * table on a PAL game is not a matter of taste, it is the wrong hues. So a choice made while a
     * PAL game is running is remembered as a choice about PAL games, and somebody's NTSC preference
     * survives loading a European cartridge.
     */
    public NESPalette palette(final Region region) {
        return region == Region.PAL ? palPalette : palette;
    }

    public void setPalette(final Region region, final NESPalette palette) {
        if (region == Region.PAL) {
            this.palPalette = palette;
        } else {
            this.palette = palette;
        }
    }

    /**
     * How a frame becomes colours. One setting rather than two, unlike the palette above: the NTSC
     * decoder does not run on a PAL machine at all, so there is no PAL preference for it to hold.
     */
    public VideoFilter videoFilter() {
        return videoFilter;
    }

    public void setVideoFilter(final VideoFilter videoFilter) {
        this.videoFilter = videoFilter;
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
     * How big File &gt; Screenshot writes the picture. A setting of its own rather than the window's
     * size, because the two are asked for different reasons: the window is as big as the display
     * allows, and a picture file wants to be the size it is going to be looked at.
     */
    public ScreenScale screenshotScale() {
        return screenshotScale;
    }

    public void setScreenshotScale(final ScreenScale screenshotScale) {
        this.screenshotScale = screenshotScale;
    }

    /**
     * Whether the window shows the row along its bottom.
     */
    public boolean statusBar() {
        return statusBar;
    }

    public void setStatusBar(final boolean statusBar) {
        this.statusBar = statusBar;
    }

    /**
     * Which machine cartridges are run on. Remembered, because somebody whose collection is
     * European has a collection that is still European tomorrow.
     */
    public RegionSetting region() {
        return region;
    }

    public void setRegion(final RegionSetting region) {
        this.region = region;
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

    /**
     * Whether Hacks &gt; Unlimited Sprites is on. Remembered for the reason Mute is: it is a
     * preference about how the emulator should behave rather than something a particular game did,
     * and somebody who wants the flicker gone wants it gone tomorrow as well.
     */
    public boolean unlimitedSprites() {
        return unlimitedSprites;
    }

    public void setUnlimitedSprites(final boolean unlimitedSprites) {
        this.unlimitedSprites = unlimitedSprites;
    }

    /**
     * How much extra time a frame Hacks &gt; Overclock is giving the game. Remembered for the reason
     * the tick above is, and kept as a percentage rather than as a number of scanlines so that it
     * means the same thing after a region switch.
     */
    public OverclockSetting overclock() {
        return overclock;
    }

    public void setOverclock(final OverclockSetting overclock) {
        this.overclock = overclock;
    }

    /**
     * How many seconds of the game to keep so it can be run backwards, or 0 for a machine that keeps
     * none and so costs nothing.
     * <p>
     * Seconds rather than frames because that is the question somebody is actually asking, and
     * because the answer in frames depends on which machine the cartridge turns out to run on --
     * which is not known until one is loaded.
     */
    public int rewindSeconds() {
        return rewindSeconds;
    }

    public void setRewindSeconds(final int rewindSeconds) {
        this.rewindSeconds = Math.clamp(rewindSeconds, 0, MAX_REWIND_SECONDS);
    }

    /**
     * The key held down to run the game backwards, or {@link KeyBindings#UNBOUND} for nobody's key.
     * <p>
     * Not one of {@link KeyBindings}'s eight, because it is not a button: no wire in the controller
     * port carries it and no game can see it. It is remapped by editing the file rather than through
     * Settings &gt; Controller..., which is a dialog about the eight things a NES pad had.
     */
    public int rewindKey() {
        return rewindKey;
    }

    public void setRewindKey(final int rewindKey) {
        this.rewindKey = rewindKey;
    }
}
