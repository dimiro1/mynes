package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.state.BatteryRAM;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The rule the autosave exists for: write the {@code .sav} when the game has changed the chip, and
 * not otherwise.
 * <p>
 * Every test here is about a file that should <em>not</em> appear. That is the whole point of the
 * shadow copy -- a timer that wrote 8KB to disk every minute of every game, battery or not, would
 * pass any test asking whether saving works.
 * <p>
 * Nothing here touches a failure path, and deliberately: those put a dialog up, and a test that
 * raised one would hang the build on a machine with no display.
 */
class BatterySavesTests {

    @TempDir
    private Path directory;

    @Test
    void aCartridgeWithNoBatteryOnItIsNeverWrittenOut() {
        var nes = machine(false);
        var game = directory.resolve("scratch.nes");
        var saves = new BatterySaves(null);

        saves.track(nes);
        fill(nes, 0x5A);
        saves.autosave(nes, game);
        saves.save(nes, game);

        assertFalse(Files.exists(BatteryRAM.pathFor(game)),
                "every board here has RAM at $6000 whether a battery was wired to it or not");
    }

    /**
     * The first check after a game has loaded is the one most likely to be wrong: the RAM is full of
     * whatever came off the disk, which is a difference from nothing but not a difference the game
     * made.
     */
    @Test
    void theFirstCheckAfterLoadingDoesNotRewriteWhatWasJustRead() {
        var game = directory.resolve("game.nes");
        var sav = BatteryRAM.pathFor(game);

        var played = machine(true);
        fill(played, 0x11);
        new BatterySaves(null).save(played, game);

        var reloaded = machine(true);
        var saves = new BatterySaves(null);

        saves.load(reloaded, game);
        saves.track(reloaded);

        assertTrue(deleteSav(sav), "the save from the first session should be there");

        saves.autosave(reloaded, game);

        assertFalse(Files.exists(sav), "nothing has changed, so nothing should have been written");
    }

    @Test
    void anAutosaveWritesOnceTheGameHasChangedTheRAM() {
        var nes = machine(true);
        var game = directory.resolve("game.nes");
        var saves = new BatterySaves(null);

        saves.track(nes);
        fill(nes, 0x7E);
        saves.autosave(nes, game);

        assertTrue(Files.exists(BatteryRAM.pathFor(game)));
    }

    /**
     * The one that matters for a laptop's disk: a game that saved an hour ago is still not being
     * written every minute since.
     */
    @Test
    void anAutosaveAfterAnAutosaveWritesNothingUntilTheGameSavesAgain() {
        var nes = machine(true);
        var game = directory.resolve("game.nes");
        var sav = BatteryRAM.pathFor(game);
        var saves = new BatterySaves(null);

        saves.track(nes);
        fill(nes, 0x7E);
        saves.autosave(nes, game);

        assertTrue(deleteSav(sav), "the first autosave should have written one");

        saves.autosave(nes, game);

        assertFalse(Files.exists(sav), "the game has not saved again since");

        fill(nes, 0x7F);
        saves.autosave(nes, game);

        assertTrue(Files.exists(sav), "and now it has");
    }

    /**
     * Unlike the autosave, which is a timer nobody asked for. This one is the window closing and a
     * cartridge being swapped, and neither gets a second chance to be right.
     */
    @Test
    void anExplicitSaveWritesWhetherAnythingChangedOrNot() {
        var nes = machine(true);
        var game = directory.resolve("game.nes");
        var sav = BatteryRAM.pathFor(game);
        var saves = new BatterySaves(null);

        saves.track(nes);

        saves.save(nes, game);

        assertTrue(deleteSav(sav), "nothing had changed and it should still have been written");
    }

    @Test
    void whatOneSessionWroteIsWhatTheNextOneStartsHolding() {
        var game = directory.resolve("game.nes");

        var played = machine(true);
        fill(played, 0x3C);
        new BatterySaves(null).save(played, game);

        var reloaded = machine(true);
        new BatterySaves(null).load(reloaded, game);

        assertArrayEquals(ram(played), ram(reloaded));
    }

    /**
     * A game with nothing open yet, which is what the window holds before a cartridge is chosen. It
     * reaches all three of these through a timer that does not stop for it.
     */
    @Test
    void aWindowWithNoGameInItIsNotAskedToWriteOneOut() {
        var nes = machine(true);
        var saves = new BatterySaves(null);

        saves.load(nes, null);
        saves.autosave(nes, null);
        saves.save(nes, null);
        saves.autosave(null, directory.resolve("game.nes"));

        assertTrue(isEmpty(directory), "nothing should have been written anywhere");
    }

    private static byte[] ram(final NES nes) {
        return nes.getBus().getMapper().prgRAM();
    }

    private static void fill(final NES nes, final int value) {
        Arrays.fill(ram(nes), (byte) value);
    }

    /**
     * Deleting rather than reading a modification time, which has a resolution somewhere between a
     * nanosecond and two seconds depending on whose file system this is running on. A file that is
     * not there cannot have been written again by accident.
     *
     * @return whether it was there to delete.
     */
    private static boolean deleteSav(final Path sav) {
        try {
            return Files.deleteIfExists(sav);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static boolean isEmpty(final Path directory) {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /**
     * An NROM cartridge, with or without the flags 6 bit that says a battery was wired to the RAM.
     */
    private static NES machine(final boolean battery) {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;
        image[6] = (byte) (battery ? 0x02 : 0x00);

        // A reset vector that points at the start of the bank, so the machine is a machine rather
        // than one that fetches its first instruction from nowhere.
        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return new NES(Cart.load(image, battery ? "game.nes" : "scratch.nes"));
    }
}
