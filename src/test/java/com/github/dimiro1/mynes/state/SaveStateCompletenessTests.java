package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether anything at all has been left out of the format.
 * <p>
 * {@link SaveStateDivergenceTests} runs two machines and compares them, which is the test that
 * matters -- but it can only catch a forgotten field whose value actually differs between the two.
 * A field the ROM never touches holds the same value in both machines, so leaving it out of the
 * format changes nothing and the comparison passes. Every vendored ROM here fails to scroll, so
 * {@code fineX} is nought in both; none reads a write-only register, so {@code openBus} matches;
 * none reads $2007 before setting $2006, so {@code readBuffer} matches. All three could be deleted
 * from the format and no amount of running would show it.
 * <p>
 * This test does not run the machine to find out. It walks the console with reflection, field by
 * field, and asserts that a machine restored from a state agrees with the machine the state came
 * from on <em>every one of them</em> -- so a field that is not in the format fails here the moment
 * the two machines happen to differ in it, which two machines at different points in different runs
 * essentially always do.
 * <p>
 * The price is {@link #NOT_IN_THE_STATE}: an explicit list of what is deliberately left out. That
 * list is the point rather than an inconvenience. Anything not on it and not in the format is a bug,
 * and adding a field to the console now means either serialising it or writing down why not.
 */
class SaveStateCompletenessTests {

    /**
     * A ROM with rendering switched on, so the pipeline fields hold something worth comparing.
     */
    private static final String ROM = "/ppu-sprite-overflow/04-obscure.nes";

    /**
     * What a save state deliberately does not carry, and why.
     * <p>
     * Keyed by {@code SimpleName.fieldName}. Every entry is a decision rather than an oversight, and
     * the reason is here so that the next person to read it can disagree with it.
     */
    private static final Map<String, String> NOT_IN_THE_STATE = Map.ofEntries(
            Map.entry("StandardController.buttons",
                    "the player's hands, which a file cannot put back -- and a machine that came"
                            + " back with A held would never see it released"),
            Map.entry("PPU.backgroundLayerVisible",
                    "a debug switch belonging to whoever is watching, not to the machine"),
            Map.entry("PPU.spriteLayerVisible",
                    "the same, and restoring it would contradict the Debug menu's tick"),
            Map.entry("MMU.writeListener",
                    "where a debugger's watchpoints wire in -- whoever is watching the machine"
                            + " rather than the machine, and a state that put one back would be"
                            + " restoring the debugger"),
            Map.entry("APU.sampleRing",
                    "the queue between the chip and the sound card rather than the chip. Both real"
                            + " drivers drain it at the end of every frame, so a state taken through"
                            + " either of them is taken when it is empty anyway"),
            Map.entry("APU.sampleRead", "an index into that queue"),
            Map.entry("APU.sampleWrite", "an index into that queue"),
            Map.entry("APU.sampleCount", "how full that queue is"),
            Map.entry("CPU.speculating",
                    "true only in the middle of a halted cycle, which is run and then taken back."
                            + " A state is taken between cycles, where it is always false"),
            Map.entry("CPU.wroteThisCycle",
                    "the same scratch: what the cycle now running put on the bus, read once by the"
                            + " halt that armed it and meaningless to anybody else"));

    @Test
    void everyMutableFieldInTheConsoleTravelsWithTheState() throws IOException {
        var original = load();
        run(original, 5);
        for (var i = 0; i < 5710; i++) {
            original.tick();
        }

        var state = save(original);

        // Somewhere quite different, and then deliberately vandalised: every settable field in the
        // machine is given a value the original almost certainly does not have. Running a second
        // machine on the same ROM is not enough on its own, because a field the ROM never touches
        // holds the same value in both and its absence from the format would go unnoticed.
        var other = load();
        other.getController1().setButtons(Controller.BUTTON_A | Controller.BUTTON_RIGHT);
        run(other, 137);
        other.getController1().setButtons(0);

        var scrambled = scramble(other);
        var before = fieldsOf(other);

        SaveState.read(other, new ByteArrayInputStream(state));

        var expected = fieldsOf(original);
        var actual = fieldsOf(other);

        assertEquals(
                expected.keySet(),
                actual.keySet(),
                "the two machines do not even have the same fields");

        var wrong = new LinkedHashMap<String, String>();
        var untested = new ArrayList<String>();

        for (var name : expected.keySet()) {
            if (NOT_IN_THE_STATE.containsKey(name)) {
                continue;
            }

            if (!expected.get(name).equals(actual.get(name))) {
                wrong.put(name, "expected " + expected.get(name) + " but found " + actual.get(name));
            } else if (before.get(name).equals(expected.get(name))) {
                untested.add(name);
            }
        }

        assertEquals(
                Map.of(),
                wrong,
                "these fields did not survive the round trip -- either serialise them, or add them"
                        + " to NOT_IN_THE_STATE with a reason");

        // Every field the scrambler could reach was different before the load, so the assertion above
        // was real evidence for all of them. What is left over is the fields it could not reach --
        // final arrays of ROM, lookup tables, filter coefficients -- none of which can differ between
        // two machines running the same cartridge. Anything else appearing here means the scrambler
        // has stopped reaching part of the console, and the test has quietly lost its teeth.
        assertTrue(
                untested.stream().noneMatch(scrambled::contains),
                "the scrambler reached these and they matched anyway, so nothing above proves they"
                        + " travel: " + untested.stream().filter(scrambled::contains).toList());
    }

    /**
     * The fields left out of the format really are left out, so that the list above is a statement
     * about this build rather than a wish. Without this, an entry could sit there forever describing
     * a decision that had been quietly reversed.
     */
    @Test
    void whatIsLeftOutOfTheStateStaysOutOfIt() throws IOException {
        var original = load();
        run(original, 5);
        original.getController1().setButtons(Controller.BUTTON_START | Controller.BUTTON_B);
        original.getPPU().setBackgroundLayerVisible(false);

        var state = save(original);

        var other = load();
        run(other, 20);
        other.getController1().setButtons(0);
        other.getPPU().setBackgroundLayerVisible(true);

        SaveState.read(other, new ByteArrayInputStream(state));

        var fields = fieldsOf(other);

        assertEquals("0", fields.get("StandardController.buttons"),
                "the buttons came across, and the release will never arrive");
        assertEquals("true", fields.get("PPU.backgroundLayerVisible"),
                "a state overrode the Debug menu");
    }

    /**
     * The exclusion list names real fields, so an entry cannot sit there describing a decision that
     * has since been reversed or a field that has been renamed.
     */
    @Test
    void theExclusionListNamesFieldsThatExist() throws IOException {
        var fields = fieldsOf(load()).keySet();

        for (var name : NOT_IN_THE_STATE.keySet()) {
            assertTrue(fields.contains(name), name + " is on the exclusion list but does not exist");
        }
    }

    // ================================================================================== internals

    /**
     * Every primitive and primitive array reachable from the console, by
     * {@code SimpleName.fieldName}.
     * <p>
     * Recurses into the emulator's own objects so that the APU's five channels, their length
     * counters and envelopes, and the PPU's VRAM are all reached -- they are private nested classes
     * with no accessors, and reflection is the only way in. Arrays are reduced to a hash so that a
     * failure message stays readable.
     */
    private static Map<String, String> fieldsOf(final NES nes) {
        var fields = new TreeMap<String, String>();

        // The mapper arrives through the Cart the NES now holds, so one walk reaches everything.
        collect(nes, fields, new IdentityHashMap<>());

        return fields;
    }

    private static void collect(
            final Object owner,
            final Map<String, String> into,
            final IdentityHashMap<Object, Boolean> seen) {
        if (owner == null || seen.put(owner, true) != null) {
            return;
        }

        for (var field : owner.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            field.setAccessible(true);

            Object value;

            try {
                value = field.get(owner);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("could not read " + field, e);
            }

            var name = owner.getClass().getSimpleName() + "." + field.getName();

            if (field.getType().isPrimitive() || value instanceof Enum<?> || value == null) {
                into.put(name, String.valueOf(value));
            } else if (field.getType().isArray()
                    && field.getType().getComponentType().isPrimitive()) {
                into.put(name, name + "#" + hashOf(value));
            } else if (value.getClass().getName().startsWith("com.github.dimiro1.mynes")
                    && !value.getClass().isSynthetic()) {
                // Into the emulator's own objects, which is the only way to reach the APU's five
                // channels and their length counters and envelopes: all private nested classes with
                // no accessors between them. Synthetic classes are skipped because an IRQHandler
                // lambda is one, and its only field is the BUS it captured.
                collect(value, into, seen);
            }
        }
    }

    /**
     * Gives every field it can reach a value the machine it came from almost certainly does not have,
     * and reports which ones it managed.
     * <p>
     * This is what turns the assertion above from "the ROM happened to leave these different" into
     * "these were definitely different". Only what can be written is touched: a {@code final}
     * reference cannot be, but the contents of a {@code final} array can, which is how the OAM, the
     * palettes, the nametables and the cartridge RAM get vandalised along with everything else.
     * <p>
     * The machine is not run afterwards -- a scrambled console is nonsense, and the point is only
     * that a load has to overwrite all of it.
     */
    private static List<String> scramble(final NES nes) {
        var touched = new ArrayList<String>();

        scramble(nes, touched, new IdentityHashMap<>(), new int[]{1});

        return touched;
    }

    private static void scramble(
            final Object owner,
            final List<String> touched,
            final IdentityHashMap<Object, Boolean> seen,
            final int[] counter) {
        if (owner == null || seen.put(owner, true) != null) {
            return;
        }

        for (var field : owner.getClass().getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            field.setAccessible(true);

            Object value;

            try {
                value = field.get(owner);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("could not read " + field, e);
            }

            var name = owner.getClass().getSimpleName() + "." + field.getName();
            var next = counter[0]++;

            if (field.getType().isArray() && field.getType().getComponentType().isPrimitive()) {
                // Only the ones a state is expected to carry. Scrambling the PRG ROM would be
                // scrambling the cartridge, which no save state claims to put back.
                if (!isCartridgeROM(name)) {
                    for (var i = 0; i < Array.getLength(value); i++) {
                        writeElement(value, i, next + i);
                    }

                    touched.add(name);
                }
            } else if (value != null
                    && value.getClass().getName().startsWith("com.github.dimiro1.mynes")
                    && !value.getClass().isSynthetic()) {
                // Whether or not the reference is final: BUS holds the four chips in non-final
                // fields, so recursing only through final ones would never reach the CPU at all.
                scramble(value, touched, seen, counter);
            } else if (!Modifier.isFinal(field.getModifiers()) && write(owner, field, value, next)) {
                touched.add(name);
            }
        }
    }

    /**
     * The two arrays that come out of the .nes file rather than out of the machine.
     */
    private static boolean isCartridgeROM(final String name) {
        return name.endsWith(".prgROM") || name.endsWith(".chrROM")
                || name.equals("Mapper0.chr");
    }

    private static boolean write(
            final Object owner, final java.lang.reflect.Field field, final Object was, final int n) {
        try {
            var type = field.getType();

            if (type == int.class) {
                field.setInt(owner, (n % 200) + 7);
            } else if (type == long.class) {
                field.setLong(owner, n + 100_000L);
            } else if (type == boolean.class) {
                field.setBoolean(owner, !(boolean) was);
            } else if (type == double.class) {
                field.setDouble(owner, n + 0.5);
            } else if (was instanceof Enum<?> constant) {
                var constants = constant.getClass().getEnumConstants();
                field.set(owner, constants[(constant.ordinal() + 1) % constants.length]);
            } else {
                return false;
            }

            return true;
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("could not write " + field, e);
        }
    }

    private static void writeElement(final Object array, final int index, final int value) {
        var type = array.getClass().getComponentType();

        if (type == int.class) {
            Array.setInt(array, index, (value % 200) + 7);
        } else if (type == byte.class) {
            Array.setByte(array, index, (byte) ((value % 200) + 7));
        } else if (type == long.class) {
            Array.setLong(array, index, value + 100_000L);
        } else if (type == short.class) {
            Array.setShort(array, index, (short) ((value % 200) + 7));
        } else if (type == double.class) {
            Array.setDouble(array, index, value + 0.5);
        }
    }

    private static int hashOf(final Object array) {
        var hash = 1;

        for (var i = 0; i < Array.getLength(array); i++) {
            hash = hash * 31 + Array.get(array, i).hashCode();
        }

        return hash;
    }

    private static NES load() throws IOException {
        return load(ROM);
    }

    private static NES load(final String resource) throws IOException {
        try (var rom = SaveStateCompletenessTests.class.getResourceAsStream(resource)) {
            assertNotNull(rom, resource);
            return new NES(Cart.load(rom.readAllBytes(), resource));
        }
    }

    private static byte[] save(final NES nes) throws IOException {
        var out = new ByteArrayOutputStream();

        SaveState.write(nes, out);

        return out.toByteArray();
    }

    private static void run(final NES nes, final int frames) {
        var ppu = nes.getPPU();

        for (var i = 0; i < frames; i++) {
            var frame = ppu.getFrame();

            do {
                nes.tick();
            } while (ppu.getFrame() == frame);
        }
    }
}
