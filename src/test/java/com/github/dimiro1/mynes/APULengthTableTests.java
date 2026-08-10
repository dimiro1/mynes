package com.github.dimiro1.mynes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The length counters: how long a note lasts when the program does not switch it off by hand.
 * <p>
 * A write to a channel's fourth register loads one of 32 tabulated lengths and every half frame
 * takes one off it. The table is the part worth spelling out -- its order looks arbitrary and is
 * not, and a single wrong entry is a handful of notes in a handful of games coming out the wrong
 * length, which is exactly the kind of bug nobody finds by playing.
 */
class APULengthTableTests {
    /**
     * The lengths $4003, $4007, $400B and $400F can load, in the order the top five bits of the
     * written byte index them. Written out here rather than read from the chip so that the test is
     * a second source rather than a mirror.
     *
     * @see <a href="https://www.nesdev.org/wiki/APU_Length_Counter">NESdev: APU length counter</a>
     */
    private static final int[] EXPECTED = {
            10, 254, 20, 2, 40, 4, 80, 6, 160, 8, 60, 10, 14, 12, 26, 14,
            12, 16, 24, 18, 48, 20, 96, 22, 192, 24, 72, 26, 16, 28, 32, 30,
    };

    /**
     * The cycle the first half frame lands on.
     */
    private static final int FIRST_HALF_FRAME = 14913;

    /**
     * How long the four step sequence is, so how far apart the half frames are once the first two
     * have gone past.
     */
    private static final int FOUR_STEP_PERIOD = 29830;

    private APU apu;

    @BeforeEach
    void setUp() {
        apu = new APU(level -> { }, level -> { });
        apu.write(0x4015, 0x0F);  // every channel enabled, so every length register loads
    }

    private void tick(final int cycles) {
        for (var i = 0; i < cycles; i++) {
            apu.tick();
        }
    }

    /**
     * The other half frame of the four step sequence, which is its last step.
     */
    private static final int SECOND_HALF_FRAME = FOUR_STEP_PERIOD - 1;

    @Nested
    @DisplayName("the table")
    class Table {
        @Test
        void everyIndexLoadsItsLength() {
            for (var index = 0; index < EXPECTED.length; index++) {
                // The low three bits are the top three bits of the timer period and have nothing
                // to do with the length, so they are set here to prove they are ignored.
                apu.write(0x4003, (index << 3) | 0x07);

                assertEquals(EXPECTED[index], apu.pulse1Length(), "index " + index);
            }
        }

        @Test
        void eachChannelLoadsFromItsOwnRegister() {
            apu.write(0x4003, 0x00);  // index 0, which is 10
            apu.write(0x4007, 0x08);  // index 1, which is 254
            apu.write(0x400B, 0x10);  // index 2, which is 20
            apu.write(0x400F, 0x18);  // index 3, which is 2

            assertEquals(10, apu.pulse1Length());
            assertEquals(254, apu.pulse2Length());
            assertEquals(20, apu.triangleLength());
            assertEquals(2, apu.noiseLength());
        }
    }

    @Nested
    @DisplayName("counting down")
    class CountingDown {
        @Test
        void aHalfFrameTakesOneOff() {
            apu.write(0x4003, 0x00);  // 10

            tick(FIRST_HALF_FRAME);

            assertEquals(9, apu.pulse1Length());
        }

        @Test
        void theHaltBitStopsTheCounting() {
            apu.write(0x4000, 0x20);  // halt set
            apu.write(0x4003, 0x00);  // 10

            tick(4 * FOUR_STEP_PERIOD);

            assertEquals(10, apu.pulse1Length(), "which is what makes a note held rather than timed");
        }

        @Test
        void theTrianglesHaltBitIsItsLinearCounterControlBit() {
            apu.write(0x4008, 0x80);  // control set, so the length counter is halted too
            apu.write(0x400B, 0x00);  // 10

            tick(4 * FOUR_STEP_PERIOD);

            assertEquals(10, apu.triangleLength());
        }

        @Test
        void itStopsAtZeroRatherThanWrappingRound() {
            apu.write(0x4003, 0x18);  // index 3, which is 2

            tick(8 * FOUR_STEP_PERIOD);

            assertEquals(0, apu.pulse1Length());
        }

        @Test
        void aReloadWhileItIsRunningStartsTheNoteAgain() {
            apu.write(0x4003, 0x00);  // 10

            tick(FIRST_HALF_FRAME);
            assertEquals(9, apu.pulse1Length());

            apu.write(0x4003, 0x00);
            assertEquals(10, apu.pulse1Length());

            tick(SECOND_HALF_FRAME - FIRST_HALF_FRAME);
            assertEquals(9, apu.pulse1Length());
        }
    }

    @Nested
    @DisplayName("$4015")
    class Status {
        @Test
        void disablingAChannelZeroesItsCounter() {
            apu.write(0x4003, 0x08);  // 254
            apu.write(0x4015, 0x0E);  // everything but pulse 1

            assertEquals(0, apu.pulse1Length());
        }

        @Test
        void aDisabledChannelIgnoresALengthRegisterWrite() {
            apu.write(0x4015, 0x00);
            apu.write(0x4003, 0x08);

            assertEquals(0, apu.pulse1Length(), "the counter stays at zero until it is enabled");
        }

        @Test
        void enablingAChannelDoesNotLoadAnythingByItself() {
            apu.write(0x4015, 0x00);
            apu.write(0x4003, 0x08);
            apu.write(0x4015, 0x01);

            assertEquals(0, apu.pulse1Length(), "the write that was ignored stays ignored");

            apu.write(0x4003, 0x08);
            assertEquals(254, apu.pulse1Length(), "and the next one lands");
        }

        @Test
        void readingItSaysWhichCountersHaveAnythingLeft() {
            apu.write(0x4003, 0x08);  // pulse 1
            apu.write(0x400B, 0x08);  // triangle

            assertEquals(0x05, apu.readStatus() & 0x0F);
        }

        @Test
        void aCounterThatHasRunOutReadsBackAsZero() {
            apu.write(0x4003, 0x18);  // index 3, which is 2

            tick(4 * FOUR_STEP_PERIOD);

            assertEquals(0, apu.readStatus() & 0x0F);
        }
    }
}
