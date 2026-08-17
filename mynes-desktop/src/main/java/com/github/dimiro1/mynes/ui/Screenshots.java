package com.github.dimiro1.mynes.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Where a screenshot goes.
 * <p>
 * Beside the ROM and named after it, the way the save slots and the battery file are, so that
 * everything belonging to a game is in one place and a picture says which game it is a picture of.
 * Nothing is asked and no dialog opens, which is the point of a key somebody can hit in the middle
 * of a jump: a picture that took a file chooser to save would be a picture of the moment after the
 * one worth keeping.
 */
public final class Screenshots {
    /**
     * The time in the name, which is what makes one picture a different file from the next.
     * <p>
     * Written this way round so that the pictures of an evening sort into the order they were taken
     * in, by name, in any file manager. No colons: macOS would take them and Windows would not.
     */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private Screenshots() {
    }

    /**
     * A path nothing is using yet, for a picture of {@code rom} taken at {@code when}.
     *
     * @param rom  where the cartridge was loaded from. The picture lands next to it, under the same
     *             name with its extension replaced, the way {@code SaveState.slotPath} does it.
     * @param when what to stamp the name with, to the second.
     */
    public static Path pathFor(final Path rom, final LocalDateTime when) {
        var name = rom.getFileName().toString();
        var dot = name.lastIndexOf('.');
        var stem = (dot < 0 ? name : name.substring(0, dot)) + "-" + STAMP.format(when);

        var path = rom.resolveSibling(stem + ".png");

        // The stamp is only good to the second, and two presses inside one second are one held key
        // away. The loop ends at the first free name, and there is always one: the names go on
        // forever and the directory does not.
        for (var n = 2; Files.exists(path); n++) {
            path = rom.resolveSibling(stem + "-" + n + ".png");
        }

        return path;
    }
}
