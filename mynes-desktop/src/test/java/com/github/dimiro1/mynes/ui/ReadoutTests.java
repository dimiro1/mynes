package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The machine's scalars, taken in one go.
 * <p>
 * What is worth holding this to is not that a getter returns a field -- it is that reading the
 * machine does not <em>change</em> it. Three of the registers in here have side effects when a
 * program reads them: $2002 clears the VBlank flag and resets the write latch, $4015 acknowledges
 * the frame counter's interrupt, and $2007 moves the address on. A gauge that fired any of those
 * four times a second would be the thing that broke the game it was pointed at.
 */
class ReadoutTests {
    private static NES nes;

    @BeforeAll
    static void machine() {
        nes = new NES(Cart.load(rom(), "readout.nes"));

        // Far enough for the reset to have run and for the PPU's warm-up to be over: $2000 and
        // $2001 writes are ignored for the first 29658 cycles, which is why the cartridge below
        // writes them in a loop rather than once.
        for (var i = 0; i < 40_000; i++) {
            nes.tick();
        }
    }

    @Test
    void itDescribesTheMachineItWasTakenFrom() {
        var ppu = nes.getPPU();
        var readout = Readout.of(nes);

        assertEquals(ppu.getFrame(), readout.frame());
        assertEquals(ppu.getScanline(), readout.scanline());
        assertEquals(ppu.getV(), readout.v());
        assertEquals(ppu.getT(), readout.t());
        assertEquals(ppu.getFineX(), readout.fineX());
        assertEquals(nes.getCPU().getState().pc(), readout.cpu().pc());
    }

    /**
     * The cartridge below writes $BB to $2000 -- NMI on, tall sprites, both pattern tables at
     * $1000, nametable 3 -- so every bit the dashboard decodes has something in it other than zero.
     */
    @Test
    void theControlByteIsDecodedTheWayTheDashboardSpellsIt() {
        var readout = Readout.of(nes);

        assertEquals(0xBB, readout.control());
        assertEquals(16, readout.spriteHeight());
        assertEquals(0x1000, readout.backgroundPatternTable());
        assertEquals(0x1000, readout.spritePatternTable());
        assertEquals(0x2C00, readout.nametable());
    }

    @Test
    void theMaskSaysWhetherAnythingIsBeingDrawn() {
        var readout = Readout.of(nes);

        assertEquals(0x18, readout.mask());
        assertTrue(readout.renderingEnabled());
    }

    /**
     * The scroll comes out of {@code t} and {@code fineX}, which is where a game's own idea of it
     * lives -- and the nametable bits of $2000 are bits 10 and 11 of {@code t}, so writing $2000
     * moves it by a whole screen.
     */
    @Test
    void theScrollIsWhereTheNextFrameWillStart() {
        var readout = Readout.of(nes);

        assertEquals(256, readout.scrollX(), "nametable 3 is the second screen across... ");
        assertEquals(240, readout.scrollY(), "...and the second down");
    }

    /**
     * The point of the class. Reading a machine to describe it must not be the thing that clears a
     * flag the game is waiting on.
     * <p>
     * The frame counter's interrupt is the sharp half: a real $4015 read does not clear it there
     * and then, it arms {@code frameIRQClearPending} and the next cycle does -- so the machine is
     * clocked afterwards, which is what a read would have needed to show its work.
     */
    @Test
    void takingOneChangesNothing() {
        var ppu = nes.getPPU();
        var apu = nes.getAPU();
        var before = ppu.peek(2);
        var latch = ppu.isWriteLatchSet();

        assertTrue((apu.peekStatus() & 0x40) != 0, "the frame counter's interrupt is up to begin");

        for (var i = 0; i < 100; i++) {
            Readout.of(nes);
        }

        assertEquals(before, ppu.peek(2), "$2002 still says what it said");
        assertEquals(latch, ppu.isWriteLatchSet(), "and the write latch was not reset");

        for (var i = 0; i < 20; i++) {
            nes.tick();
        }

        assertTrue((apu.peekStatus() & 0x40) != 0, "and nothing acknowledged the interrupt");
    }

    @Test
    void thePadIsWhatIsBeingHeldRatherThanWhatHasBeenShiftedOut() {
        nes.getController1().setButtons(Controller.BUTTON_A | Controller.BUTTON_RIGHT);

        // Half way through being clocked, which is what a pad usually is: the game has taken two
        // bits out of the shift register and the readout must not be describing those.
        nes.getController1().setStrobe(1);
        nes.getController1().setStrobe(0);
        nes.getController1().read();
        nes.getController1().read();

        var readout = Readout.of(nes);

        assertEquals(Controller.BUTTON_A | Controller.BUTTON_RIGHT, readout.pad1());
        assertEquals(0, readout.pad2());

        nes.getController1().setButtons(0);
    }

    /**
     * A $4017 write takes three or four cycles to reach the sequencer, which is the hardware's and
     * is why the machine is clocked between the write and the reading.
     */
    @Test
    void theFrameCounterModeIsWhicheverSequenceIsRunning() {
        nes.getAPU().write(0x4017, 0x00);
        settle();

        assertFalse(Readout.of(nes).fiveStep());
        assertFalse(Readout.of(nes).frameIRQInhibited());

        nes.getAPU().write(0x4017, 0xC0);
        settle();

        assertTrue(Readout.of(nes).fiveStep());
        assertTrue(Readout.of(nes).frameIRQInhibited());
    }

    private static void settle() {
        for (var i = 0; i < 20; i++) {
            nes.tick();
        }
    }

    /**
     * A cartridge that writes $2000 and $2001 with something other than zero and then spins, so
     * that every field above has a value worth asserting on.
     *
     * <pre>
     * 8000  A9 BB     LDA #$BB     NMI on, 8x16, both tables $1000, nametable 3
     * 8002  8D 00 20  STA $2000
     * 8005  A9 18     LDA #$18     background and sprites on
     * 8007  8D 01 20  STA $2001
     * 800A  4C 00 80  JMP $8000
     * </pre>
     * <p>
     * Round and round rather than written once and spun on, because the chip ignores both registers
     * until it has warmed up: a cartridge that wrote them at the reset vector and then stopped would
     * leave every one of them at zero.
     */
    private static byte[] rom() {
        var code = new int[]{
                0xA9, 0xBB,
                0x8D, 0x00, 0x20,
                0xA9, 0x18,
                0x8D, 0x01, 0x20,
                0x4C, 0x00, 0x80,
        };

        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        for (var i = 0; i < code.length; i++) {
            image[16 + i] = (byte) code[i];
        }

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
