package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.Controller;
import org.jetbrains.annotations.Nullable;

import java.awt.event.KeyEvent;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Which key presses which button, on which of the console's two pads.
 * <p>
 * Immutable: {@link #with(Port, Button, int)} returns a new set rather than editing this one, so the
 * dispatcher reading the bindings on the event dispatch thread never sees a half applied remap.
 * <p>
 * <b>A key belongs to one button on one pad.</b> Putting it on a second takes it off the first,
 * across the two ports as well as within one -- a key that pressed A on both pads would walk both
 * players at once, which is a bug rather than a shortcut, and stealing rather than refusing is what
 * makes swapping a pair possible.
 * <p>
 * A value type, and one section of a larger file: {@code com.github.dimiro1.mynes.ui.Config} owns
 * {@code ~/.mynes/config.properties} and hands the properties down here. That file is meant to be
 * edited by hand as much as through the settings dialog, which is why the values are {@code VK_}
 * constant names rather than numbers or whatever {@link KeyEvent#getKeyText(int)} happens to call
 * a key in the current locale. Nothing in it is trusted: an entry that is missing or misspelled
 * falls back to the default for that button, one at a time, so a botched edit costs a binding
 * rather than a startup.
 */
public final class KeyBindings {
    private static final Logger logger = System.getLogger("INPUT");

    /**
     * The key code of a button nothing is bound to. {@link KeyEvent#VK_UNDEFINED} is zero and no
     * key event that names a key carries it, so it doubles as "no key".
     */
    public static final int UNBOUND = KeyEvent.VK_UNDEFINED;

    private static final String HEADER = """
            # Which key presses which button. Values are the names of the VK_ constants in
            # java.awt.event.KeyEvent, for instance VK_X, VK_LEFT or VK_ENTER; an empty value
            # leaves the button unbound. No key presses two buttons: giving one to a second
            # takes it off the first, on the other pad as readily as on the same one.
            #
            # Player two is empty on a fresh install. A second pad is worth nothing to somebody
            # playing alone, and any default for it would be eight keys taken off their keyboard
            # to buy them that -- and eight keys that would land somewhere else again on a
            # keyboard that is not laid out like this one.
            """;

    /**
     * The console's two controller ports.
     * <p>
     * The prefix is also the name of that pad's entries in the config file, so a port cannot appear
     * here without the file gaining a section for it.
     */
    public enum Port {
        ONE("Player One", "controller1."),
        TWO("Player Two", "controller2.");

        private final String label;
        private final String prefix;

        Port(final String label, final String prefix) {
            this.label = label;
            this.prefix = prefix;
        }

        /**
         * How the port is spelled in the settings dialog.
         */
        public String label() {
            return label;
        }

        String propertyKey(final Button button) {
            return prefix + button.name().toLowerCase(Locale.ROOT);
        }
    }

    /**
     * The eight buttons, in the order the shift register clocks them out and the settings dialog
     * lists them. The constant name is also the name of the entry in the config file, so a button
     * cannot appear here without the file gaining a line for it.
     */
    public enum Button {
        A(Controller.BUTTON_A, "A"),
        B(Controller.BUTTON_B, "B"),
        SELECT(Controller.BUTTON_SELECT, "Select"),
        START(Controller.BUTTON_START, "Start"),
        UP(Controller.BUTTON_UP, "Up"),
        DOWN(Controller.BUTTON_DOWN, "Down"),
        LEFT(Controller.BUTTON_LEFT, "Left"),
        RIGHT(Controller.BUTTON_RIGHT, "Right");

        private final int mask;
        private final String label;

        Button(final int mask, final String label) {
            this.mask = mask;
            this.label = label;
        }

        /**
         * The {@code Controller.BUTTON_*} bit this button sets in the mask handed to a
         * {@link Controller}.
         */
        public int mask() {
            return mask;
        }

        /**
         * How the button is spelled in the settings dialog.
         */
        public String label() {
            return label;
        }
    }

    /**
     * What a key does: one button, on one pad.
     * <p>
     * A pair rather than a button on its own, because "what does this keystroke press" stopped
     * having an answer that a caller could assume belonged to player one.
     */
    public record Press(Port port, Button button) {
    }

    private static final Map<String, Integer> CODES_BY_NAME;
    private static final Map<Integer, String> NAMES_BY_CODE;

    static {
        var codes = new HashMap<String, Integer>();
        var names = new HashMap<Integer, String>();

        for (var field : KeyEvent.class.getFields()) {
            if (!field.getName().startsWith("VK_")
                    || field.getType() != int.class
                    || !Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            try {
                var code = field.getInt(null);
                codes.put(field.getName(), code);
                // A few codes carry two names. getFields() has no defined order, so settle it in
                // a way that does not depend on one.
                names.merge(code, field.getName(), (a, b) -> a.compareTo(b) <= 0 ? a : b);
            } catch (IllegalAccessException e) {
                throw new AssertionError("the VK_ constants are public", e);
            }
        }

        CODES_BY_NAME = Map.copyOf(codes);
        NAMES_BY_CODE = Map.copyOf(names);
    }

    /**
     * Arrows and Z/X for player one, which sit on the same physical keys on QWERTY and on
     * Colemak-DH, and nothing at all for player two.
     */
    private static final Map<Port, Map<Button, Integer>> DEFAULTS = defaultKeys();

    private final Map<Port, Map<Button, Integer>> keys;

    /**
     * Takes ownership of {@code keys}; every caller builds fresh maps and keeps no reference.
     */
    private KeyBindings(final Map<Port, Map<Button, Integer>> keys) {
        this.keys = keys;
    }

    private static Map<Port, Map<Button, Integer>> defaultKeys() {
        var one = new EnumMap<Button, Integer>(Button.class);

        one.put(Button.A, KeyEvent.VK_X);
        one.put(Button.B, KeyEvent.VK_Z);
        one.put(Button.SELECT, KeyEvent.VK_SHIFT);
        one.put(Button.START, KeyEvent.VK_ENTER);
        one.put(Button.UP, KeyEvent.VK_UP);
        one.put(Button.DOWN, KeyEvent.VK_DOWN);
        one.put(Button.LEFT, KeyEvent.VK_LEFT);
        one.put(Button.RIGHT, KeyEvent.VK_RIGHT);

        var keys = new EnumMap<Port, Map<Button, Integer>>(Port.class);

        keys.put(Port.ONE, one);

        // Player two is deliberately empty rather than sat on a second set of keys. Two people at
        // one keyboard is the rarer half of the rare case -- most cartridges have no two player
        // mode at all -- and a default for it would take eight keys away from every session that
        // is not one. There is nowhere obvious to put them either: WASD is four different keys on
        // a Colemak or a Dvorak keyboard and a numeric keypad is missing from every laptop, where
        // the arrows and Z/X above are the same keys everywhere. So this is one dialog away rather
        // than guessed at.
        keys.put(Port.TWO, new EnumMap<>(Button.class));

        return keys;
    }

    public static KeyBindings defaults() {
        return new KeyBindings(defaultKeys());
    }

    /**
     * Picks the bindings out of an already loaded config file, filling in the default for anything
     * it does not answer for. Properties that answer for nothing at all give the defaults.
     */
    public static KeyBindings from(final Properties properties) {
        var keys = new EnumMap<Port, Map<Button, Integer>>(Port.class);

        for (var port : Port.values()) {
            var buttons = new EnumMap<Button, Integer>(Button.class);

            for (var button : Button.values()) {
                buttons.put(
                        button,
                        codeOf(
                                properties.getProperty(port.propertyKey(button)),
                                DEFAULTS.get(port).getOrDefault(button, UNBOUND),
                                port.propertyKey(button)));
            }

            keys.put(port, buttons);
        }

        return new KeyBindings(keys);
    }

    /**
     * Reads one {@code VK_} name out of the config file, the way every entry in it is read: nothing
     * is trusted, and a bad one costs its own setting rather than the startup.
     * <p>
     * Public and not about the eight buttons, because the file has grown other keys since -- the
     * rewind key is one -- and the alternative is a second reflection-built table somewhere else
     * that disagrees with this one about what this JDK calls a key.
     *
     * @param name     the value as it appears in the file, or null if the entry is not there.
     * @param fallback what a missing or unreadable entry means. {@link #UNBOUND} for a key that is
     *                 allowed to be nothing.
     * @param setting  the property this came from, so the log says which line to go and fix.
     * @return the key code, or {@link #UNBOUND} for an entry deliberately emptied out.
     */
    public static int codeOf(
            final @Nullable String name, final int fallback, final String setting) {
        if (name == null) {
            return fallback;
        }

        var trimmed = name.trim();
        if (trimmed.isEmpty()) {
            // Deliberately unbound. Someone who would rather not give up a key for Select can
            // empty the entry out and say so.
            return UNBOUND;
        }

        var code = CODES_BY_NAME.get(trimmed);
        if (code == null) {
            logger.log(Level.WARNING, trimmed + " is not a key name, "
                    + setting + " falls back to its default");
            return fallback;
        }

        return code;
    }

    /**
     * Writes this section into the file its owner is building, comment and all.
     * <p>
     * Written by hand rather than through {@link Properties#store} so that the buttons come out in
     * a fixed, readable order -- the order the shift register clocks them out in -- instead of
     * whatever order the hash table holds them in. Both pads are written every time, empty values
     * and all, so that the file says what can be remapped rather than only what has been.
     */
    public void appendTo(final StringBuilder text) {
        text.append(HEADER);

        for (var port : Port.values()) {
            for (var button : Button.values()) {
                text.append(port.propertyKey(button))
                        .append('=')
                        .append(nameOf(keyFor(port, button)))
                        .append('\n');
            }
        }
    }

    /**
     * How a key code is spelled back into the config file, and the other half of {@link #codeOf}.
     * Empty for a key that is nothing, which is what reads back as unbound.
     */
    public static String nameOf(final int code) {
        if (code == UNBOUND) {
            return "";
        }

        var name = NAMES_BY_CODE.get(code);
        if (name == null) {
            // AWT hands out VK_ constants or VK_UNDEFINED, so reaching this needs a key that the
            // toolkit knows and this JDK's KeyEvent does not name. Dropping the binding beats
            // writing a number that will not read back.
            logger.log(Level.WARNING, "key code " + code + " has no name, leaving it out of the file");
            return "";
        }

        return name;
    }

    /**
     * The key bound to {@code button} on {@code port}, or {@link #UNBOUND}.
     */
    public int keyFor(final Port port, final Button button) {
        return keys.get(port).getOrDefault(button, UNBOUND);
    }

    /**
     * The pad and button {@code keyCode} presses, or null if nothing is bound to that key.
     * <p>
     * The guard on {@link #UNBOUND} is load-bearing rather than tidy: an empty player two holds
     * that code in all eight of its buttons, so asking what "no key at all" presses would come back
     * with player two's A.
     */
    public @Nullable Press pressFor(final int keyCode) {
        if (keyCode == UNBOUND) {
            return null;
        }

        for (var port : Port.values()) {
            for (var button : Button.values()) {
                if (keyFor(port, button) == keyCode) {
                    return new Press(port, button);
                }
            }
        }

        return null;
    }

    /**
     * A copy with {@code button} on {@code port} moved to {@code keyCode}, taking that key off
     * whatever else was using it -- on either pad.
     * <p>
     * Stealing rather than refusing is what makes swapping two buttons possible: give A the key B
     * is on, which leaves B unbound, then give B the key A used to be on. Across the two pads it is
     * also the only honest answer, since a key on both would press both.
     */
    public KeyBindings with(final Port port, final Button button, final int keyCode) {
        var updated = new EnumMap<Port, Map<Button, Integer>>(Port.class);

        for (var each : Port.values()) {
            updated.put(each, new EnumMap<>(keys.get(each)));
        }

        if (keyCode != UNBOUND) {
            for (var otherPort : Port.values()) {
                for (var otherButton : Button.values()) {
                    if ((otherPort != port || otherButton != button)
                            && keyFor(otherPort, otherButton) == keyCode) {
                        updated.get(otherPort).put(otherButton, UNBOUND);
                    }
                }
            }
        }

        updated.get(port).put(button, keyCode);

        return new KeyBindings(updated);
    }
}
