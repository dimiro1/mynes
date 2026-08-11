package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The battery file, which is the part of this that has to work with other people's emulators.
 * <p>
 * So the questions here are mostly about what is <em>not</em> in the file. Eight kilobytes and nothing
 * else: no magic number, no version, no length, no checksum. Every one of those would be an
 * improvement on its own terms and would break the only property the format has, which is that FCEUX
 * and Nestopia and Mesen can all read it.
 */
class BatteryRAMTests {
    private static final String ROM = "src/test/resources/nestest/nestest.nes";

    @TempDir
    private Path directory;

    @Test
    void theFileIsExactlyTheBytesOfTheChipAndNothingElse() throws IOException {
        var nes = load();
        var path = directory.resolve("game.sav");

        fill(nes, 0x11);

        assertEquals(BatteryRAM.BYTES, BatteryRAM.write(nes, path));
        assertEquals(BatteryRAM.BYTES, Files.size(path), "no header, no trailer");

        var bytes = Files.readAllBytes(path);

        assertEquals(0x11, Byte.toUnsignedInt(bytes[0]), "the first byte of the file is $6000");
        assertEquals(0x11, Byte.toUnsignedInt(bytes[BatteryRAM.BYTES - 1]), "and the last is $7FFF");
    }

    @Test
    void whatWasWrittenToTheChipIsWhatComesBackIntoIt() throws IOException {
        var written = load();
        var path = directory.resolve("game.sav");

        for (var i = 0; i < BatteryRAM.BYTES; i++) {
            written.getBus().getMapper().prgRAMWrite(0x6000 + i, i * 7 + 1);
        }

        var before = written.getBus().getMapper().prgRAM().clone();
        BatteryRAM.write(written, path);

        var read = load();

        assertEquals(BatteryRAM.BYTES, BatteryRAM.read(read, path));
        assertArrayEquals(before, read.getBus().getMapper().prgRAM());
    }

    /**
     * A file another emulator wrote is worked with rather than refused. This is somebody's progress
     * through a game, and being strict about the length would be the wrong trade.
     */
    @Test
    void aShorterFileFillsWhatItCanAndLeavesTheRestAlone() throws IOException {
        var nes = load();
        var path = directory.resolve("short.sav");

        fill(nes, 0x22);
        Files.write(path, new byte[]{1, 2, 3, 4});

        assertEquals(4, BatteryRAM.read(nes, path));

        var ram = nes.getBus().getMapper().prgRAM();

        assertArrayEquals(new byte[]{1, 2, 3, 4}, Arrays.copyOf(ram, 4));
        assertEquals(0x22, Byte.toUnsignedInt(ram[4]), "and the rest of the chip is untouched");
    }

    @Test
    void aLongerFileGivesUpItsFirstEightKilobytes() throws IOException {
        var nes = load();
        var path = directory.resolve("big.sav");
        var big = new byte[0x8000];

        Arrays.fill(big, (byte) 0x33);
        Files.write(path, big);

        assertEquals(BatteryRAM.BYTES, BatteryRAM.read(nes, path));
        assertEquals(0x33, Byte.toUnsignedInt(nes.getBus().getMapper().prgRAM()[0]));
    }

    @Test
    void aFileThatIsNotThereLeavesTheChipAlone() throws IOException {
        var nes = load();
        fill(nes, 0x44);

        assertEquals(-1, BatteryRAM.read(nes, directory.resolve("never-written.sav")));
        assertEquals(0x44, Byte.toUnsignedInt(nes.getBus().getMapper().prgRAM()[0]));
    }

    @Test
    void theFileIsNamedAfterTheRomWithTheExtensionReplaced() {
        assertEquals(
                Path.of("/games/Zelda.sav"),
                BatteryRAM.pathFor(Path.of("/games/Zelda.nes")),
                "beside the ROM, which is the convention the interoperability rests on");
        assertEquals(
                Path.of("/games/no-extension.sav"),
                BatteryRAM.pathFor(Path.of("/games/no-extension")));
        assertEquals(
                Path.of("/games/Mario Bros 3.sav"),
                BatteryRAM.pathFor(Path.of("/games/Mario Bros 3.nes")),
                "a name with dots and spaces in it keeps everything up to the last dot");
    }

    /**
     * Every board here has RAM fitted at $6000 whether or not a battery was wired to it -- several of
     * blargg's ROMs report their results through that window -- so the header is what decides whether
     * a real console would have kept it. Persisting scratch RAM would invent saves nobody made.
     */
    @Test
    void aCartridgeWithNoBatteryIsNotWorthSaving() throws IOException {
        var nes = load();

        assertFalse(nes.getCart().hasBattery(), "nestest has no battery");
        assertTrue(nes.getBus().getMapper().prgRAM().length > 0, "but it does have the RAM");
        assertFalse(BatteryRAM.isWorthSaving(nes));
    }

    /**
     * Overwriting has to leave nothing of the longer file that was there, which is the failure mode a
     * plain append or a partial write would have.
     */
    @Test
    void writingOverAnOlderSaveLeavesNoneOfItBehind() throws IOException {
        var nes = load();
        var path = directory.resolve("game.sav");

        Files.write(path, new byte[0x8000]);

        fill(nes, 0x55);
        BatteryRAM.write(nes, path);

        assertEquals(BatteryRAM.BYTES, Files.size(path));
    }

    @Test
    void theTemporaryFileDoesNotSurviveTheWrite() throws IOException {
        var nes = load();
        var path = directory.resolve("game.sav");

        BatteryRAM.write(nes, path);

        try (var files = Files.list(directory)) {
            assertEquals(
                    1,
                    files.count(),
                    "the save is written through a temporary, which must not be left lying about");
        }
    }

    private NES load() throws IOException {
        return new NES(Cart.load(Files.readAllBytes(Path.of(ROM)), ROM));
    }

    private static void fill(final NES nes, final int value) {
        Arrays.fill(nes.getBus().getMapper().prgRAM(), (byte) value);
    }
}
