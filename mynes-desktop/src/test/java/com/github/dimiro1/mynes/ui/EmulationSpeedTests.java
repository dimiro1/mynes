package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the speeds Fast Forward runs at.
 * <p>
 * Two things matter here and neither is easy to see by looking at the emulator run. The first is
 * the arithmetic: a speed is a frame budget, and getting it wrong is the difference between four
 * times speed and four times slower. The second is that the ids survive the round trip through
 * {@code ~/.mynes/config.properties}, which is a file people edit by hand, so every way of getting
 * it wrong has to land somewhere sensible.
 */
class EmulationSpeedTests {
    @Nested
    @DisplayName("the frame budget")
    class FrameBudget {
        @Test
        void normalIsOneNTSCFrame() {
            assertEquals(Region.NTSC.frameNanos(), EmulationSpeed.NORMAL.frameNanos(Region.NTSC));
        }

        @Test
        void normalIsOnePALFrameOnAPALMachine() {
            // Three and a third milliseconds longer, which is the difference between 60.0988
            // frames a second and 50.0070 -- and a machine paced at the wrong one of them plays
            // the game at the wrong speed however accurately it is emulated.
            assertEquals(Region.PAL.frameNanos(), EmulationSpeed.NORMAL.frameNanos(Region.PAL));
            assertTrue(EmulationSpeed.NORMAL.frameNanos(Region.PAL)
                    > EmulationSpeed.NORMAL.frameNanos(Region.NTSC));
        }

        @Test
        void aMultipliedSpeedGetsThatMuchLessOfAFrame() {
            var frame = Region.NTSC.frameNanos();

            assertEquals(frame / 2, EmulationSpeed.TWO_TIMES.frameNanos(Region.NTSC));
            assertEquals(frame / 4, EmulationSpeed.FOUR_TIMES.frameNanos(Region.NTSC));
            assertEquals(frame / 8, EmulationSpeed.EIGHT_TIMES.frameNanos(Region.NTSC));
        }

        @Test
        void aMultipliedSpeedDividesWhicheverFrameItIsGiven() {
            assertEquals(Region.PAL.frameNanos() / 4,
                    EmulationSpeed.FOUR_TIMES.frameNanos(Region.PAL));
        }

        @Test
        void unlimitedHasNoBudgetAtAll() {
            // Nothing divides by this. The loop recognises unlimited and never waits, so the zero
            // is here to say there is no answer rather than to be used as one.
            assertEquals(0, EmulationSpeed.UNLIMITED.frameNanos(Region.NTSC));
            assertEquals(0, EmulationSpeed.UNLIMITED.frameNanos(Region.PAL));
        }
    }

    @Nested
    @DisplayName("the choices")
    class Choices {
        @Test
        void leaveOutNormal() {
            // Fast forwarding at normal speed is what leaving the menu item unticked does.
            assertFalse(EmulationSpeed.fastForwardChoices().contains(EmulationSpeed.NORMAL));
        }

        @Test
        void areEveryOtherSpeed() {
            assertEquals(EmulationSpeed.values().length - 1,
                    EmulationSpeed.fastForwardChoices().size());
            assertTrue(EmulationSpeed.fastForwardChoices().contains(EmulationSpeed.UNLIMITED));
        }

        @Test
        void includeTheDefault() {
            assertTrue(EmulationSpeed.fastForwardChoices()
                    .contains(EmulationSpeed.defaultFastForward()));
        }
    }

    @Nested
    @DisplayName("naming a speed")
    class Ids {
        @Test
        void anIdNamesItsSpeed() {
            assertSame(EmulationSpeed.EIGHT_TIMES, EmulationSpeed.fastForwardById("8x"));
            assertSame(EmulationSpeed.UNLIMITED, EmulationSpeed.fastForwardById("unlimited"));
        }

        @Test
        void everyChoiceReadsBackAsItself() {
            // What the config file's round trip rests on: the id written out is the id that comes
            // back in.
            for (var speed : EmulationSpeed.fastForwardChoices()) {
                assertSame(speed, EmulationSpeed.fastForwardById(speed.id()), speed.label());
            }
        }

        @Test
        void anUnknownIdFallsBackToTheDefault() {
            assertSame(EmulationSpeed.defaultFastForward(),
                    EmulationSpeed.fastForwardById("turbo"));
        }

        @Test
        void normalIsNotOneOfTheAnswers() {
            // Someone asking for a fast forward that does nothing has misunderstood the setting,
            // and the log line saying so is worth more than honouring it.
            assertSame(EmulationSpeed.defaultFastForward(),
                    EmulationSpeed.fastForwardById(EmulationSpeed.NORMAL.id()));
        }
    }
}
