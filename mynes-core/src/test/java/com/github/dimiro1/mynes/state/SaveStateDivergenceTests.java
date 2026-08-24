package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.video.FrameAnalysis;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Whether the state is the whole state.
 * <p>
 * This is what the format is for, and it is only possible because nothing in the machine reads a
 * clock or a random number. Take a state; let the original run on and write down what it does; put
 * the state into a <em>different</em> machine and let that run the same distance. Any field the
 * state forgot is a field the second machine still holds the wrong value in, and the two run apart.
 * <p>
 * Three things make the difference between this biting and this passing for the wrong reason.
 * <ul>
 *   <li>The second machine is deliberately somewhere else when the state lands -- a different number
 *       of frames in, having been given different buttons. Loading into a freshly built console is
 *       the weak version: a forgotten field would hold its power-on value in both machines and the
 *       test would agree while the format was wrong.</li>
 *   <li>Input after the load is a pure function of how many frames have passed <em>since</em> the
 *       load, so both machines get the same buttons on the same frames. Otherwise the two diverge
 *       because of the controller rather than because of the state.</li>
 *   <li>The state bytes are compared as well as the pictures, and they are the stronger of the two.
 *       Every vendored ROM here finishes its work and then sits on a results screen forever -- see
 *       {@link #ANIMATED_FRAMES} -- so the picture alone would stop being evidence quite early. The
 *       state bytes carry the cycle counters, the interrupt latches and every APU channel nobody
 *       can hear, and they never stop moving.</li>
 * </ul>
 */
class SaveStateDivergenceTests {

    /**
     * Where the state is taken from.
     * <p>
     * Early on purpose. These ROMs do all their drawing in the first sixty frames or so and then
     * hold the result forever, so this is the one part of the run with tiles being fetched, sprites
     * being evaluated and the shift registers holding something -- which is exactly the state most
     * worth proving travels. A state taken at frame 200 would be a state of a machine sitting still.
     */
    private static final int SAVE_ON_FRAME = 5;

    /**
     * How many CPU cycles past that frame's start the state is taken, which lands the beam around
     * scanline 50 and puts it in the middle of a sprite evaluation.
     * <p>
     * This is not a detail. A state taken at a frame boundary does not test most of the PPU's
     * pipeline at all: the shift registers are about to be reloaded by the next line's fetches, the
     * sprite evaluation state machine is about to be restarted, and the eight sprite output units are
     * about to be rewritten -- so those fields can be left out of the format entirely and nothing
     * diverges. Verified by removing them one at a time, which is a test that only fails from here.
     * The same goes for the CPU's half-executed instruction, and for a save taken from the REPL,
     * which can land anywhere.
     */
    private static final int MID_FRAME_CYCLES = 5710;

    /**
     * How far both machines run after the load, comparing the whole machine each frame.
     */
    private static final int FRAMES_AFTER_LOAD = 200;

    /**
     * How far the picture comparison runs. Shorter, because it is only evidence while the ROM is
     * still redrawing: past this these ROMs sit on their results screen and every frame hashes the
     * same, which the guard in that test refuses to accept as a pass.
     */
    private static final int ANIMATED_FRAMES = 60;

    /**
     * Where the other machine is when the state arrives. Not {@link #SAVE_ON_FRAME}, and not a
     * multiple of it.
     */
    private static final int FRAMES_ON_THE_OTHER_MACHINE = 137;

    @ParameterizedTest
    @ValueSource(strings = {
            "/nestest/nestest.nes",
            "/ppu-vbl-nmi/07-nmi_on_timing.nes",
            "/apu-test/3-irq_flag.nes",
            "/mmc3-test-2/1-clocking.nes",
            // The only vendored ROMs with rendering switched on at the save point, which is what
            // makes the background and sprite pipelines part of what is being tested rather than
            // dead weight. Without one of these, most of the PPU's chunk could be deleted and every
            // assertion here would still pass.
            "/ppu-sprite-overflow/02-details.nes",
            "/ppu-sprite-overflow/04-obscure.nes"})
    void aStateReloadedRunsOnExactlyAsTheMachineItCameFromDid(final String rom) throws IOException {
        var original = load(rom);
        runToSavePoint(original);

        var state = save(original);
        var expected = traceOf(original, FRAMES_AFTER_LOAD);

        // Somewhere else entirely, so a forgotten field is holding something different here rather
        // than coincidentally the same thing.
        var other = load(rom);
        runElsewhere(other);

        SaveState.read(other, new ByteArrayInputStream(state));

        var actual = traceOf(other, FRAMES_AFTER_LOAD);

        for (var i = 0; i < expected.size(); i++) {
            assertEquals(
                    expected.get(i).state(),
                    actual.get(i).state(),
                    "the machine diverged " + (i + 1) + " frames after the state was loaded");
        }
    }

    /**
     * The picture, over the window where these ROMs still change it. Weaker than the state bytes and
     * kept anyway, because it is the thing a person would notice, and because a field that reaches
     * the screen is a different class of mistake from one that does not.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/nestest/nestest.nes",
            "/mmc3-test-2/1-clocking.nes",
            "/ppu-sprite-overflow/04-obscure.nes"})
    void aStateReloadedDrawsTheSamePictures(final String rom) throws IOException {
        var original = load(rom);
        runToSavePoint(original);

        var state = save(original);
        var expected = traceOf(original, ANIMATED_FRAMES);

        assertTrue(
                new HashSet<>(expected.stream().map(Step::picture).toList()).size() > 1,
                rom + " draws one unchanging picture over these frames, so comparing it proves"
                        + " nothing -- move the window or drive the ROM harder");

        var other = load(rom);
        runElsewhere(other);

        SaveState.read(other, new ByteArrayInputStream(state));

        var actual = traceOf(other, ANIMATED_FRAMES);

        for (var i = 0; i < expected.size(); i++) {
            assertEquals(
                    expected.get(i).picture(),
                    actual.get(i).picture(),
                    "the picture diverged " + (i + 1) + " frames after the state was loaded");
        }
    }

    /**
     * The machine has to come back identical at the moment of the load, not merely converge on it
     * over the following frames.
     */
    @Test
    void aMachineLoadedFromAStateWritesTheSameStateBackOut() throws IOException {
        var original = load("/mmc3-test-2/1-clocking.nes");
        runToSavePoint(original);

        var state = save(original);

        var other = load("/mmc3-test-2/1-clocking.nes");
        runElsewhere(other);
        SaveState.read(other, new ByteArrayInputStream(state));

        assertArrayEquals(state, save(other), "the machine did not come back the same");
    }

    /**
     * The one field the overclock puts in the state: how many repeats of the current line the beam
     * is part way through.
     * <p>
     * The setting itself is deliberately left out, being the Hacks menu's rather than the machine's
     * -- so this is what stops a state taken on the twentieth of 131 identical lines from resuming
     * as though it were on the first. Both machines are told the same number of lines, because a
     * replay of a run is a replay of the run that happened; what is being proved is that the count
     * travelled, not that the setting did.
     */
    @Test
    void aStateTakenOnAnExtraLineRunsOnExactlyAsTheMachineItCameFromDid() throws IOException {
        var rom = "/ppu-sprite-overflow/04-obscure.nes";
        var overclock = new Overclock(60, 0);

        var original = load(rom);
        original.getPPU().setOverclock(overclock);
        runToSavePoint(original);
        runOntoAnExtraLine(original);

        var state = save(original);
        var expected = traceOf(original, 40);

        var other = load(rom);
        other.getPPU().setOverclock(overclock);
        runElsewhere(other);

        SaveState.read(other, new ByteArrayInputStream(state));

        var actual = traceOf(other, 40);

        for (var i = 0; i < expected.size(); i++) {
            assertEquals(
                    expected.get(i).state(),
                    actual.get(i).state(),
                    "the machine diverged " + (i + 1) + " frames after the state was loaded");
        }
    }

    /**
     * The picture is not needed to put the machine back -- every visible pixel is rewritten every
     * frame -- but it is needed for the machine to <em>look</em> like it came back, which is what
     * somebody loading a slot in the window sees.
     */
    @Test
    void thePictureComesBackWithTheState() throws IOException {
        var original = load("/nestest/nestest.nes");
        runToSavePoint(original);

        var state = save(original);
        var picture = FrameAnalysis.hash(original.getPPU().getFrameBuffer());

        var other = load("/nestest/nestest.nes");
        runElsewhere(other);

        SaveState.read(other, new ByteArrayInputStream(state));

        assertEquals(
                picture,
                FrameAnalysis.hash(other.getPPU().getFrameBuffer()),
                "the framebuffer travelled, so the screen does not flash the old game");
    }

    // ================================================================================== internals

    /**
     * What one frame looked like, from the outside and from the inside.
     */
    private record Step(long picture, String state) {
    }

    /**
     * Runs to the point the state is taken from, which is deliberately not a frame boundary.
     * <p>
     * No buttons yet: these ROMs start drawing on their own, and stopping this early is what catches
     * them mid-draw.
     */
    private static void runToSavePoint(final NES nes) {
        for (var i = 0; i < SAVE_ON_FRAME; i++) {
            advanceFrame(nes);
        }

        for (var i = 0; i < MID_FRAME_CYCLES; i++) {
            nes.tick();
        }
    }

    /**
     * Runs on until the beam is on a line the overclock is running again, and then some way into it
     * -- so that the state is taken where the count of repeats is a number nothing else could
     * reconstruct.
     */
    private static void runOntoAnExtraLine(final NES nes) {
        var ppu = nes.getPPU();

        while (!ppu.isOnExtraLine()) {
            nes.tick();
        }

        // Twenty lines in, counted in CPU cycles because that is what a tick is: three dots each.
        for (var i = 0; i < 20 * 341 / 3; i++) {
            nes.tick();
        }

        assertTrue(ppu.isOnExtraLine(), "twenty lines on and still repeating, which is the point");
    }

    /**
     * Puts a machine somewhere that is not the save point, with buttons the original never saw.
     */
    private static void runElsewhere(final NES nes) {
        nes.getController1().setButtons(Controller.BUTTON_A | Controller.BUTTON_RIGHT);

        for (var i = 0; i < FRAMES_ON_THE_OTHER_MACHINE; i++) {
            advanceFrame(nes);
        }

        nes.getController1().setButtons(0);
    }

    /**
     * Runs on, recording both the picture and the whole machine after every frame, so a failure says
     * which frame went wrong rather than only that something did.
     * <p>
     * The buttons depend on nothing but how many frames have passed since this started, which is
     * what lets the same schedule be replayed on the machine the state was loaded into.
     */
    private static List<Step> traceOf(final NES nes, final int frames) {
        var steps = new ArrayList<Step>(frames);

        for (var i = 0; i < frames; i++) {
            nes.getController1().setButtons(buttonsFor(i));
            advanceFrame(nes);

            steps.add(new Step(FrameAnalysis.hash(nes.getPPU().getFrameBuffer()), fingerprint(nes)));
        }

        nes.getController1().setButtons(0);

        return steps;
    }

    private static int buttonsFor(final int framesSinceLoad) {
        return (framesSinceLoad / 20) % 2 == 0 ? Controller.BUTTON_START : 0;
    }

    /**
     * The whole machine, as something an assertion can print a difference between. The state's own
     * bytes, which is every field the format carries.
     */
    private static String fingerprint(final NES nes) {
        try {
            return Long.toHexString(FrameAnalysis.hash(nes.getPPU().getFrameBuffer()))
                    + " " + java.util.Arrays.hashCode(save(nes));
        } catch (IOException e) {
            throw new IllegalStateException("a state written to memory cannot fail", e);
        }
    }

    private static NES load(final String resource) throws IOException {
        try (var rom = SaveStateDivergenceTests.class.getResourceAsStream(resource)) {
            assertNotNull(rom, resource);
            return new NES(Cart.load(rom.readAllBytes(), resource));
        }
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
