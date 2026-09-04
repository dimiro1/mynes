package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.archive.Archive;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A game somebody has opened: a cartridge, the file inside it when the cartridge was a zip, and the
 * IPS patch that was applied to it if there was one.
 * <p>
 * The pair rather than the cartridge alone, because a romhack is a different game from the ROM it
 * was cut against -- which is the same thing {@code GameUIFrame.gamePath()} says when it names a
 * hack's save states after the patch. An entry that remembered only the {@code .nes} would reopen
 * the original, silently, at the one moment somebody had asked for the hack by name.
 * <p>
 * The name inside the zip is remembered for the same reason. A zip holding one cartridge does not
 * need it, but a zip holding several was picked from a dialog once, and a menu item that asked again
 * every time would be a shortcut to the question rather than to the game. Written down whenever the
 * ROM was a zip rather than only when it was ambiguous, since which of those a file is can change
 * under the menu: one is a game plus a text file until somebody drops a second dump in beside it.
 * <p>
 * Both paths are made absolute and normalised on the way in, which is what lets two entries be
 * compared: the same cartridge reached as {@code roms/smb.nes} from one working directory and as
 * {@code /home/x/roms/smb.nes} from another is one game, and the Open Recent menu holds each game
 * once.
 *
 * @param entry the name of the file inside {@code rom}, or null when the ROM is the cartridge.
 */
public record RecentRom(Path rom, @Nullable String entry, @Nullable Path patch) {
    public RecentRom {
        rom = rom.toAbsolutePath().normalize();
        patch = patch == null ? null : patch.toAbsolutePath().normalize();
    }

    /**
     * How the menu spells this game: the file names, in the shape the title bar already uses for a
     * patched one, since the two are naming the same thing in the same window.
     * <p>
     * The name inside a zip in place of the zip's own, because that is the cartridge: a collection
     * whose archives are all called after the game reads the same either way, and one whose archives
     * are not is the case this is for.
     */
    public String label() {
        return (entry == null ? name(rom) : Archive.fileNameOf(entry))
                + (patch == null ? "" : " + " + name(patch));
    }

    /**
     * The whole of both paths, for the tooltip. The labels are file names, so two cartridges called
     * the same thing in two folders are one entry apiece and there would otherwise be nothing to
     * tell them apart.
     * <p>
     * One line, in the shape of the label, rather than one path per line: a Swing tooltip renders
     * plain text and would show the newline rather than break at it, and the way to make it break
     * is HTML -- which would mean escaping paths, and {@code <} and {@code &} are both characters a
     * filename is allowed to hold.
     */
    public String describe() {
        return rom + (entry == null ? "" : " > " + entry)
                + (patch == null ? "" : " + " + patch);
    }

    /**
     * Whether the files are still where they were left.
     * <p>
     * A patched game needs both: the patch is what makes it that game, and applying nothing to the
     * cartridge would quietly open the original.
     * <p>
     * A zip is only checked for being there, not for still holding the entry. Opening it is the only
     * way to find that out, and reading every archive on the list to build a menu would cost a
     * player with ten of them a disk full of work every time they pulled File down.
     */
    public boolean isThere() {
        return Files.isRegularFile(rom) && (patch == null || Files.isRegularFile(patch));
    }

    /**
     * A path has no file name only when it is a root, which no cartridge is -- but the list is read
     * from a file that is meant to be edited by hand, and a menu item labelled {@code null} is a
     * worse answer than one labelled {@code /}.
     */
    private static String name(final Path path) {
        var name = path.getFileName();

        return (name == null ? path : name).toString();
    }
}
