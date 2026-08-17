package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.Controller;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The grammar that says what to press and when.
 * <p>
 * Worth this much attention because a schedule that is read differently from how it was meant does
 * not look like an error: it looks like a game that sat on its title screen, which is exactly what
 * the schedule was written to stop happening.
 */
class InputScheduleTests {
    private static final int PRESS_FRAMES = 2;

    private static InputSchedule of(final String... specs) {
        return InputSchedule.parse(List.of(specs), PRESS_FRAMES);
    }

    private static UsageException refused(final String spec) {
        return assertThrows(UsageException.class, () -> of(spec));
    }

    @Test
    void aPressIsHeldForThePressLength() {
        var schedule = of("60:start");

        assertEquals(0, schedule.buttonsAt(59));
        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(60));
        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(61));
        assertEquals(0, schedule.buttonsAt(62));
    }

    @Test
    void aHoldCoversTheFramesBetweenItsEndsAndNoMore() {
        var schedule = of("200-400:right");

        assertEquals(0, schedule.buttonsAt(199));
        assertEquals(Controller.BUTTON_RIGHT, schedule.buttonsAt(200));
        assertEquals(Controller.BUTTON_RIGHT, schedule.buttonsAt(399));
        assertEquals(0, schedule.buttonsAt(400), "the far end is excluded");
    }

    @Test
    void aPulseAlsoPressesOnItsFirstFrame() {
        var schedule = of("60/40:start");

        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(60));
        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(61));
        assertEquals(0, schedule.buttonsAt(62));
    }

    @Test
    void aPulseRepeatsForAsLongAsTheRunLasts() {
        var schedule = of("60/40:start");

        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(100));
        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(140));
        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(10060));
        assertEquals(0, schedule.buttonsAt(120));
    }

    /**
     * The form that gets a game past its menus without going on to press Start during play, which
     * in Super Mario Bros. is the pause button.
     */
    @Test
    void aPulseWithACountStopsAfterIt() {
        var schedule = of("60/40x3:start");

        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(60));
        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(100));
        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(140));
        assertEquals(0, schedule.buttonsAt(180), "the fourth press should not happen");
    }

    @Test
    void buttonsCombineWithPlus() {
        assertEquals(
                Controller.BUTTON_A | Controller.BUTTON_RIGHT,
                of("10:a+right").buttonsAt(10));
    }

    @Test
    void twoEventsOnOneFrameArePressedTogether() {
        var schedule = of("100-200:right", "150:a");

        assertEquals(Controller.BUTTON_RIGHT, schedule.buttonsAt(149));
        assertEquals(Controller.BUTTON_RIGHT | Controller.BUTTON_A, schedule.buttonsAt(150));
        assertEquals(Controller.BUTTON_RIGHT, schedule.buttonsAt(152));
    }

    @Test
    void oneFlagCanCarryAWholeSchedule() {
        var schedule = of("60:start,200-400:right");

        assertEquals(2, schedule.events().size());
        assertEquals(Controller.BUTTON_START, schedule.buttonsAt(60));
        assertEquals(Controller.BUTTON_RIGHT, schedule.buttonsAt(300));
    }

    @Test
    void anEmptyScheduleNeverPressesAnything() {
        var schedule = InputSchedule.empty(PRESS_FRAMES);

        assertTrue(schedule.isEmpty());
        assertEquals(0, schedule.buttonsAt(0));
        assertEquals(0, schedule.buttonsAt(100_000));
    }

    @Test
    void aSpecWithNoColonIsRejected() {
        assertTrue(refused("60start").getMessage().contains("colon"));
    }

    @Test
    void aFrameNumberThatIsNotANumberIsRejected() {
        assertTrue(refused("soon:start").getMessage().contains("soon"));
    }

    @Test
    void anUnknownButtonIsRejectedByName() {
        var message = refused("60:strat").getMessage();

        assertTrue(message.contains("strat"));
        assertTrue(message.contains("start"), "the message should offer the real names");
    }

    @Test
    void aHoldThatEndsBeforeItStartsIsRejected() {
        assertTrue(refused("400-200:right").getMessage().contains("200"));
    }

    @Test
    void aPulseThatRepeatsEveryZeroFramesIsRejected() {
        assertTrue(refused("60/0:start").getMessage().contains("zero"));
    }

    @Test
    void theParsedShapeIsWhatTheReportEchoesBack() {
        var event = of("60/40x3:start").events().getFirst();

        assertEquals(InputSchedule.Kind.PULSE, event.kind());
        assertEquals(60, event.from());
        assertEquals(40, event.every());
        assertEquals(3, event.count());
        assertEquals(List.of("start"), InputSchedule.describe(event.buttons()));
    }

    @Test
    void buttonsAreNamedInTheOrderTheConsoleNamesThem() {
        assertEquals(
                List.of("a", "start", "left"),
                InputSchedule.describe(
                        Controller.BUTTON_LEFT | Controller.BUTTON_A | Controller.BUTTON_START));
    }
}
