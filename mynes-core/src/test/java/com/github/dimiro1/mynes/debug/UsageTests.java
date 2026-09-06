package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The meter on its own, told about writes at cycle counts this file states out loud, and then
 * against a real machine running a program small enough to reason about.
 * <p>
 * Both halves matter and they catch different things. A meter fed by hand is the only way to say
 * what "did nothing new" means precisely enough to disagree with; a meter on a machine is the only
 * thing that proves the hook it hangs off is the one the processor actually crosses.
 */
class UsageTests {
    /**
     * A frame's worth of cycles, near enough. Nothing here depends on the real number -- the meter
     * is told where the boundaries are -- so a round one reads better in the assertions.
     */
    private static final int FRAME = 30_000;

    private final Usage usage = new Usage();

    // ============================================================================ the wait, by hand

    @Test
    void aProgramThatStoresNothingSpentTheWholeFrameWaiting() {
        var reading = frame(FRAME, () -> { });

        assertEquals(FRAME, reading.longestWait());
        assertEquals(0, reading.load(), "nothing stored is nothing done");
    }

    @Test
    void theWaitIsTheLongestStretchAndNotTheSumOfThem() {
        // Two quiet stretches, 8000 and 12000 cycles, with work between them.
        var reading = frame(FRAME, () -> {
            usage.wrote(0x0000, 0x8000, 8_000);
            usage.wrote(0x0001, 0x8003, 8_100);
            usage.wrote(0x0002, 0x8006, 20_100);
        });

        assertEquals(12_000, reading.longestWait(), "the longer of the two, not their sum");
    }

    /**
     * Contra, Mega Man 5 and Castlevania all wait in a loop that advances a counter, so measuring
     * on silence alone puts all three at ninety-nine per cent busy while they sit on a menu. This is
     * the rule that sees through it.
     */
    @Test
    void aLoopStoringTheSameByteFromTheSameInstructionIsStillWaiting() {
        var reading = frame(FRAME, () -> {
            usage.wrote(0x0300, 0x8000, 1_000);

            for (var at = 2_000; at < FRAME; at += 100) {
                usage.wrote(0x0090, 0xFEB2, at);
            }
        });

        // From the loop's first pass rather than from the work before it: that pass really was a
        // write this frame had not seen, and it is only the ones after it that are the same thing
        // again. One iteration of a wait loop counted as work is not worth arithmetic to avoid.
        assertEquals(FRAME - 2_000, reading.longestWait(), "one instruction, one byte, over and over");
    }

    /**
     * The same shape, and the opposite answer: a fill loop is one instruction writing a different
     * address every time round, which is work however tight the loop is.
     */
    @Test
    void aLoopStoringADifferentAddressEachTimeIsWork() {
        var reading = frame(FRAME, () -> {
            for (var at = 0; at < FRAME; at += 100) {
                usage.wrote(0x0300 + at / 100, 0xFEB2, at);
            }
        });

        assertEquals(100, reading.longestWait(), "a fill loop is a frame's work, not a frame's wait");
    }

    /**
     * There is no telling the hardware the same thing twice by accident, so a register never
     * continues a stretch however often the same instruction writes it -- which is what keeps a
     * {@code STA $2007} upload loop from reading as a wait.
     */
    @Test
    void aWriteToAnythingButMemoryIsAlwaysSomethingNew() {
        var reading = frame(FRAME, () -> {
            for (var at = 0; at < FRAME; at += 100) {
                usage.wrote(0x2007, 0xFEB2, at);
            }
        });

        assertEquals(100, reading.longestWait());
    }

    @Test
    void aStretchIsNotJoinedAcrossAFrameBoundary() {
        usage.frameEnded(1, 0, 0);
        usage.wrote(0x0300, 0x8000, 10);
        usage.frameEnded(2, FRAME, 0);

        // Nothing at all in the third frame. If the stretch carried over it would be measured from
        // the write in the second, and would come out longer than the frame it is reported against.
        usage.frameEnded(3, 2 * FRAME, 0);

        assertEquals(FRAME, usage.snapshot(0).longestWait(), "a frame's wait cannot exceed the frame");
    }

    @Test
    void cyclesATransferStoleAreNeitherWorkNorWaiting() {
        usage.frameEnded(1, 0, 0);

        for (var at = 0; at < FRAME; at += 100) {
            usage.wrote(0x0300 + at / 100, 0xFEB2, at);
        }

        // A frame of 30,513 cycles, 513 of which the processor was held off the bus for.
        usage.frameEnded(2, FRAME, 513);

        var reading = usage.snapshot(0);

        assertEquals(FRAME + 513, reading.cycles());
        assertEquals(513, reading.stolen());
        assertEquals(FRAME, reading.ran(), "the program only ever had the other thirty thousand");
    }

    @Test
    void framesThatWentByUncountedStartTheWindowAgain() {
        usage.frameEnded(1, 0, 0);
        usage.frameEnded(2, FRAME, 0);

        assertEquals(1, usage.snapshot(0).busy().length);

        // A rewind, or a panel that was shut for a while. There is no difference to take across it.
        usage.frameEnded(900, 500 * FRAME, 0);

        assertEquals(0, usage.snapshot(0).busy().length, "nothing measured is not one huge frame");
    }

    @Test
    void aReadingTakenByNobodyIsToldApartFromAFrameThatUsedNoneOfItself() {
        assertTrue(frame(FRAME, () -> { }).measured(), "a frame that did nothing was still counted");
        assertFalse(Usage.Snapshot.NONE.measured(), "and a reading nobody took was not");
    }

    // ================================================================================== the memory

    @Test
    void onlyTheTwoMemoriesAreMappedAndTheMirrorsAreTheSameBytes() {
        usage.wrote(0x0007, 0x8000, 0);
        usage.wrote(0x1807, 0x8003, 1);          // the same byte, three mirrors along
        usage.wrote(0x0100, 0x8006, 2);
        usage.wrote(0x0200, 0x8009, 3);
        usage.wrote(0x6000, 0x800C, 4);
        usage.wrote(0x2007, 0x800F, 5);          // a register
        usage.wrote(0x8000, 0x8012, 6);          // a mapper

        var reading = usage.snapshot(0x2000);

        assertEquals(1, of(reading, Usage.Area.ZERO_PAGE).ever(), "the mirror is the same byte");
        assertEquals(1, of(reading, Usage.Area.STACK).ever());
        assertEquals(1, of(reading, Usage.Area.WORK_RAM).ever());
        assertEquals(1, of(reading, Usage.Area.CARTRIDGE_RAM).ever());
    }

    @Test
    void aBoardWithNoRAMHasNoCartridgeAreaAtAll() {
        usage.wrote(0x6000, 0x8000, 0);

        assertNull(
                of(usage.snapshot(0), Usage.Area.CARTRIDGE_RAM),
                "nothing on the board is a different answer from nothing written");
    }

    @Test
    void readingEmptiesTheLiveMapAndLeavesTheEverOneAlone() {
        usage.wrote(0x0010, 0x8000, 0);

        assertEquals(1, of(usage.snapshot(0), Usage.Area.ZERO_PAGE).live());

        var again = of(usage.snapshot(0), Usage.Area.ZERO_PAGE);

        assertEquals(0, again.live(), "read once, and the quarter second starts again");
        assertEquals(1, again.ever(), "what it was used for does not start again");
    }

    @Test
    void startingAgainForgetsBoth() {
        usage.wrote(0x0010, 0x8000, 0);
        usage.forget();

        var reading = of(usage.snapshot(0), Usage.Area.ZERO_PAGE);

        assertEquals(0, reading.ever());
        assertEquals(0, reading.live());
    }

    /**
     * The map is what says <em>where</em>, which is most of the point of drawing one rather than
     * printing a percentage.
     */
    @Test
    void theMapSaysWhichEndOfTheAreaTheBytesAreIn() {
        usage.wrote(0x01FF, 0x8000, 0);

        var map = of(usage.snapshot(0), Usage.Area.STACK).everMap();

        assertEquals(1, map[Usage.CELLS - 1], "the stack fills downwards from the top");
        assertEquals(0, map[0]);
    }

    // ============================================================================ on a real machine

    /**
     * A program that does nothing but jump to itself, which is what every wait loop is underneath.
     * The whole point of the exercise: no hand-fed cycle numbers anywhere, and the answer still has
     * to be that the frame was entirely spent waiting.
     */
    @Test
    void aMachineRunningALoopSpendsAllOfItsFrameWaiting() {
        var reading = onAMachine(new int[]{0x4C, 0x00, 0x80});

        // 29780.5 on NTSC, so a whole frame is one or the other depending on which one it was.
        assertTrue(
                reading.cycles() == 29_780 || reading.cycles() == 29_781,
                "an NTSC frame, and this one came to " + reading.cycles());

        assertEquals(0, reading.stolen(), "nothing here transfers anything");
        assertEquals(0, reading.writes());
        assertEquals(0, reading.load(), 0.001);
    }

    /**
     * The same machine and the opposite program: a store to a moving address, forever. Every write
     * is somewhere new, so there is no stretch anywhere and the frame is entirely work.
     */
    @Test
    void aMachineFillingMemorySpendsAllOfItsFrameWorking() {
        // 8000  A2 00      LDX #$00
        // 8002  8A         TXA
        // 8003  9D 00 03   STA $0300,X
        // 8006  E8         INX
        // 8007  4C 02 80   JMP $8002
        var reading = onAMachine(new int[]{
                0xA2, 0x00,
                0x8A,
                0x9D, 0x00, 0x03,
                0xE8,
                0x4C, 0x02, 0x80,
        });

        assertTrue(reading.writes() > 2_000, "a store every dozen cycles, all frame");
        assertTrue(
                reading.load() > 0.99,
                "a fill loop leaves no stretch anywhere, so it is " + reading.load());

        assertEquals(
                256,
                of(reading, Usage.Area.WORK_RAM).ever(),
                "the page it walks, and nothing else");
    }

    // ================================================================================== internals

    /**
     * One frame with {@code work} done during it, measured from a standing start.
     */
    private Usage.Snapshot frame(final long cycles, final Runnable work) {
        usage.frameEnded(1, 0, 0);
        work.run();
        usage.frameEnded(2, cycles, 0);

        return usage.snapshot(0);
    }

    /**
     * Runs {@code code} on a real console through a real debugger, and reports the third frame --
     * far enough in that the program is going round its loop rather than starting up.
     */
    private static Usage.Snapshot onAMachine(final int[] code) {
        var nes = new NES(Cart.load(rom(code), "usage.nes"), Region.NTSC);
        var meter = new Usage();
        var debugger = new Debugger();

        debugger.attach(nes);
        debugger.setUsage(meter);

        var ppu = nes.getPPU();

        for (var frame = 1; frame <= 4; frame++) {
            var was = ppu.getFrame();

            do {
                nes.tick();
            } while (ppu.getFrame() == was);

            meter.frameEnded(
                    frame, nes.getCPU().getRunCycles(), nes.getCPU().getStalledCycles());
        }

        return meter.snapshot(0);
    }

    private static Usage.Segment of(final Usage.Snapshot reading, final Usage.Area area) {
        for (var segment : reading.memory()) {
            if (segment.area() == area) {
                return segment;
            }
        }

        return null;
    }

    private static byte[] rom(final int[] code) {
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
