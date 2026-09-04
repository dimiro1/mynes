package com.github.dimiro1.mynes;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The numbers that separate the two consoles.
 * <p>
 * Every one of them is arithmetic somebody could get wrong by a digit and not notice for a long
 * time: a machine with the frame rate slightly off still plays, a noise table one entry out still
 * hisses. So they are checked against the quantities they are derived from -- dots per frame against
 * scanlines, the frame length against the clock rate -- rather than restated, which would only
 * assert that the file says what the file says.
 */
class RegionTests {

    @Test
    void aFrameIsAsManyDotsAsItHasScanlines() {
        assertEquals(89342, 341 * Region.NTSC.scanlinesPerFrame());
        assertEquals(106392, 341 * Region.PAL.scanlinesPerFrame());
    }

    /**
     * Both ratios are worked back out of the line each console draws rather than restated, for the
     * reason the rest of this file works: 1.386 is a number nobody would spot a wrong digit in.
     * <p>
     * The 2C02 puts its 256 pixels and their border into 280 pixels' worth of a 4:3 line, so a
     * pixel is 240/280 of 4/3 across -- which comes out at exactly 8:7. The 2C07's dot clock and
     * PAL's square-pixel rate are the same sum with numbers that do not simplify.
     */
    @Test
    void aTelevisionDrewThePixelsWiderThanTheyWereTall() {
        assertEquals(8 / 7.0, Region.NTSC.pixelAspect());
        assertEquals(240 / 280.0 * 4 / 3, Region.NTSC.pixelAspect(), 1e-12);

        assertEquals(7_375_000 / 5_320_342.5, Region.PAL.pixelAspect());
        assertEquals(1.3862, Region.PAL.pixelAspect(), 0.0001);
    }

    @Test
    void thePreRenderLineIsTheLastOne() {
        assertEquals(261, Region.NTSC.preRenderLine());
        assertEquals(311, Region.PAL.preRenderLine());
    }

    @Test
    void onlyTheNTSCChipDropsADotOnOddFrames() {
        assertTrue(Region.NTSC.skipsDotOnOddFrames());
        assertFalse(Region.PAL.skipsDotOnOddFrames());
    }

    @Test
    void theDividersGiveThreeDotsToACPUCycleAndThreeAndAFifth() {
        // What NES.tick asks the PPU for, worked out the way the PPU works it out: master clocks
        // in, dots out, and whatever will not divide carried to the next cycle.
        assertEquals(3.0, dotsPerCPUCycle(Region.NTSC));
        assertEquals(3.2, dotsPerCPUCycle(Region.PAL));
    }

    @Test
    void thePALDividerRepeatsEverySixteenDots() {
        // 3, 3, 3, 3, 4 -- five CPU cycles to sixteen dots, and back where it started. A pattern
        // that did not close would drift the beam away from the program over a few frames.
        var remainder = 0;
        var dots = 0;

        for (var cycle = 0; cycle < 5; cycle++) {
            var available = remainder + Region.PAL.cpuDivider();

            dots += available / Region.PAL.ppuDivider();
            remainder = available % Region.PAL.ppuDivider();
        }

        assertEquals(16, dots);
        assertEquals(0, remainder, "the sixth cycle must start where the first one did");
    }

    @Test
    void theCPUClockIsTheMasterClockOverTheDivider() {
        // 21.477272MHz over twelve, and 26.601712MHz over sixteen.
        assertEquals(21_477_272.0 / 12, Region.NTSC.cpuClockHz(), 1.0);
        assertEquals(26_601_712.0 / 16, Region.PAL.cpuClockHz(), 0.0);
    }

    @Test
    void aFrameLastsItsDotsAtTheDotRate() {
        // The dot clock is the master clock over the PPU's divider, so a frame is its dots over
        // that. 60.0988 frames a second and 50.0070, neither of them the round number.
        //
        // Ten nanoseconds of slack on sixteen milliseconds, which is four parts in ten million:
        // the NTSC constant is 1/60.0988 and 60.0988 is itself a rounding, so the two ways of
        // arriving at it disagree in the eighth digit. Anything actually wrong here is out by
        // microseconds at the very least.
        assertEquals(nanosPerFrame(21_477_272.0, 4, 89341.5), Region.NTSC.frameNanos(), 10.0);
        assertEquals(nanosPerFrame(26_601_712.0, 5, 106392), Region.PAL.frameNanos(), 10.0);
    }

    @Test
    void everyNoisePeriodIsEvenOnBothMachines() {
        // The divider that counts them runs at half the CPU clock, and APU.Noise halves them on
        // the way in. An odd entry would quietly lose half a cycle of pitch.
        for (var region : Region.values()) {
            for (var index = 0; index < 16; index++) {
                assertEquals(0, region.noisePeriod(index) % 2,
                        region.label() + " period " + index);
            }
        }
    }

    @Test
    void theTablesDifferBetweenTheMachines() {
        // Not a survey of all thirty-two entries, which would only copy the table twice. This is
        // the one thing worth asserting: that PAL is not quietly reading NTSC's.
        assertNotEquals(Region.NTSC.noisePeriod(2), Region.PAL.noisePeriod(2));
        assertNotEquals(Region.NTSC.dmcRate(0), Region.PAL.dmcRate(0));
        assertEquals(398, Region.PAL.dmcRate(0));
        assertEquals(14, Region.PAL.noisePeriod(2));
    }

    @Test
    void theFrameCounterSequenceIsInOrderAndEndsOnItsPeriod() {
        for (var region : Region.values()) {
            assertTrue(region.step1Cycle() < region.step2Cycle(), region.label());
            assertTrue(region.step2Cycle() < region.step3Cycle(), region.label());
            assertTrue(region.step3Cycle() < region.irqFirstCycle(), region.label());

            // The three cycles the interrupt flag is set on are consecutive, which is what
            // 6-irq_flag_timing reads $4015 in the middle of.
            assertEquals(region.irqFirstCycle() + 1, region.step4Cycle(), region.label());
            assertEquals(region.step4Cycle() + 1, region.fourStepPeriod(), region.label());

            assertEquals(region.step5Cycle() + 1, region.fiveStepPeriod(), region.label());
            assertTrue(region.fourStepPeriod() < region.step5Cycle(), region.label());
        }
    }

    @Test
    void theFrameCounterSequenceLastsOneFrameOfItsOwnMachine() {
        // Which is what "frame counter" means, and the whole reason PAL needs its own table: at
        // NTSC's 29830 cycles a PAL machine's envelopes and length counters would run 11% fast and
        // every game's music would be in the wrong tempo.
        //
        // Neither is exact -- the sequence is a whole number of cycles and a frame is not -- and
        // how close each comes is a fact about the hardware rather than about this emulator. NTSC
        // is out by 0.166% and PAL by 0.0196%, so a fifth of a percent covers both and would still
        // catch a table borrowed from the other machine.
        for (var region : Region.values()) {
            var sequence = region.fourStepPeriod() / region.cpuClockHz();
            var frame = region.frameNanos() / 1_000_000_000.0;

            assertEquals(frame, sequence, frame * 0.002, region.label());
        }
    }

    @Test
    void anIdNamesItsRegion() {
        assertSame(Region.NTSC, Region.byId("ntsc"));
        assertSame(Region.PAL, Region.byId("pal"));
    }

    @Test
    void anUnknownIdNamesNothingAtAll() {
        // Null rather than a fallback, because the two callers disagree about what to do with it:
        // a config file falls back to automatic and a command line refuses.
        assertNull(Region.byId("secam"));
        assertNull(Region.byId("PAL"));
        assertNull(Region.byId(""));
    }

    private static double dotsPerCPUCycle(final Region region) {
        return (double) region.cpuDivider() / region.ppuDivider();
    }

    private static double nanosPerFrame(
            final double masterClockHz, final int ppuDivider, final double dots) {
        return dots / (masterClockHz / ppuDivider) * 1_000_000_000.0;
    }
}
