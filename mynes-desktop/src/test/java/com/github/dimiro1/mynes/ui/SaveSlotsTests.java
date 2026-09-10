package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.state.SaveState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JMenu;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the nine slots say they have in them, which is the half of this that has a decision in it.
 * <p>
 * Writing and reading a slot belongs to the window, so none of that is here. What is here is the
 * labelling: nine numbered items are a guessing game unless each one says what is in it, and that
 * answer comes off the disk every time the menu is pulled down.
 */
class SaveSlotsTests {

    @TempDir
    private Path directory;

    private final List<Integer> saved = new ArrayList<>();
    private final List<Integer> loaded = new ArrayList<>();

    private SaveSlots slots() {
        return new SaveSlots(saved::add, loaded::add);
    }

    @Test
    void thereAreNineOfThemInEachSubmenuNumberedFromOne() {
        var slots = slots();

        assertEquals(SaveSlots.COUNT, slots.saveMenu().getItemCount());
        assertEquals(SaveSlots.COUNT, slots.loadMenu().getItemCount());
        assertEquals("Slot 1", slots.saveMenu().getItem(0).getText());
        assertEquals("Slot 9", slots.saveMenu().getItem(SaveSlots.COUNT - 1).getText());
    }

    @Test
    void aSlotWithNothingInItIsGreyedOutAndSaysOnlyItsNumber() {
        var slots = slots();
        var menu = slots.loadMenu();

        slots.describe(directory.resolve("game.nes"));

        for (var slot = 1; slot <= SaveSlots.COUNT; slot++) {
            var item = menu.getItem(slot - 1);

            assertEquals("Slot " + slot, item.getText());
            assertFalse(item.isEnabled(), "slot " + slot + " has nothing in it");
        }
    }

    @Test
    void aSlotWithAStateInItSaysWhichFrameItStopped() throws IOException {
        var game = directory.resolve("game.nes");
        var slots = slots();
        var menu = slots.loadMenu();

        SaveState.write(machine(), SaveState.slotPath(game, 4));

        slots.describe(game);

        var item = menu.getItem(3);

        assertTrue(item.isEnabled());
        assertTrue(item.getText().startsWith("Slot 4 — frame 0, "), item.getText());
        assertFalse(menu.getItem(2).isEnabled(), "and the ones either side are still empty");
        assertFalse(menu.getItem(4).isEnabled());
    }

    /**
     * Still listed rather than greyed, because refusing to show it would hide the only clue that
     * something is wrong with the file.
     */
    @Test
    void aStateThatWillNotGiveUpItsHeaderIsStillOffered() throws IOException {
        var game = directory.resolve("game.nes");
        var slots = slots();
        var menu = slots.loadMenu();

        Files.write(SaveState.slotPath(game, 1), "not a save state".getBytes());

        slots.describe(game);

        assertEquals("Slot 1 — unreadable", menu.getItem(0).getText());
        assertTrue(menu.getItem(0).isEnabled());
    }

    /**
     * The window holds nine slots before anything has been opened, and the menu can still be pulled
     * down. There is no game for them to be slots of, so all nine say so.
     */
    @Test
    void withNoCartridgeLoadedEverySlotIsEmpty() {
        var slots = slots();
        var menu = slots.loadMenu();

        slots.describe(null);

        for (var slot = 1; slot <= SaveSlots.COUNT; slot++) {
            assertFalse(menu.getItem(slot - 1).isEnabled());
        }
    }

    @Test
    void theQuickItemsSayWhichSlotTheyWouldUse() {
        var slots = slots();

        assertEquals("Quick Save (Slot 1)", slots.quickSaveItem().getText());
        assertEquals("Quick Load (Slot 1)", slots.quickLoadItem().getText());
    }

    /**
     * The pair of keys and the two menus are one setting rather than two, so picking a slot from
     * either submenu is what moves them -- including from Save State, which is the half somebody
     * would expect to be about saving alone.
     */
    @Test
    void pickingASlotFromEitherSubmenuMovesBothQuickKeysOntoIt() {
        var slots = slots();

        click(slots.loadMenu(), 6);

        assertEquals("Quick Save (Slot 7)", slots.quickSaveItem().getText());
        assertEquals("Quick Load (Slot 7)", slots.quickLoadItem().getText());

        click(slots.saveMenu(), 2);

        assertEquals("Quick Save (Slot 3)", slots.quickSaveItem().getText());
        assertEquals("Quick Load (Slot 3)", slots.quickLoadItem().getText());
    }

    @Test
    void theQuickItemsAndTheSubmenusReachTheSameTwoActions() {
        var slots = slots();

        click(slots.saveMenu(), 4);
        click(slots.loadMenu(), 1);

        slots.quickSaveItem().doClick();
        slots.quickLoadItem().doClick();

        assertEquals(List.of(5, 2), saved, "slot 5 from the menu, then slot 2 from the quick key");
        assertEquals(List.of(2, 2), loaded);
    }

    private static void click(final JMenu menu, final int index) {
        menu.getItem(index).doClick();
    }

    private static NES machine() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return new NES(Cart.load(image, "game.nes"));
    }
}
