package com.github.dimiro1.mynes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for the strobe latch and the shift register of the standard controller.
 * <p>
 * The ROM based tests never touch this: none of the blargg suites or nestest read a controller,
 * so every game that has ever run in this emulator has read $4016 from an untested implementation.
 */
class StandardControllerTests {
    /**
     * A, Select and Left held down: an asymmetric mask, so a shift in the wrong direction or an
     * off by one in the read order shows up instead of cancelling itself out.
     */
    private static final int A_SELECT_LEFT =
            Controller.BUTTON_A | Controller.BUTTON_SELECT | Controller.BUTTON_LEFT;

    private StandardController controller;

    @BeforeEach
    void setUp() {
        controller = new StandardController();
    }

    /**
     * Clocks a full strobe cycle, which is what a game writes to $4016 before reading a frame's
     * worth of input.
     */
    private void strobe() {
        controller.setStrobe(1);
        controller.setStrobe(0);
    }

    private int[] read(final int count) {
        var bits = new int[count];
        for (var i = 0; i < bits.length; i++) {
            bits[i] = controller.read();
        }
        return bits;
    }

    @Nested
    class Strobe {
        @Test
        void readsWhileTheStrobeIsHighReturnTheAButton() {
            controller.setButtons(Controller.BUTTON_A | Controller.BUTTON_RIGHT);
            controller.setStrobe(1);

            assertEquals(Controller.BUTTON_A, controller.read(), "A is held");
            assertEquals(Controller.BUTTON_A, controller.read(), "and reading does not shift");

            controller.setButtons(Controller.BUTTON_RIGHT);

            assertEquals(0, controller.read(), "A was released");
        }

        @Test
        void buttonsChangedWhileTheStrobeIsHighAreLatchedWhenItFalls() {
            controller.setStrobe(1);
            controller.setButtons(A_SELECT_LEFT);
            controller.setStrobe(0);

            assertArrayEquals(new int[]{1, 0, 1, 0, 0, 0, 1, 0}, read(8));
        }

        @Test
        void buttonsChangedWhileTheStrobeIsLowDoNotDisturbTheReadInProgress() {
            controller.setButtons(A_SELECT_LEFT);
            strobe();

            assertEquals(1, controller.read(), "A, from the latched mask");

            // The player lets go mid poll. The hardware is reading a register that was filled at
            // the falling edge, so the rest of this poll still reports the buttons as they were.
            controller.setButtons(0);

            assertArrayEquals(new int[]{0, 1, 0, 0, 0, 1, 0}, read(7));
        }
    }

    @Nested
    class ShiftRegister {
        @Test
        void eightReadsReturnTheButtonsInOrder() {
            controller.setButtons(A_SELECT_LEFT);
            strobe();

            // A, B, Select, Start, Up, Down, Left, Right
            assertArrayEquals(new int[]{1, 0, 1, 0, 0, 0, 1, 0}, read(8));
        }

        @Test
        void furtherReadsReturnOne() {
            controller.setButtons(A_SELECT_LEFT);
            strobe();
            read(8);

            // Past the eighth button the register has nothing left to report and clocks in ones.
            assertEquals(1, controller.read(), "the ninth read");
            assertEquals(1, controller.read(), "and every read after it");
        }

        @Test
        void aNewStrobeReloadsTheRegister() {
            controller.setButtons(A_SELECT_LEFT);
            strobe();
            controller.read();
            controller.read();

            strobe();

            assertArrayEquals(new int[]{1, 0, 1, 0, 0, 0, 1, 0}, read(8),
                    "the second poll should start again at A");
        }
    }

    /**
     * The two gauges the control panel's Pads tab is built on. Neither is anything the console can
     * see, and what is asked of them is always a difference between two frames rather than either
     * number on its own -- so what matters is that a poll is counted exactly once and that a frame
     * with no poll in it counts none.
     */
    @Nested
    class Counting {
        @Test
        void aPollIsTheFallingEdgeOfTheStrobe() {
            assertEquals(0, controller.getPolls(), "nothing has asked yet");

            controller.setStrobe(1);
            assertEquals(0, controller.getPolls(), "raising it is only half of one");

            controller.setStrobe(0);
            assertEquals(1, controller.getPolls(), "and letting it fall is the poll");
        }

        /**
         * $4016 carries three output lines, and a game working an expansion port writes to it
         * without ever raising the strobe. There is no edge in that, and so no poll.
         */
        @Test
        void aWriteThatLeavesTheStrobeLowIsNotAPoll() {
            controller.setStrobe(0);
            controller.setStrobe(0);

            assertEquals(0, controller.getPolls());
        }

        @Test
        void oneFrameOfAGameReadingThePadIsOnePollAndEightBits() {
            strobe();
            read(8);

            assertEquals(1, controller.getPolls());
            assertEquals(8, controller.getBitsRead());
        }

        /**
         * The port a game with no two player mode never reads: latched with the other one, because
         * a single write drives both, and never clocked. Which is the whole reason the two numbers
         * are separate.
         */
        @Test
        void aPadThatIsLatchedButNeverReadCountsPollsAndNoBits() {
            strobe();

            assertEquals(1, controller.getPolls());
            assertEquals(0, controller.getBitsRead());
        }

        @Test
        void peekingAtTheLineIsNotABitRead() {
            strobe();
            controller.read();
            controller.peek();

            assertEquals(
                    1,
                    controller.getBitsRead(),
                    "the bit was clocked out once and read twice");
        }
    }
}
