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

    /**
     * What nestest's NROM board carries, which is also what every board without bank switching at
     * $6000 carries.
     */
    private static final int BYTES = 0x2000;

    @TempDir
    private Path directory;

    @Test
    void theFileIsExactlyTheBytesOfTheChipAndNothingElse() throws IOException {
        var nes = load();
        var path = directory.resolve("game.sav");

        fill(nes, 0x11);

        assertEquals(BYTES, BatteryRAM.write(nes, path));
        assertEquals(BYTES, Files.size(path), "no header, no trailer");

        var bytes = Files.readAllBytes(path);

        assertEquals(0x11, Byte.toUnsignedInt(bytes[0]), "the first byte of the file is $6000");
        assertEquals(0x11, Byte.toUnsignedInt(bytes[BYTES - 1]), "and the last is $7FFF");
    }

    @Test
    void whatWasWrittenToTheChipIsWhatComesBackIntoIt() throws IOException {
        var written = load();
        var path = directory.resolve("game.sav");

        for (var i = 0; i < BYTES; i++) {
            written.getBus().getMapper().prgRAMWrite(0x6000 + i, i * 7 + 1);
        }

        var before = written.getBus().getMapper().prgRAM().clone();
        BatteryRAM.write(written, path);

        var read = load();

        assertEquals(BYTES, BatteryRAM.read(read, path));
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

        assertEquals(BYTES, BatteryRAM.read(nes, path));
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

        assertEquals(BYTES, Files.size(path));
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

    /**
     * A board that banks the window writes the whole chip rather than the window, since the battery
     * is soldered to all of it. This is what FCEUX writes for the same board, and the lenient reader
     * above is what makes a file from an emulator that only kept one bank of it still load.
     */
    @Test
    void aBoardWithMoreRAMWritesAllOfIt() throws IOException {
        var nes = new NES(Cart.load(sxrom(), "sxrom.nes"));
        var path = directory.resolve("sxrom.sav");

        assertTrue(BatteryRAM.isWorthSaving(nes));
        fill(nes, 0x66);

        assertEquals(0x8000, BatteryRAM.write(nes, path));
        assertEquals(0x8000, Files.size(path));

        var read = new NES(Cart.load(sxrom(), "sxrom.nes"));

        assertEquals(0x8000, BatteryRAM.read(read, path));
        assertArrayEquals(nes.getBus().getMapper().prgRAM(), read.getBus().getMapper().prgRAM());
    }

    /**
     * A NES 2.0 header for an MMC1 board with a battery and 32KB of PRG NVRAM behind it, and one
     * bank of PRG ROM after it.
     */
    private static byte[] sxrom() {
        var rom = new byte[16 + 0x4000];

        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1;
        rom[6] = 0x12;         // mapper 1, battery
        rom[7] = 0x08;         // NES 2.0
        rom[10] = (byte) 0x90; // 64 << 9 bytes of PRG NVRAM

        return rom;
    }

    private NES load() throws IOException {
        return new NES(Cart.load(Files.readAllBytes(Path.of(ROM)), ROM));
    }

    private static void fill(final NES nes, final int value) {
        Arrays.fill(nes.getBus().getMapper().prgRAM(), (byte) value);
    }
}
