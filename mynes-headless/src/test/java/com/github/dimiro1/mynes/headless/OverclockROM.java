package com.github.dimiro1.mynes.headless;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * A cartridge whose game logic does not fit in a frame, assembled here rather than vendored.
 * <p>
 * It exists because {@code --hack overclock} cannot be demonstrated on a game that keeps up. What
 * the hack undoes is a main loop overrunning its frame -- the next NMI arrives with the last frame's
 * work unfinished, the game skips a turn, and the picture stutters. Super Mario Bros. 3 and Gradius
 * do it under load, but only under load, in places a test cannot reliably reach and only for a
 * handful of frames at a time. So this does it on purpose and does it every time.
 * <p>
 * The program is a game with one job: count how many times it can get through a fixed pile of work.
 * The pile is about 42500 cycles, which is 1.43 NTSC frames -- so on the hardware it finishes one
 * iteration every <em>two</em> frames, because the wait at the end of one always ends on an NMI and
 * the loop is phase-locked to them. Give it 131 extra scanlines a frame and the frame becomes 44671
 * cycles, the pile fits, and it manages one iteration per frame. Give it 66 -- half as many -- and
 * the frame is 37282 cycles, which is not enough, and it is back to one every two.
 * <p>
 * Two counters in zero page say so, both sixteen bit and little endian, and {@code --dump ram} is
 * how to read them:
 * <ul>
 *   <li>{@code $00-$01} frames, counted by the NMI handler.</li>
 *   <li>{@code $02-$03} iterations, counted by the main loop.</li>
 * </ul>
 * The screen says the same thing without a debugger. Rendering is never switched on, so the whole
 * picture is the backdrop -- and with rendering off the backdrop is read from wherever the VRAM
 * address happens to point rather than from $3F00, which is the "background palette hack" real games
 * use to flash the screen. So the NMI leaves the address at {@code $3F00 + (iterations & 7)} and the
 * screen changes colour once per finished iteration, with no $2007 write to undo. A report's
 * {@code video.frameChanges} then counts iterations for free.
 * <p>
 * <strong>This is the assembler.</strong> The {@code .s} beside the cartridge is the same program
 * written for asm6, and it is there to be read and changed; nothing assembles it, and the build
 * needs no assembler because of that. So a change to the program is made here, and the file it
 * produces is rewritten with {@link #main}:
 * <pre>
 * mvn -q -pl mynes-headless -am test-compile
 * java -cp mynes-headless/target/test-classes \
 *     com.github.dimiro1.mynes.headless.OverclockROM \
 *     mynes-headless/src/test/resources/overclock/overclock.nes
 * </pre>
 * Nothing outside the JDK is on that class path, which is the point: regenerating the cartridge
 * costs a compile and nothing else.
 *
 * @see <a href="https://www.nesdev.org/wiki/Init_code">NESdev: init code</a>
 * @see <a href="https://www.nesdev.org/wiki/PPU_palettes">NESdev: PPU palettes</a>
 */
final class OverclockROM {
    /**
     * Where the one program bank lands. An NROM cartridge with a single 16KB bank mirrors it into
     * both halves of $8000-$FFFF, and the 6502 reads its vectors from the top of memory, so this is
     * the address the code below has to be assembled for.
     */
    private static final int PRG_BASE = 0xC000;

    /**
     * Where {@link #PALETTE} sits inside that bank, and where the NMI handler sits after it. Both on
     * round boundaries well past the end of the code, so the addresses in the instructions below
     * read as themselves and so that nothing here can grow into anything else.
     */
    private static final int PALETTE_AT = 0xC100;
    private static final int NMI_AT = 0xC200;

    /**
     * A lone {@code RTI}, which the IRQ vector points at.
     * <p>
     * Nothing can reach it: the program starts with {@code SEI} and switches the APU's frame
     * interrupt off, and the cartridge is NROM and has no interrupt of its own. It is here so that
     * an interrupt nobody can explain returns instead of running the NMI handler and counting a
     * frame that did not happen.
     */
    private static final int IRQ_AT = NMI_AT - 1;

    /**
     * How many times round the outer delay loop, which is the whole of what makes the program too
     * slow. 33 comes to 42439 cycles, and the rest of an iteration brings it to about 42500 --
     * 1.43 NTSC frames, which is comfortably over one and comfortably under two.
     * <p>
     * Changing it changes what the cartridge demonstrates. Under 29780 cycles it keeps up on the
     * hardware and there is nothing to fix; over 59561 no overclock this side of the limit would let
     * it manage a frame.
     */
    static final int OUTER_ITERATIONS = 33;

    /**
     * Where the frame counter lives, and the iteration counter after it. Both sixteen bit and little
     * endian, so {@code $00-$01} and {@code $02-$03}.
     */
    static final int FRAMES_AT = 0x00;
    static final int ITERATIONS_AT = 0x02;

    /**
     * The flag the NMI raises and the main loop waits on: 1 when a frame has been drawn since the
     * loop last cleared it.
     */
    private static final int TICK_AT = 0x04;

    /**
     * Eight background colours, one per palette cell the NMI can point the VRAM address at. Distinct
     * on any television so that {@code video.topColours} in a report can be read, and so that
     * {@code frameChanges} counts every iteration -- consecutive values of {@code iterations & 7}
     * always differ, so the screen changes every time round.
     */
    private static final int[] PALETTE = {
            0x0F, 0x16, 0x2A, 0x12, 0x28, 0x24, 0x1C, 0x30,
    };

    private OverclockROM() {
    }

    /**
     * The whole .nes file: a sixteen byte header, one 16KB program bank and one 8KB character bank.
     * <p>
     * The character bank is empty and stays that way. Rendering is never switched on -- the picture
     * is the backdrop and nothing else -- so there is no tile to put in it; it is there because a
     * cartridge with no character bank at all is a cartridge with character RAM, which is a
     * different thing to have to explain.
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
     *
     * @param args where to write it. The class Javadoc has the whole command.
     */
    public static void main(final String[] args) throws IOException {
        if (args.length != 1) {
            System.err.println("usage: OverclockROM <path to overclock.nes>");
            System.exit(2);
        }

        System.out.println("wrote " + writeTo(Path.of(args[0])) + ", " + image().length + " bytes");
    }

    /**
     * The program bank.
     * <p>
     * Hand assembled, with the disassembly beside each instruction. The branch offsets are the only
     * numbers here that cannot be read off the source: each is counted in bytes from the instruction
     * <em>after</em> the branch, so inserting anything inside one of these loops means counting it
     * again. None of them crosses a page, which is load bearing rather than incidental -- a taken
     * branch that crossed one would cost an extra cycle every time round the inner loop, and the
     * whole point of this cartridge is how many cycles a lap takes.
     */
    private static byte[] program() {
        var setup = new int[]{
                0x78,                          // SEI
                0xD8,                          // CLD
                0xA2, 0x40,                    // LDX #$40
                0x8E, 0x17, 0x40,              // STX $4017    no APU frame interrupt
                0xA2, 0xFF,                    // LDX #$FF
                0x9A,                          // TXS
                0xE8,                          // INX          X = 0 from here down
                0x8E, 0x00, 0x20,              // STX $2000
                0x8E, 0x01, 0x20,              // STX $2001    rendering stays off for good
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
                0xE0, PALETTE.length,          // CPX #8
                0xD0, 0xF5,                    // BNE -11

                // Both counters and the flag between the two halves of the program.
                0xA9, 0x00,                    // LDA #$00
                0x85, FRAMES_AT,               // STA $00
                0x85, FRAMES_AT + 1,           // STA $01
                0x85, ITERATIONS_AT,           // STA $02
                0x85, ITERATIONS_AT + 1,       // STA $03
                0x85, TICK_AT,                 // STA $04

                0xA9, 0x80,                    // LDA #$80     NMI on; rendering is still off
                0x8D, 0x00, 0x20,              // STA $2000
        };

        // One lap of the game, in its own array so that the address the jump at the end goes back to
        // is where the setup happens to stop rather than a number counted by hand.
        //
        // The pile of work is a delay loop because what the work is does not matter -- only that it
        // is the same every lap and that it does not fit in a frame. 33 laps of 1283 cycles, plus
        // the branches, is 42439; the rest of the lap brings it to about 42500, and an NTSC frame is
        // 29780.
        var lap = new int[]{
                0xA0, OUTER_ITERATIONS,        // LDY #33      main:
                0xA2, 0x00,                    // LDX #$00     outer:
                0xCA,                          // DEX          inner:
                0xD0, 0xFD,                    // BNE -3       256 times round, 1279 cycles
                0x88,                          // DEY
                0xD0, 0xF8,                    // BNE -8

                0xE6, ITERATIONS_AT,           // INC $02      one more lap finished
                0xD0, 0x02,                    // BNE +2
                0xE6, ITERATIONS_AT + 1,       // INC $03

                // Wait for the next picture. Clearing the flag before waiting is what makes this a
                // wait for the *next* NMI rather than an acknowledgement of the last one -- and it
                // is why a lap takes a whole number of frames however long the work took.
                0xA9, 0x00,                    // LDA #$00
                0x85, TICK_AT,                 // STA $04
                0xA5, TICK_AT,                 // LDA $04      wait:
                0xF0, 0xFC,                    // BEQ -4
        };

        var bank = new byte[0x4000];

        for (var i = 0; i < setup.length; i++) {
            bank[i] = (byte) setup[i];
        }

        for (var i = 0; i < lap.length; i++) {
            bank[setup.length + i] = (byte) lap[i];
        }

        // Back to the top of the lap. Where the jump sits and where it goes both fall out of how
        // long the two arrays turned out to be, so nothing here has to be counted again when either
        // of them changes.
        var loopStart = PRG_BASE + setup.length;
        var jump = setup.length + lap.length;

        bank[jump] = (byte) 0x4C;                         // JMP main
        bank[jump + 1] = (byte) loopStart;
        bank[jump + 2] = (byte) (loopStart >> 8);

        for (var i = 0; i < PALETTE.length; i++) {
            bank[PALETTE_AT - PRG_BASE + i] = (byte) PALETTE[i];
        }

        bank[IRQ_AT - PRG_BASE] = (byte) 0x40;            // RTI

        var nmi = new int[]{
                0x48,                          // PHA          X and Y are the main loop's; A is not
                0xE6, FRAMES_AT,               // INC $00      one more frame
                0xD0, 0x02,                    // BNE +2
                0xE6, FRAMES_AT + 1,           // INC $01
                0xA9, 0x01,                    // LDA #$01
                0x85, TICK_AT,                 // STA $04      let the main loop go on

                // Leave the VRAM address inside palette RAM, at the cell this lap's number names.
                // With rendering off that cell *is* the backdrop, so the whole screen becomes that
                // colour and stays it until the next lap -- and nothing has to be written back.
                0x2C, 0x02, 0x20,              // BIT $2002    and put the $2006 latch back to first
                0xA9, 0x3F,                    // LDA #$3F
                0x8D, 0x06, 0x20,              // STA $2006
                0xA5, ITERATIONS_AT,           // LDA $02
                0x29, 0x07,                    // AND #$07
                0x8D, 0x06, 0x20,              // STA $2006

                0x68,                          // PLA
                0x40,                          // RTI          which puts the flags back too
        };

        for (var i = 0; i < nmi.length; i++) {
            bank[NMI_AT - PRG_BASE + i] = (byte) nmi[i];
        }

        // The three vectors, at the top of the bank.
        var vectors = 0x4000 - 6;

        bank[vectors] = (byte) NMI_AT;
        bank[vectors + 1] = (byte) (NMI_AT >> 8);
        bank[vectors + 2] = (byte) PRG_BASE;
        bank[vectors + 3] = (byte) (PRG_BASE >> 8);
        bank[vectors + 4] = (byte) IRQ_AT;
        bank[vectors + 5] = (byte) (IRQ_AT >> 8);

        return bank;
    }

}
