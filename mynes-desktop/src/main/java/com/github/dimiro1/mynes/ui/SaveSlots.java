package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.state.SaveState;
import com.github.dimiro1.mynes.state.SaveStateException;
import org.jetbrains.annotations.Nullable;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.KeyStroke;
import java.awt.event.KeyEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.IntConsumer;

/**
 * The nine save state slots: the two submenus, the two quick items, and which slot they mean.
 * <p>
 * <b>What is really here is the labelling.</b> Writing and reading a slot belongs to the window,
 * which owns the machine and the thread allowed to touch it, so both are handed in as callbacks.
 * What this owns is the part with a decision in it: going to the disk when the menu opens, reading
 * each state's header, and saying what is in every slot. That is what makes nine numbered slots
 * usable instead of a guessing game, and it is why the state's header is deliberately not
 * compressed -- nine files give up their frame number without any of them being inflated.
 * <p>
 * <b>Which slot is current is one setting rather than two.</b> Picking Slot 4 from either submenu
 * moves the pair of quick keys onto slot 4, so the menus and {@code F5}/{@code F7} can never come
 * to mean different things.
 * <p>
 * The labels are worked out when the menu is pulled down rather than when it is built, because what
 * is on the disk moves without anybody telling the emulator: a slot can be written by another copy
 * of this program, or deleted in a file manager, while the menu sits there built.
 */
final class SaveSlots {

    /**
     * How many save state slots there are. Nine because that is how many fit on the number row, and
     * because a tenth would be the one nobody could remember what they put in.
     */
    static final int COUNT = 9;

    /**
     * How a slot's time is put on its label. Short: the item already carries a slot number and a
     * frame count, and a full date would push the useful half off the edge of a menu.
     */
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("d MMM HH:mm");

    private final JMenuItem[] loadItems = new JMenuItem[COUNT];
    private final JMenuItem quickSave = new JMenuItem("Quick Save");
    private final JMenuItem quickLoad = new JMenuItem("Quick Load");

    private final IntConsumer save;
    private final IntConsumer load;

    /**
     * Which slot the two quick items use. Whichever was last picked from either submenu, so the
     * pair of keys and the menus are one setting rather than two.
     */
    private int current = 1;

    SaveSlots(final IntConsumer save, final IntConsumer load) {
        this.save = save;
        this.load = load;

        // Function keys, for three reasons. They sit in the same physical place on every keyboard
        // layout, which a letter does not -- this one is Colemak-DH. F5 and F7 are what ZSNES and
        // SNES9x used, so they are the keys a player already has in their fingers. And they need no
        // modifier, which matters here: Shift is bound to Select, so KeyboardInput deliberately does
        // not treat it as a shortcut modifier and any Shift+key shortcut would be a hazard.
        quickSave.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0));
        quickLoad.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F7, 0));

        quickSave.addActionListener(e -> save.accept(current));
        quickLoad.addActionListener(e -> load.accept(current));

        describeQuickItems();
    }

    /**
     * The Save State submenu, whose nine items never change their labels: every slot can be written
     * to, so there is nothing about one worth saying beyond its number.
     */
    JMenu saveMenu() {
        return menu("Save State", save, false);
    }

    /**
     * The Load State submenu, whose nine items are relabelled by {@link #describe} with what is in
     * them.
     */
    JMenu loadMenu() {
        return menu("Load State", load, true);
    }

    JMenuItem quickSaveItem() {
        return quickSave;
    }

    JMenuItem quickLoadItem() {
        return quickLoad;
    }

    /**
     * Puts what is in each slot onto its menu item, and greys out the empty ones.
     *
     * @param gamePath the cartridge the slots belong to, or null when nothing is loaded -- which
     *                 greys all nine, since there is no game for them to be slots of.
     */
    void describe(final @Nullable Path gamePath) {
        describeQuickItems();

        for (var slot = 1; slot <= COUNT; slot++) {
            var item = loadItems[slot - 1];
            var path = gamePath == null ? null : SaveState.slotPath(gamePath, slot);

            if (path == null || !Files.exists(path)) {
                item.setText("Slot " + slot);
                item.setEnabled(false);
                continue;
            }

            item.setEnabled(true);
            item.setText("Slot " + slot + " — " + describeState(path));
        }
    }

    /**
     * What one slot's file has in it, as it goes on the label.
     * <p>
     * A file that will not even give up its header is still offered rather than greyed, because
     * refusing to list it would hide the only clue that something is wrong with it.
     */
    private static String describeState(final Path path) {
        try {
            var header = SaveState.header(path);
            var when = Files.getLastModifiedTime(path).toInstant()
                    .atZone(ZoneId.systemDefault())
                    .format(WHEN);

            return "frame " + header.frame() + ", " + when;
        } catch (IOException | SaveStateException ex) {
            return "unreadable";
        }
    }

    private void describeQuickItems() {
        quickSave.setText("Quick Save (Slot " + current + ")");
        quickLoad.setText("Quick Load (Slot " + current + ")");
    }

    /**
     * Builds one of the two submenus, nine items numbered from one.
     *
     * @param keep whether to hold on to the items for {@link #describe} to relabel later.
     */
    private JMenu menu(final String title, final IntConsumer action, final boolean keep) {
        var menu = new JMenu(title);

        for (var slot = 1; slot <= COUNT; slot++) {
            var item = new JMenuItem("Slot " + slot);
            var chosen = slot;

            // No accelerators on these eighteen. Command-1 to Command-9 is "switch tab" everywhere
            // else, and eighteen global shortcuts for something two keys already do would be
            // eighteen chances to collide with a game's controls.
            item.addActionListener(e -> {
                current = chosen;
                action.accept(chosen);
                describeQuickItems();
            });

            if (keep) {
                loadItems[slot - 1] = item;
            }

            menu.add(item);
        }

        return menu;
    }
}
