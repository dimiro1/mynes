package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.video.FrameAnalysis;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Going backwards.
 * <p>
 * The claim being tested is a strong one and worth stating plainly: a machine that has been rewound
 * to frame 60 is not approximately the machine that was at frame 60, it is that machine, byte for
 * byte. So every test below compares the state's own bytes rather than only the picture -- the
 * picture stops being evidence as soon as a ROM settles down, and the state bytes carry the cycle
 * counters, the interrupt latches and every APU channel nobody can hear.
 * <p>
 * That the format survives being written and read at all is {@link SaveStateDivergenceTests}'s job,
 * and this deliberately does not repeat it. What is left here is the ring's own arithmetic: which
 * entry a rewind of one lands on, what running out does, and what happens to the history when
 * somebody plays on from the middle of it.
 */
class RewindTests {

    /**
     * Enough for every test here to keep everything it captures, so a ring that evicts is something
     * a test asks for rather than something it stumbles into.
     */
    private static final int ROOMY = 400;

    /**
     * Where the run this is measured against starts pressing Start, and how long for. Copied from
     * the frames {@code ReplTests} already establishes are enough for nestest to notice a button and
     * redraw -- which is what makes the second timeline below genuinely a second timeline.
     */
    private static final int PRESS_AT = 60;
    private static final int PRESS_FOR = 30;

    /**
     * Rewinding lands on the machine that was there, not on something close to it. The comparison is
     * against a second machine that simply ran the shorter distance, which is the only definition of
     * "where it was" that does not come from the thing being tested.
     */
    @Test
    void rewindingGoesBackToTheFrameItLeft() throws IOException {
        var nes = load();
        var rewind = new Rewind(ROOMY);

        // Arming captures at once, so the machine as it stands is the floor of the history rather
        // than something the first frame has already moved off.
        rewind.capture(nes);

        for (var i = 0; i < 90; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        assertEquals(30, rewind.rewind(nes, 30));
        assertEquals(60, nes.getPPU().getFrame());

        var straight = load();
        for (var i = 0; i < 60; i++) {
            advanceFrame(straight);
        }

        assertEquals(
                FrameAnalysis.hash(straight.getPPU().getFrameBuffer()),
                FrameAnalysis.hash(nes.getPPU().getFrameBuffer()),
                "the picture is the one that was on screen at frame 60");
        assertArrayEquals(save(straight), save(nes), "and so is every field behind it");
    }

    /**
     * One frame is the step the window takes, once per display tick, so it is the one that has to be
     * exactly right: the entry on top is where the machine already is, and a rewind that loaded it
     * would hold still while somebody held the key down.
     */
    @Test
    void rewindingOneFrameMovesOneFrame() throws IOException {
        var nes = load();
        var rewind = new Rewind(ROOMY);
        rewind.capture(nes);

        for (var i = 0; i < 20; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        for (var frame = 19; frame >= 0; frame--) {
            assertEquals(1, rewind.rewind(nes, 1));
            assertEquals(frame, nes.getPPU().getFrame());
        }
    }

    /**
     * Held down too long, which is what a key does. Parking on the oldest frame still kept is the
     * answer, rather than an error somebody holding a key cannot act on.
     */
    @Test
    void rewindingStopsAtTheOldestFrameItKept() throws IOException {
        var nes = load();
        var rewind = new Rewind(10);
        rewind.capture(nes);

        for (var i = 0; i < 50; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        assertEquals(9, rewind.rewind(nes, 99), "nine, not ninety-nine and not ten");
        assertEquals(41, nes.getPPU().getFrame());
        assertEquals(0, rewind.rewindable(), "and it is parked there");

        assertEquals(0, rewind.rewind(nes, 1), "asking again from the floor moves nothing");
        assertEquals(41, nes.getPPU().getFrame());
    }

    /**
     * The frames rewound past are gone. Playing on writes a different future over them, and it is
     * that future the next rewind walks back through -- otherwise holding the key twice would take
     * somebody somewhere they had never been.
     */
    @Test
    void theRingHoldsTheNewTimelineAfterResuming() throws IOException {
        var nes = load();
        var rewind = new Rewind(ROOMY);
        rewind.capture(nes);

        var untouched = new ArrayList<String>();

        for (var i = 0; i < PRESS_AT + PRESS_FOR; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
            untouched.add(fingerprint(nes));
        }

        assertEquals(PRESS_FOR, rewind.rewind(nes, PRESS_FOR));
        assertEquals(PRESS_AT, nes.getPPU().getFrame());

        // The same frames again with a button the first run never saw, so what is captured over the
        // top of the discarded entries really is a different machine.
        nes.getController1().setButtons(Controller.BUTTON_START);

        for (var i = 0; i < PRESS_FOR; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        nes.getController1().setButtons(0);

        var played = fingerprint(nes);

        assertNotEquals(untouched.getLast(), played,
                "pressing Start has to have changed something, or there is only one timeline here"
                        + " and this proves nothing");

        for (var i = 0; i < 10; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        assertEquals(10, rewind.rewind(nes, 10));
        assertEquals(played, fingerprint(nes), "back onto the timeline that was actually played");
    }

    /**
     * A machine nobody was recording, and one recorded for exactly one frame. Both have nowhere to
     * go, and both have to leave the machine alone rather than reload the frame it is already on --
     * which is what makes it free to ask on every frame of a run.
     */
    @Test
    void aMachineThatNeverCapturedCannotRewind() throws IOException {
        var nes = load();
        var rewind = new Rewind(10);

        for (var i = 0; i < 5; i++) {
            advanceFrame(nes);
        }

        var before = save(nes);

        assertEquals(0, rewind.rewindable());
        assertEquals(0, rewind.rewind(nes, 1));
        assertArrayEquals(before, save(nes), "the machine was not touched");

        rewind.capture(nes);

        assertEquals(0, rewind.rewindable(), "one state is where the machine already is");
        assertEquals(0, rewind.rewind(nes, 1));
        assertArrayEquals(before, save(nes));
    }

    @Test
    void aRingTooSmallToRewindIsRefused() {
        for (var capacity : List.of(-1, 0, 1)) {
            assertThrows(IllegalArgumentException.class, () -> new Rewind(capacity),
                    capacity + " states can never rewind");
        }

        assertEquals(2, new Rewind(2).capacity());
        assertEquals(1, new Rewind(2).interval(), "a state for every frame unless asked otherwise");
        assertThrows(IllegalArgumentException.class, () -> new Rewind(10, 0));
    }

    // ================================================================================= intervals

    /**
     * What the window keeps: a state every other frame, which is half the cost, twice the history
     * for the memory, and a rewind that gives back two frames per tick instead of one.
     */
    @Test
    void aWiderIntervalKeepsEveryOtherFrame() throws IOException {
        var nes = load();
        var rewind = new Rewind(ROOMY, 2);

        // Frame 0, then 2, 4, ... 20. Eleven states over twenty-one frames.
        rewind.capture(nes);

        for (var i = 0; i < 20; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        assertEquals(10, rewind.rewindable(), "eleven states, ten of them to go back onto");

        for (var frame = 18; frame >= 0; frame -= 2) {
            assertEquals(1, rewind.rewind(nes, 1));
            assertEquals(frame, nes.getPPU().getFrame(), "two frames a step");
        }

        assertEquals(0, rewind.rewind(nes, 1), "and the floor is the first frame it kept");
    }

    /**
     * The odd frames in between are still somewhere to go back <em>from</em>. Standing on frame 21
     * with the newest state taken on 20, the first step back is onto 20 rather than past it to 18 --
     * otherwise letting go of the key would land somewhere nobody asked for.
     */
    @Test
    void aFrameWithNoStateOfItsOwnStepsBackOntoTheNewestOne() throws IOException {
        var nes = load();
        var rewind = new Rewind(ROOMY, 2);
        rewind.capture(nes);

        for (var i = 0; i < 21; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        assertEquals(21, nes.getPPU().getFrame(), "an odd frame, and the newest state is on 20");

        assertEquals(1, rewind.rewind(nes, 1));
        assertEquals(20, nes.getPPU().getFrame(), "one frame, not three");

        assertEquals(1, rewind.rewind(nes, 1));
        assertEquals(18, nes.getPPU().getFrame(), "and two from there on");
    }

    /**
     * Resuming lays the new timeline down on the same spacing the old one had. A capture that
     * carried on counting from where the rewind interrupted it would put every state after it on the
     * odd frames, and the two halves of the ring would disagree about what a step is.
     */
    @Test
    void resumingKeepsTheStatesOnTheSameFrames() throws IOException {
        var nes = load();
        var rewind = new Rewind(ROOMY, 2);
        rewind.capture(nes);

        for (var i = 0; i < 20; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        rewind.rewind(nes, 5);
        assertEquals(10, nes.getPPU().getFrame());

        for (var i = 0; i < 6; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        assertEquals(16, nes.getPPU().getFrame());

        assertEquals(1, rewind.rewind(nes, 1));
        assertEquals(14, nes.getPPU().getFrame(), "still even, still two apart");
    }

    /**
     * The two machines do not hold the same number of frames of the same wall-clock history, and
     * neither number is thirty times something round.
     */
    @Test
    void secondsOfHistoryCountDifferentlyOnTheTwoMachines() {
        assertEquals(1803, Rewind.framesFor(Region.NTSC, 30));
        assertEquals(1500, Rewind.framesFor(Region.PAL, 30));
    }

    // ================================================================================== internals

    private static NES load() throws IOException {
        var resource = "/nestest/nestest.nes";

        try (var rom = RewindTests.class.getResourceAsStream(resource)) {
            assertNotNull(rom, resource);
            return new NES(Cart.load(rom.readAllBytes(), resource));
        }
    }

    /**
     * The whole machine, as something an assertion can print a difference between.
     */
    private static String fingerprint(final NES nes) throws IOException {
        return Long.toHexString(FrameAnalysis.hash(nes.getPPU().getFrameBuffer()))
                + " " + java.util.Arrays.hashCode(save(nes));
    }

    private static byte[] save(final NES nes) throws IOException {
        var out = new ByteArrayOutputStream();

        SaveState.write(nes, out);

        return out.toByteArray();
    }

    private static void advanceFrame(final NES nes) {
        var ppu = nes.getPPU();
        var frame = ppu.getFrame();

        do {
            nes.tick();
        } while (ppu.getFrame() == frame);
    }
}
