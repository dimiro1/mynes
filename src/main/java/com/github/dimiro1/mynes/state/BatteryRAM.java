package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.NES;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * The eight kilobytes a coin cell kept alive, as a {@code .sav} file.
 * <p>
 * This is the one part of saving a game that <strong>is</strong> standard, and it is standard by
 * being as close to nothing as a format can be: the raw contents of $6000-$7FFF, no magic number, no
 * version, no checksum, no header of any kind. FCEUX, Nestopia and Mesen all read and write exactly
 * this, which is why a save from any of them can be dropped next to the ROM here and simply work.
 * <p>
 * <strong>Nothing may be added to it.</strong> A magic number would be useful, a checksum would be
 * more useful still, and either one would break the only property the format has. Anything that needs
 * a header belongs in a {@link SaveState} instead.
 * <p>
 * Unlike a save state, this is the player's actual progress through the game -- fifty hours of Zelda
 * rather than a bookmark somebody can take again in a second. That is why it is written through a
 * temporary and a move, and why a file that is the wrong length is worked with rather than refused.
 */
public final class BatteryRAM {

    /**
     * What a board carries at $6000-$7FFF, and so how long the file is.
     */
    public static final int BYTES = 0x2000;

    private static final String EXTENSION = ".sav";

    private BatteryRAM() {
    }

    /**
     * Where the battery file for a ROM belongs: beside it, with the extension replaced rather than
     * appended.
     * <p>
     * Beside the ROM because that is the convention the interoperability rests on -- an emulator
     * handed a directory of games and saves should find them without being told where to look.
     */
    public static Path pathFor(final Path rom) {
        var name = rom.getFileName().toString();
        var dot = name.lastIndexOf('.');

        return rom.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + EXTENSION);
    }

    /**
     * Fills the cartridge's RAM from a file.
     * <p>
     * A file of the wrong length is used anyway, for the reason in the class comment: this is
     * somebody's progress, and refusing it outright to be tidy would be the wrong trade. A short one
     * fills what it can and leaves the rest as it was; a long one -- which is what an emulator
     * modelling a bigger board writes -- gives up its first eight kilobytes.
     *
     * @return how many bytes were taken from the file, or -1 if there was no file or the cartridge
     *         has no RAM to put it in.
     */
    public static int read(final NES nes, final Path path) throws IOException {
        var ram = nes.getBus().getMapper().prgRAM();

        if (ram.length == 0 || !Files.exists(path)) {
            return -1;
        }

        var bytes = Files.readAllBytes(path);
        var taken = Math.min(bytes.length, ram.length);

        System.arraycopy(bytes, 0, ram, 0, taken);

        return taken;
    }

    /**
     * Writes the cartridge's RAM out, and does not destroy what was there until it has worked.
     *
     * @return how many bytes were written, or -1 if the cartridge has no RAM and so nothing to save.
     */
    public static int write(final NES nes, final Path path) throws IOException {
        var ram = nes.getBus().getMapper().prgRAM();

        if (ram.length == 0) {
            return -1;
        }

        // Through a temporary and a move, because a crash halfway through overwriting a save file
        // would otherwise lose both the new progress and the old.
        var temporary = path.resolveSibling(path.getFileName() + ".tmp");

        Files.write(temporary, ram);
        Files.move(temporary, path,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);

        return ram.length;
    }

    /**
     * Whether this cartridge is one whose RAM a real console would have kept.
     * <p>
     * The window is asked for separately, because every board here has RAM fitted at $6000 whether or
     * not a battery was wired to it -- several of blargg's test ROMs report their results through it
     * -- and persisting scratch RAM would invent saves that never existed. The command line
     * deliberately does not ask: {@code --sram-out} is somebody being explicit.
     */
    public static boolean isWorthSaving(final NES nes) {
        return nes.getCart().hasBattery() && nes.getBus().getMapper().prgRAM().length > 0;
    }
}
