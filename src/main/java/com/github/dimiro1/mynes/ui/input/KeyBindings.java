package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.Controller;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.event.KeyEvent;
import java.lang.reflect.Modifier;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Which key presses which button.
 * <p>
 * Immutable: {@link #with(Button, int)} returns a new set rather than editing this one, so the
 * dispatcher reading the bindings on the event dispatch thread never sees a half applied remap.
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
    private static final Logger logger = LoggerFactory.getLogger("INPUT");

    /**
     * The key code of a button nothing is bound to. {@link KeyEvent#VK_UNDEFINED} is zero and no
     * key event that names a key carries it, so it doubles as "no key".
     */
    public static final int UNBOUND = KeyEvent.VK_UNDEFINED;

    private static final String HEADER = """
            # Which key presses which button. Values are the names of the VK_ constants in
            # java.awt.event.KeyEvent, for instance VK_X, VK_LEFT or VK_ENTER; an empty value
            # leaves the button unbound.
            """;

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

        String propertyKey() {
            return "controller1." + name().toLowerCase(Locale.ROOT);
        }
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
     * Arrows and Z/X, which sit on the same physical keys on QWERTY and on Colemak-DH.
     */
    private static final Map<Button, Integer> DEFAULTS = defaultKeys();

    private final Map<Button, Integer> keys;

    /**
     * Takes ownership of {@code keys}; every caller builds a fresh map and keeps no reference.
     */
    private KeyBindings(final Map<Button, Integer> keys) {
        this.keys = keys;
    }

    private static Map<Button, Integer> defaultKeys() {
        var keys = new EnumMap<Button, Integer>(Button.class);

        keys.put(Button.A, KeyEvent.VK_X);
        keys.put(Button.B, KeyEvent.VK_Z);
        keys.put(Button.SELECT, KeyEvent.VK_SHIFT);
        keys.put(Button.START, KeyEvent.VK_ENTER);
        keys.put(Button.UP, KeyEvent.VK_UP);
        keys.put(Button.DOWN, KeyEvent.VK_DOWN);
        keys.put(Button.LEFT, KeyEvent.VK_LEFT);
        keys.put(Button.RIGHT, KeyEvent.VK_RIGHT);

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
        var keys = new EnumMap<Button, Integer>(Button.class);

        for (var button : Button.values()) {
            keys.put(button, parse(properties.getProperty(button.propertyKey()), button));
        }

        return new KeyBindings(keys);
    }

    private static int parse(final @Nullable String value, final Button button) {
        if (value == null) {
            return DEFAULTS.get(button);
        }

        var name = value.trim();
        if (name.isEmpty()) {
            // Deliberately unbound. Someone who would rather not give up a key for Select can
            // empty the entry out and say so.
            return UNBOUND;
        }

        var code = CODES_BY_NAME.get(name);
        if (code == null) {
            logger.warn("{} is not a key name, {} falls back to its default",
                    name, button.propertyKey());
            return DEFAULTS.get(button);
        }

        return code;
    }

    /**
     * Writes this section into the file its owner is building, comment and all.
     * <p>
     * Written by hand rather than through {@link Properties#store} so that the buttons come out in
     * a fixed, readable order -- the order the shift register clocks them out in -- instead of
     * whatever order the hash table holds them in.
     */
    public void appendTo(final StringBuilder text) {
        text.append(HEADER);

        for (var button : Button.values()) {
            text.append(button.propertyKey())
                    .append('=')
                    .append(nameFor(keyFor(button)))
                    .append('\n');
        }
    }

    private static String nameFor(final int code) {
        if (code == UNBOUND) {
            return "";
        }

        var name = NAMES_BY_CODE.get(code);
        if (name == null) {
            // AWT hands out VK_ constants or VK_UNDEFINED, so reaching this needs a key that the
            // toolkit knows and this JDK's KeyEvent does not name. Dropping the binding beats
            // writing a number that will not read back.
            logger.warn("key code {} has no name, leaving it out of the file", code);
            return "";
        }

        return name;
    }

    /**
     * The key bound to {@code button}, or {@link #UNBOUND}.
     */
    public int keyFor(final Button button) {
        return keys.getOrDefault(button, UNBOUND);
    }

    /**
     * The button {@code keyCode} presses, or null if nothing is bound to that key.
     */
    public @Nullable Button buttonFor(final int keyCode) {
        if (keyCode == UNBOUND) {
            return null;
        }

        for (var button : Button.values()) {
            if (keyFor(button) == keyCode) {
                return button;
            }
        }

        return null;
    }

    /**
     * A copy with {@code button} moved to {@code keyCode}, taking that key off whatever else was
     * using it.
     * <p>
     * Stealing rather than refusing is what makes swapping two buttons possible: give A the key B
     * is on, which leaves B unbound, then give B the key A used to be on.
     */
    public KeyBindings with(final Button button, final int keyCode) {
        var updated = new EnumMap<>(keys);

        if (keyCode != UNBOUND) {
            for (var other : Button.values()) {
                if (other != button && keyFor(other) == keyCode) {
                    updated.put(other, UNBOUND);
                }
            }
        }

        updated.put(button, keyCode);

        return new KeyBindings(updated);
    }
}
