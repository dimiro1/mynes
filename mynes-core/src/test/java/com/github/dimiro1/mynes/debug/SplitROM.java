package com.github.dimiro1.mynes.debug;

/**
 * A cartridge that splits the screen, assembled here.
 * <p>
 * Its own class rather than a method on one test, because two of them want it and it is the same
 * demonstration for both: the smallest cartridge that has an answer to "where in the frame did
 * that happen".
 * <p>
 * Its NMI does nothing but raise a flag; the main loop waits for it, counts out about 15400
 * cycles -- 135 scanlines, so a little over a hundred lines into the next picture -- and writes
 * the scroll twice, which is what a game does to put a status bar over a scrolling level. Then
 * it writes to work RAM, which is there to be ignored.
 * <pre>
 * C000  SEI / CLD / LDX #$FF / TXS
 *       LDA #$00 / STA $2001 / STA $01     rendering off, the NMI's flag cleared
 * C00C  BIT $2002 / BPL -5                 two vblanks, which is how one waits for the PPU
 * C011  BIT $2002 / BPL -5
 *       LDA #$80 / STA $2000               and only then the NMI
 * C01B  LDA $01 / BEQ -4             loop: wait for it
 *       LDA #$00 / STA $01
 * C023  LDY #$0C / LDX #$00 / DEX / BNE -3 / DEY / BNE -8
 *       LDA #$40 / STA $2005 / STA $2005   the split
 *       STA $0200                          and one write to work RAM, to be ignored
 * C038  JMP $C01B
 *
 * C100  INC $01 / RTI
 * </pre>
 * The interrupt rather than a $2002 poll for the same reason the pads cartridge uses one: a
 * program that polls the flag can have a read land on the dot it is raised, lose that vblank
 * and slip a frame, which would put the split on a different line every so often.
 */
final class SplitROM {
    private SplitROM() {
    }

    static byte[] image() {
        var program = new int[]{
                0x78, 0xD8, 0xA2, 0xFF, 0x9A,
                0xA9, 0x00, 0x8D, 0x01, 0x20,
                0x85, 0x01,
                0x2C, 0x02, 0x20, 0x10, 0xFB,
                0x2C, 0x02, 0x20, 0x10, 0xFB,
                0xA9, 0x80, 0x8D, 0x00, 0x20,
                0xA5, 0x01, 0xF0, 0xFC,
                0xA9, 0x00, 0x85, 0x01,
                0xA0, 0x0C,
                0xA2, 0x00,
                0xCA, 0xD0, 0xFD,
                0x88, 0xD0, 0xF8,
                0xA9, 0x40, 0x8D, 0x05, 0x20, 0x8D, 0x05, 0x20,
                0x8D, 0x00, 0x02,
                0x4C, 0x1B, 0xC0,
        };

        var handler = new int[]{0xE6, 0x01, 0x40};
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        for (var i = 0; i < program.length; i++) {
            image[16 + i] = (byte) program[i];
        }

        for (var i = 0; i < handler.length; i++) {
            image[16 + 0x0100 + i] = (byte) handler[i];
        }

        // One 16KB bank is mirrored into both halves of $8000-$FFFF, so the code above is
        // assembled for $C000 and the two vectors point into it.
        image[16 + 0x3FFA] = 0x00;
        image[16 + 0x3FFB] = (byte) 0xC1;
        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0xC0;

        return image;
    }
}
