package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.StandardController;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning two lifetime counters into "did the game read the pad this frame".
 * <p>
 * The last test is the one the instrument stands on, and it is run against a real machine rather
 * than against two controllers driven by hand: a cartridge whose main loop polls $4016 on every
 * other frame, which is the shape a game that overruns its frame has. Every part of the chain is in
 * it -- the strobe latching a cycle or two after the write, the port counting the falling edge, and
 * the arithmetic here turning that into a mark on a strip.
 */
class PadPollingTests {
    /**
     * One frame of a game that reads its pad: the strobe up, the strobe down, eight bits out.
     */
    private static void poll(final Controller pad) {
        pad.setStrobe(1);
        pad.setStrobe(0);

        for (var bit = 0; bit < 8; bit++) {
            pad.read();
        }
    }

    @Test
    void aFrameWithAPollInItIsNotALagFrame() {
        var polling = new PadPolling();
        var one = new StandardController();
        var two = new StandardController();

        for (var frame = 0; frame < 10; frame++) {
            poll(one);
            poll(two);
            polling.frameEnded(frame, one, two);
        }

        var pads = polling.snapshot();

        assertEquals(9, pads.frames(), "the first frame is the baseline and measures nothing");
        assertEquals(0, pads.lagFrames());
        assertEquals(1, pads.polls1(), "one poll in the frame that just finished");
        assertEquals(8, pads.bits1(), "and eight bits clocked out of it");
    }

    /**
     * The port a one player game never reads. Both pads are latched by the same write, so what
     * separates them is the bits: this is the shape of every game with no two player mode.
     */
    @Test
    void aPadThatIsLatchedAndNeverReadShowsPollsAndNoBits() {
        var polling = new PadPolling();
        var one = new StandardController();
        var two = new StandardController();

        for (var frame = 0; frame < 3; frame++) {
            poll(one);

            // What $4016 does to the other port, and nothing more.
            two.setStrobe(1);
            two.setStrobe(0);

            polling.frameEnded(frame, one, two);
        }

        var pads = polling.snapshot();

        assertEquals(1, pads.polls2(), "player two is latched with player one");
        assertEquals(0, pads.bits2(), "and never read");
    }

    @Test
    void aFrameWithNoPollInItIsALagFrame() {
        var polling = new PadPolling();
        var one = new StandardController();
        var two = new StandardController();

        polling.frameEnded(0, one, two);
        poll(one);
        polling.frameEnded(1, one, two);
        polling.frameEnded(2, one, two);
        poll(one);
        polling.frameEnded(3, one, two);

        var pads = polling.snapshot();

        assertEquals(3, pads.frames());
        assertEquals(1, pads.lagFrames());
        assertArrayEqualsBoolean(new boolean[]{true, false, true}, pads.polled());
    }

    /**
     * Frames that went by uncounted -- the panel was shut, or the machine was rewound past what was
     * measured -- are a gap rather than a difference. Measuring across one would report however
     * many frames went by as a single frame of enormous activity, and the frame after a rewind as
     * a lag frame the game had nothing to do with.
     */
    @Test
    void aGapInTheFramesStartsTheWindowAgain() {
        var polling = new PadPolling();
        var one = new StandardController();
        var two = new StandardController();

        for (var frame = 0; frame < 10; frame++) {
            poll(one);
            polling.frameEnded(frame, one, two);
        }

        assertEquals(9, polling.snapshot().frames());

        // Two hundred frames later, having counted none of them.
        for (var frame = 0; frame < 200; frame++) {
            poll(one);
        }

        polling.frameEnded(210, one, two);

        assertEquals(0, polling.snapshot().frames(), "the window starts again rather than lying");

        poll(one);
        polling.frameEnded(211, one, two);

        var pads = polling.snapshot();

        assertEquals(1, pads.frames());
        assertEquals(1, pads.polls1(), "and the counts are the new frame's rather than the gap's");
    }

    @Test
    void theWindowHoldsTheMostRecentFramesAndNoMore() {
        var polling = new PadPolling();
        var one = new StandardController();
        var two = new StandardController();

        for (var frame = 0; frame < Readout.Pads.WINDOW * 2; frame++) {
            // Nothing polled at all until the very last frame, so the window's contents say where
            // in the run it is looking rather than only how long it is.
            if (frame == Readout.Pads.WINDOW * 2 - 1) {
                poll(one);
            }

            polling.frameEnded(frame, one, two);
        }

        var pads = polling.snapshot();

        assertEquals(Readout.Pads.WINDOW, pads.frames(), "capped at the window");
        assertEquals(Readout.Pads.WINDOW - 1, pads.lagFrames());
        assertTrue(pads.polled()[pads.frames() - 1], "newest last");
        assertFalse(pads.polled()[0], "oldest first");
    }

    /**
     * The check the plan asked for: a cartridge that skips a poll, run for real.
     * <p>
     * Its main loop waits for the NMI, flips a byte in zero page, waits out half a frame, and reads
     * $4016 only when that byte comes out odd -- so exactly every other frame goes by without the
     * pad being read, which is the every-other-frame stutter a game that overruns its frame has and
     * the pattern the strip in the Pads tab exists to show.
     */
    @Test
    void aRomThatPollsOnAlternateFramesShowsHalfItsFramesAsLag() {
        var nes = new NES(Cart.load(alternatingRom(), "pads.nes"));
        var polling = new PadPolling();

        // Well past the two vblanks the program waits out before its loop starts, so the window
        // holds nothing but the steady state.
        for (var frame = 1; frame <= 200; frame++) {
            runOneFrame(nes);
            polling.frameEnded(frame, nes.getController1(), nes.getController2());
        }

        var pads = polling.snapshot();

        assertEquals(Readout.Pads.WINDOW, pads.frames());
        assertEquals(Readout.Pads.WINDOW / 2, pads.lagFrames(), "every other frame");

        for (var i = 1; i < pads.frames(); i++) {
            assertFalse(
                    pads.polled()[i] == pads.polled()[i - 1],
                    "the frames alternate rather than coming in runs, at " + i);
        }

        assertEquals(0, pads.bits2(), "and it never looks at player two");
    }

    private static void runOneFrame(final NES nes) {
        var was = nes.getPPU().getFrame();

        while (nes.getPPU().getFrame() == was) {
            nes.tick();
        }
    }

    private static void assertArrayEqualsBoolean(
            final boolean[] expected, final boolean[] actual) {

        assertEquals(expected.length, actual.length, "same number of frames");

        for (var i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "frame " + i);
        }
    }

    /**
     * A 16KB NROM cartridge whose whole program is the loop described above, assembled by hand.
     * <p>
     * A game rather than a harness, because what is being checked is the whole path from a
     * {@code STA $4016} to a mark on a strip: the strobe is latched a cycle or two after the write
     * lands, on a get-to-put transition, which is machinery no test driving a controller directly
     * would go anywhere near.
     * <pre>
     * C000  SEI / CLD / LDX #$FF / TXS
     *       LDA #$40 / STA $4017       the frame counter's interrupt off
     *       LDA #$00 / STA $2001       rendering off
     *       STA $00 / STA $01          the parity byte and the NMI's flag
     * C013  BIT $2002 / BPL -5         two vblanks, which is how a program waits for the PPU
     * C018  BIT $2002 / BPL -5
     *       LDA #$80 / STA $2000       and only then the NMI
     * C022  LDA $01 / BEQ -4     loop: wait for the NMI to say a frame has begun
     *       LDA #$00 / STA $01
     *       LDA $00 / EOR #$01 / STA $00
     * C030  LDY #$0C / LDX #$00 / DEX / BNE -3 / DEY / BNE -8     about 15000 cycles
     *       LDA $00 / BEQ +18          on an even frame, skip the poll entirely
     *       LDA #$01 / STA $4016 / LDA #$00 / STA $4016
     *       LDX #$08 / LDA $4016 / DEX / BNE -6                   the eight bits
     * C050  JMP $C022
     *
     * C100  INC $01 / RTI              the NMI, and the whole of it
     * </pre>
     * Two things in there are the point rather than ceremony. The frame is found through the
     * <b>NMI</b> rather than by polling $2002, because a program that polls it can have a read land
     * on the dot the flag is raised, lose that frame's vblank and slip a frame -- which the
     * hardware does, this emulator models, and a test asserting on an exact pattern would trip
     * over. And the poll sits behind a <b>delay</b> that puts it near the middle of the picture
     * rather than at the top of it: a poll a few cycles from a frame boundary would land on either side of it as the
     * CPU and the PPU drift, which is a real thing to know about and not this test's subject.
     */
    private static byte[] alternatingRom() {
        var program = new int[]{
                0x78, 0xD8, 0xA2, 0xFF, 0x9A,
                0xA9, 0x40, 0x8D, 0x17, 0x40,
                0xA9, 0x00, 0x8D, 0x01, 0x20,
                0x85, 0x00, 0x85, 0x01,
                0x2C, 0x02, 0x20, 0x10, 0xFB,
                0x2C, 0x02, 0x20, 0x10, 0xFB,
                0xA9, 0x80, 0x8D, 0x00, 0x20,
                0xA5, 0x01, 0xF0, 0xFC,
                0xA9, 0x00, 0x85, 0x01,
                0xA5, 0x00, 0x49, 0x01, 0x85, 0x00,
                0xA0, 0x0C,
                0xA2, 0x00,
                0xCA, 0xD0, 0xFD,
                0x88, 0xD0, 0xF8,
                0xA5, 0x00,
                0xF0, 0x12,
                0xA9, 0x01, 0x8D, 0x16, 0x40,
                0xA9, 0x00, 0x8D, 0x16, 0x40,
                0xA2, 0x08,
                0xAD, 0x16, 0x40, 0xCA, 0xD0, 0xFA,
                0x4C, 0x22, 0xC0,
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

        // On a round boundary well past the end of the code above, so that the address in the
        // vector reads as itself.
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
