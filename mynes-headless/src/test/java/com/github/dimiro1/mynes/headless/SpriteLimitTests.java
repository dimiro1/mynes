package com.github.dimiro1.mynes.headless;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * What {@code --hack unlimited-sprites} does to the picture, from a command line to a PNG.
 * <p>
 * The core's own tests pin the behaviour far more precisely than this does, by driving the PPU
 * through its registers and reading the framebuffer. What they cannot say is that the flag on the
 * command line reaches those pixels: that is several classes away from the chip, and the report
 * only proves the flag reached the <em>machine</em>. So this runs the whole thing twice over
 * {@link SpriteLimitROM}'s cartridge and counts what came out.
 */
class SpriteLimitTests {
    private static final String ROM = "src/test/resources/sprite-limit/sprite-limit.nes";

    /**
     * The row the sprites land on, which is one below the coordinate they were given.
     */
    private static final int ROW = SpriteLimitROM.SPRITE_Y + 1;

    /**
     * How tall a sprite is, and so how many rows the change is allowed to touch.
     */
    private static final int SPRITE_HEIGHT = 8;

    @TempDir
    private Path directory;

    /**
     * The cartridge on disk and the generator beside it cannot drift apart, which is the whole
     * reason it is safe to have both. Without this, a change to the assembly would leave a .nes
     * nobody could account for, and the comments in {@link SpriteLimitROM} would be describing a
     * file that no longer matched them.
     */
    @Test
    void theCheckedInCartridgeIsExactlyWhatTheGeneratorProduces() throws Exception {
        assertArrayEquals(
                SpriteLimitROM.image(),
                Files.readAllBytes(Path.of(ROM)),
                "the cartridge and the code that describes it have come apart. To rewrite it:\n"
                        + "  mvn -q -pl mynes-headless -am test-compile\n"
                        + "  java -cp mynes-headless/target/test-classes "
                        + SpriteLimitROM.class.getName() + " \\\n"
                        + "      mynes-headless/" + ROM + "\n"
                        + "and change sprite-limit.s to match, since nothing checks that one");
    }

    @Test
    void theHardwareLimitCutsTheRowToItsFirstEightSprites() throws Exception {
        var picture = run();

        assertEquals(
                SpriteLimitROM.DRAWN_WIDTH_WITH_THE_LIMIT,
                drawnWidth(picture),
                "eight sprites four pixels apart, the last of them eight pixels wide");
    }

    @Test
    void liftingItDrawsAllSixtyFourRightAcrossTheLine() throws Exception {
        var picture = run("--hack", "unlimited-sprites");

        assertEquals(
                picture.getWidth(),
                drawnWidth(picture),
                "sixty four of them reach further than the beam does");
    }

    /**
     * The hack adds sprites and does nothing else. Anything appearing on another row would mean it
     * had found sprites the evaluation never looked at, or drawn them on the wrong line.
     */
    @Test
    void nothingOutsideThatRowOfSpritesChangesAtAll() throws Exception {
        var without = run();
        var with = run("--hack", "unlimited-sprites");

        for (var y = 0; y < without.getHeight(); y++) {
            if (y >= ROW && y < ROW + SPRITE_HEIGHT) {
                continue;
            }

            for (var x = 0; x < without.getWidth(); x++) {
                assertEquals(without.getRGB(x, y), with.getRGB(x, y),
                        "the picture differs at " + x + "," + y);
            }
        }
    }

    /**
     * How many pixels of {@link #ROW} are not the backdrop.
     * <p>
     * Measured against the corner of the picture rather than against a colour written down here,
     * since which RGB a palette entry comes out as is a property of the palette rather than of the
     * emulator, and this test has no opinion about televisions.
     */
    private static int drawnWidth(final BufferedImage picture) {
        var backdrop = picture.getRGB(0, 0);
        var drawn = 0;

        for (var x = 0; x < picture.getWidth(); x++) {
            if (picture.getRGB(x, ROW) != backdrop) {
                drawn++;
            }
        }

        return drawn;
    }

    /**
     * Runs the cartridge and hands back the last frame.
     * <p>
     * {@code --full-frame} so that {@link #ROW} means the scanline it says: the crop a television
     * would apply takes eight lines off the top, and correcting for it here would be one more thing
     * to get wrong.
     */
    private BufferedImage run(final String... extra) throws IOException {
        var out = directory.resolve(extra.length == 0 ? "plain" : "hacked");

        var args = new String[extra.length + 10];

        args[0] = "--rom";
        args[1] = ROM;
        args[2] = "--out";
        args[3] = out.toString();
        args[4] = "--quiet";
        args[5] = "--frames";
        args[6] = "60";
        args[7] = "--screenshot";
        args[8] = "last";
        args[9] = "--full-frame";

        System.arraycopy(extra, 0, args, 10, extra.length);

        assertEquals(Headless.EXIT_OK, Headless.run(args));

        return ImageIO.read(out.resolve("frame-000060.png").toFile());
    }
}
