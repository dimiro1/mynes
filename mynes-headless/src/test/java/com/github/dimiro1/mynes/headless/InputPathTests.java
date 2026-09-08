package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A scheduled press arriving at the game.
 * <p>
 * Everything else about the input is checked a piece at a time -- the grammar in
 * {@link InputScheduleTests}, the controller's shift register in
 * {@code StandardControllerTests} -- and none of it would notice if the
 * schedule were never handed to the machine at all. This is the one test that follows a press the
 * whole way: from a spec, through {@link Session#setButtons}, out of $4016 a bit at a time, and
 * into the memory of a program that read it.
 * <p>
 * The program reads both ports, which is the only way to say that the two pads are two pads: a
 * second player wired to the first would read out of $4016 exactly as a real press does, and every
 * test that only ever looked at one port would pass.
 * <p>
 * The cartridge is built here rather than vendored, the way {@code ppu/NESIntegrationTests} does
 * it, so the test depends on nothing but the emulator.
 */
class InputPathTests {
    /**
     * Where the reader program publishes a finished reading.
     * <p>
     * It builds the byte at $10 and copies it here once all eight bits are in. Two addresses rather
     * than one because the program never stops: sampling the accumulator between frames would catch
     * it halfway through a shift, which reads as a single stray bit rather than as a button.
     */
    private static final int RESULT = 0x0011;

    /**
     * The same for player two, out of $4017. A second address rather than a second run of the
     * program, because the console latches both pads with one write and a game reads them in step.
     */
    private static final int RESULT_TWO = 0x0013;

    /**
     * A program that reads the pad over and over and stores what it finds.
     * <p>
     * Strobe high then low to latch the buttons, then eight reads apiece of $4016 and $4017, each
     * of which hands back one button in bit 0, rolled into a byte through the carry. The order the
     * console shifts them out in is A, B, Select, Start, Up, Down, Left, Right, so each byte lands
     * with A at bit 7 -- the reverse of {@link Controller}'s masks, which is what {@link #shifted}
     * undoes.
     * <p>
     * The two ports are read alternately inside one loop, which is what a two player game does and
     * is the arrangement that would catch a bus reading one port for both: $4016 and $4017 are
     * decoded separately and each clocks its own shift register on.
     */
    private static final int[] PAD_READER = {
            // start: LDA #$01
            0xA9, 0x01,
            //        STA $4016        latch both pads
            0x8D, 0x16, 0x40,
            //        LDA #$00
            0xA9, 0x00,
            //        STA $4016        let them shift out
            0x8D, 0x16, 0x40,
            //        LDX #$08         eight buttons
            0xA2, 0x08,
            //        LDA #$00
            0xA9, 0x00,
            //        STA $10          start player one's byte empty
            0x85, 0x10,
            //        STA $12          and player two's
            0x85, 0x12,
            // read:  LDA $4016        bit 0 is player one's next button
            0xAD, 0x16, 0x40,
            //        LSR A            into the carry
            0x4A,
            //        ROL $10          and into the byte
            0x26, 0x10,
            //        LDA $4017        the same again for player two
            0xAD, 0x17, 0x40,
            //        LSR A
            0x4A,
            //        ROL $12
            0x26, 0x12,
            //        DEX
            0xCA,
            //        BNE read         back fifteen bytes, to the LDA $4016
            0xD0, 0xF1,
            //        LDA $10
            0xA5, 0x10,
            //        STA $11          publish, now that all eight are in
            0x85, 0x11,
            //        LDA $12
            0xA5, 0x12,
            //        STA $13
            0x85, 0x13,
            //        JMP start        round again, forever
            0x4C, 0x00, 0xC0,
    };

    /**
     * The byte {@link #PAD_READER} would leave for a given set of buttons.
     */
    private static int shifted(final int mask) {
        var result = 0;
        var masks = new int[]{
                Controller.BUTTON_A, Controller.BUTTON_B,
                Controller.BUTTON_SELECT, Controller.BUTTON_START,
                Controller.BUTTON_UP, Controller.BUTTON_DOWN,
                Controller.BUTTON_LEFT, Controller.BUTTON_RIGHT,
        };

        for (var button : masks) {
            result = (result << 1) | ((mask & button) != 0 ? 1 : 0);
        }

        return result;
    }

    private static Session sessionRunning() {
        var image = new byte[16 + 0x4000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;  // one PRG bank, mirrored into both $8000 and $C000

        for (var i = 0; i < PAD_READER.length; i++) {
            image[16 + i] = (byte) PAD_READER[i];
        }

        // The reset vector, pointing at the first instruction.
        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0xC0;

        return new Session(
                new NES(Cart.load(image, "input-path.nes")),
                Palettes.defaultPalette().colours(),
                VideoFilter.NONE,
                FilterStrength.defaultStrength(),
                false,
                false,
                null);
    }

    /**
     * What the program has most recently read out of player one's controller.
     */
    private static int lastRead(final Session session) {
        return session.readCPU(RESULT, 1)[0];
    }

    /**
     * And out of player two's.
     */
    private static int lastReadTwo(final Session session) {
        return session.readCPU(RESULT_TWO, 1)[0];
    }

    private static Session runSchedule(final InputSchedule schedule, final long frames)
            throws IOException {
        var session = sessionRunning();

        for (var frame = 1L; frame <= frames; frame++) {
            session.setButtons(schedule.buttonsAt(frame));
            session.advanceFrame();
        }

        return session;
    }

    /**
     * The second pad is a second pad. Nothing on the command line authors input for it -- a movie
     * is the only thing that moves it -- so this is where {@link Session#setButtons2} is held to
     * arriving at $4017 and at nothing else.
     */
    @Test
    void theSecondPadArrivesOnItsOwnPort() throws Exception {
        var session = sessionRunning();

        session.setButtons(Controller.BUTTON_A);
        session.setButtons2(Controller.BUTTON_RIGHT);

        for (var frame = 0; frame < 3; frame++) {
            session.advanceFrame();
        }

        assertEquals(shifted(Controller.BUTTON_A), lastRead(session), "out of $4016");
        assertEquals(shifted(Controller.BUTTON_RIGHT), lastReadTwo(session), "out of $4017");
    }

    /**
     * And neither port answers for the other, which is the failure a test of one pad could never
     * see: a second player wired to the first would read out of $4016 exactly as a real press does.
     */
    @Test
    void neitherPadAnswersForTheOther() throws Exception {
        var session = sessionRunning();

        session.setButtons2(Controller.BUTTON_START);

        for (var frame = 0; frame < 3; frame++) {
            session.advanceFrame();
        }

        assertEquals(0, lastRead(session), "player one is holding nothing");
        assertEquals(shifted(Controller.BUTTON_START), lastReadTwo(session));
    }

    @Test
    void aScheduledPressReachesTheGame() throws Exception {
        var schedule = InputSchedule.parse(java.util.List.of("50:start"), 2);

        assertEquals(0, lastRead(runSchedule(schedule, 49)), "nothing is pressed yet");
        assertEquals(shifted(Controller.BUTTON_START), lastRead(runSchedule(schedule, 50)));
        assertEquals(0, lastRead(runSchedule(schedule, 60)), "and it is let go of again");
    }

    @Test
    void aHoldIsStillDownFramesLater() throws Exception {
        var schedule = InputSchedule.parse(java.util.List.of("10-100:right"), 2);

        assertEquals(shifted(Controller.BUTTON_RIGHT), lastRead(runSchedule(schedule, 50)));
    }

    @Test
    void twoButtonsAtOnceBothArrive() throws Exception {
        var schedule = InputSchedule.parse(java.util.List.of("20:a+right"), 2);
        var expected = shifted(Controller.BUTTON_A | Controller.BUTTON_RIGHT);

        assertEquals(expected, lastRead(runSchedule(schedule, 20)));
    }

    /**
     * The form that gets a real cartridge past its menus. Checked here for the thing that is easy
     * to get wrong about it -- that the first press happens on the frame named rather than one
     * period later.
     */
    @Test
    void aPulsePressesOnTheFrameItNames() throws Exception {
        var schedule = InputSchedule.parse(java.util.List.of("60/40:start"), 2);
        var start = shifted(Controller.BUTTON_START);

        assertEquals(start, lastRead(runSchedule(schedule, 60)));
        assertEquals(start, lastRead(runSchedule(schedule, 100)));
        assertEquals(start, lastRead(runSchedule(schedule, 140)));
        assertEquals(0, lastRead(runSchedule(schedule, 80)));
    }

    /**
     * A press is set before the frame it belongs to is emulated, so a game that polls the pad
     * anywhere in its frame sees it. Anything else would make a one frame press a coin toss.
     */
    @Test
    void aSingleFrameOfPressIsLongEnoughToBeRead() throws Exception {
        var schedule = InputSchedule.parse(java.util.List.of("30:b"), 1);

        assertEquals(shifted(Controller.BUTTON_B), lastRead(runSchedule(schedule, 30)));
    }

    @Test
    void thePadReaderIsActuallyReadingTheThingUnderTest() throws Exception {
        // If the program were not working, every assertion above would pass by reading zero. This
        // is the one that says the zeros mean something.
        var pressed = lastRead(
                runSchedule(InputSchedule.parse(java.util.List.of("20:start"), 2), 20));

        assertTrue(pressed != 0, "the program should have shifted a pressed button in");
    }
}
