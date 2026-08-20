package com.github.dimiro1.mynes.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A cartridge with all sixty four sprites on one scanline, assembled here rather than vendored.
 * <p>
 * It exists because no real game would do this. The hardware draws eight sprites a scanline and
 * every cartridge is written around that, so the overflow a test of the sprite limit needs is
 * exactly what a shipped game spends its effort avoiding -- Punch-Out!!'s first fight peaks at
 * seven sprites on a line and Battletoads' first level at eight. Nothing in a ROM collection
 * reliably overflows anywhere a test can reach.
 * <p>
 * Built rather than vendored because a generator is cheaper to keep than a binary: forty lines that
 * say what they do, against twenty four kilobytes nobody can read, with no question about where it
 * came from or who owns it. It is also the only way this file can stay honest -- {@code mvn test}
 * assembles and runs it, so a mistake in it fails the build rather than sitting there.
 * <p>
 * What it draws is one row of sixty four solid blocks four pixels apart, cycling the four sprite
 * palettes so that the individual sprites can be counted. On hardware the row is
 * {@value #DRAWN_WIDTH_WITH_THE_LIMIT} pixels wide, because only the first eight are drawn. With
 * the limit lifted it crosses the screen.
 * <p>
 * <strong>This is the assembler.</strong> The {@code .s} beside the cartridge is the same program
 * written for asm6, and it is there to be read and changed; nothing assembles it, and the build
 * needs no assembler because of that. So a change to the program is made here, and the file it
 * produces is rewritten with {@link #main}:
 * <pre>
 * mvn -q -pl mynes-headless -am test-compile
 * java -cp mynes-headless/target/test-classes \
 *     com.github.dimiro1.mynes.headless.SpriteLimitROM \
 *     mynes-headless/src/test/resources/sprite-limit/sprite-limit.nes
 * </pre>
 * Nothing outside the JDK is on that class path, which is the point: regenerating the cartridge
 * costs a compile and nothing else.
 *
 * @see <a href="https://www.nesdev.org/wiki/Init_code">NESdev: init code</a>
 * @see <a href="https://www.nesdev.org/wiki/PPU_sprite_evaluation">NESdev: sprite evaluation</a>
 */
final class SpriteLimitROM {
    /**
     * Where the one program bank lands. An NROM cartridge with a single 16KB bank mirrors it into
     * both halves of $8000-$FFFF, and the 6502 reads its vectors from the top of memory, so this is
     * the address the code below has to be assembled for.
     */
    private static final int PRG_BASE = 0xC000;

    /**
     * Where {@link #PALETTE} sits inside that bank. Far enough past the code that the two cannot
     * meet, and on a page boundary so the address in the load instruction reads as itself.
     */
    private static final int PALETTE_AT = 0xC100;

    /**
     * The scanline every sprite is given as its Y coordinate. A sprite is drawn on the line
     * <em>below</em> the one it names, so the row to look at is one more than this.
     */
    static final int SPRITE_Y = 100;

    /**
     * How far apart they are put, in pixels. Four, so that all sixty four fit across a 256 pixel
     * line with room to spare, and so that each one still shows past the one before it.
     */
    static final int SPRITE_SPACING = 4;

    static final int SPRITES = 64;

    /**
     * How wide the row comes out when the hardware's limit applies: the first eight sprites, each
     * eight pixels wide and four pixels along from the one before, which is 7 * 4 + 8.
     */
    static final int DRAWN_WIDTH_WITH_THE_LIMIT = 36;

    /**
     * Thirty two bytes of palette RAM, copied in wholesale. Only the sprite half matters: colour 1
     * of each of the four palettes is a colour of its own, so that a row of blocks cycling through
     * them can be told apart from one long block.
     */
    private static final int[] PALETTE = {
            0x0F, 0x00, 0x10, 0x30,
            0x0F, 0x00, 0x10, 0x30,
            0x0F, 0x00, 0x10, 0x30,
            0x0F, 0x00, 0x10, 0x30,
            0x0F, 0x16, 0x27, 0x18,
            0x0F, 0x1A, 0x2A, 0x3A,
            0x0F, 0x12, 0x22, 0x32,
            0x0F, 0x14, 0x24, 0x34,
    };

    private SpriteLimitROM() {
    }

    /**
     * The whole .nes file: a sixteen byte header, one 16KB program bank and one 8KB character bank.
     */
    static byte[] image() {
        var image = new byte[16 + 0x4000 + 0x2000];

        // "NES", the end-of-file byte the format is named after, then one bank of each. Everything
        // after that is zero, which is mapper 0, horizontal mirroring, no battery and no trainer.
        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        var program = program();
        System.arraycopy(program, 0, image, 16, program.length);

        // Tile 1, eight rows of the low bit plane switched on and the high one left off, which is
        // colour 1 of whichever palette the sprite's attribute byte names.
        for (var row = 0; row < 8; row++) {
            image[16 + 0x4000 + 0x10 + row] = (byte) 0xFF;
        }

        return image;
    }

    /**
     * Writes it somewhere.
     */
    static Path writeTo(final Path path) throws IOException {
        return Files.write(path, image());
    }

    /**
     * Rewrites the checked-in cartridge, which is the only reason a test fixture has a main.
     * <p>
     * The alternative is a note in a comment saying to run something that cannot be run, which is
     * what this replaced: {@code SpriteLimitTests} compares the file against {@link #image()} and
     * has to be able to say what to do when they disagree.
     *
     * @param args where to write it. The class Javadoc has the whole command.
     */
    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: SpriteLimitROM <path to sprite-limit.nes>");
            System.exit(2);
        }

        System.out.println("wrote " + writeTo(Path.of(args[0])) + ", " + image().length + " bytes");
    }

    /**
     * The program bank.
     * <p>
     * Hand assembled, with the disassembly beside each instruction. The four branch offsets are the
     * only numbers here that cannot be read off the source: each is counted in bytes from the
     * instruction <em>after</em> the branch, so inserting anything inside one of these loops means
     * counting it again. The jump at the end is not one of them -- it lands on itself, and where
     * that is falls out of how long everything before it turned out to be.
     */
    private static byte[] program() {
        var body = new int[]{
                0x78,                          // SEI
                0xD8,                          // CLD
                0xA2, 0x40,                    // LDX #$40
                0x8E, 0x17, 0x40,              // STX $4017    no APU frame interrupt
                0xA2, 0xFF,                    // LDX #$FF
                0x9A,                          // TXS
                0xE8,                          // INX          X = 0 from here down
                0x8E, 0x00, 0x20,              // STX $2000
                0x8E, 0x01, 0x20,              // STX $2001
                0x8E, 0x10, 0x40,              // STX $4010

                // The two VBlanks every cartridge waits for: the PPU ignores $2000, $2001, $2005
                // and $2006 until the beam first reaches the pre-render line, so anything written
                // before this would be dropped.
                0x2C, 0x02, 0x20,              // BIT $2002    first
                0x10, 0xFB,                    // BPL -5
                0x2C, 0x02, 0x20,              // BIT $2002    second
                0x10, 0xFB,                    // BPL -5

                0xA9, 0x3F,                    // LDA #$3F     point $2007 at palette RAM
                0x8D, 0x06, 0x20,              // STA $2006
                0xA9, 0x00,                    // LDA #$00
                0x8D, 0x06, 0x20,              // STA $2006
                0xA2, 0x00,                    // LDX #$00
                0xBD, PALETTE_AT & 0xFF, PALETTE_AT >> 8,
                                               // LDA PALETTE,X
                0x8D, 0x07, 0x20,              // STA $2007
                0xE8,                          // INX
                0xE0, PALETTE.length,          // CPX #32
                0xD0, 0xF5,                    // BNE -11

                0xA9, 0x00,                    // LDA #$00
                0x8D, 0x03, 0x20,              // STA $2003    OAMADDR at the start of OAM
                0x85, 0x10,                    // STA $10      and the X coordinate at the left
                0xA2, 0x00,                    // LDX #$00

                // Sixty four sprites, written a byte at a time through $2004 rather than by DMA,
                // because the address walks itself and there is nothing here in a hurry.
                0xA9, SPRITE_Y,                // LDA #100     the same line for every one of them
                0x8D, 0x04, 0x20,              // STA $2004
                0xA9, 0x01,                    // LDA #$01     tile 1, the solid block
                0x8D, 0x04, 0x20,              // STA $2004
                0x8A,                          // TXA
                0x29, 0x03,                    // AND #$03     a different palette each time round
                0x8D, 0x04, 0x20,              // STA $2004
                0xA5, 0x10,                    // LDA $10
                0x8D, 0x04, 0x20,              // STA $2004    X
                0x18,                          // CLC
                0x69, SPRITE_SPACING,          // ADC #4
                0x85, 0x10,                    // STA $10
                0xE8,                          // INX
                0xE0, SPRITES,                 // CPX #64
                0xD0, 0xE1,                    // BNE -31

                0xA9, 0x14,                    // LDA #$14     sprites on, left column included
                0x8D, 0x01, 0x20,              // STA $2001
        };

        var bank = new byte[0x4000];

        for (var i = 0; i < body.length; i++) {
            bank[i] = (byte) body[i];
        }

        // Where the jump that follows the body sits, and equally where it goes: there is nothing
        // left to do, and a 6502 with nothing to do has to be given somewhere to do it.
        var forever = PRG_BASE + body.length;

        bank[body.length] = (byte) 0x4C;                  // JMP forever
        bank[body.length + 1] = (byte) forever;
        bank[body.length + 2] = (byte) (forever >> 8);

        for (var i = 0; i < PALETTE.length; i++) {
            bank[PALETTE_AT - PRG_BASE + i] = (byte) PALETTE[i];
        }

        // The three vectors, at the top of the bank. NMI is never enabled and IRQ never fires, so
        // both of those point at the same loop the program ends in.
        var vectors = 0x4000 - 6;

        bank[vectors] = (byte) forever;
        bank[vectors + 1] = (byte) (forever >> 8);
        bank[vectors + 2] = (byte) PRG_BASE;
        bank[vectors + 3] = (byte) (PRG_BASE >> 8);
        bank[vectors + 4] = (byte) forever;
        bank[vectors + 5] = (byte) (forever >> 8);

        return bank;
    }
}
