package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.state.Movie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.LongPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The emulation thread with a debugger attached to it.
 * <p>
 * The rest of this class has never been tested -- it is a thread and a sound card, and the parts
 * worth checking live elsewhere. What is worth checking here is the one thing that cannot be reached
 * from anywhere else: that arming the debugger really does move the loop onto the instruction at a
 * time path, that a breakpoint really does stop the machine part way through a frame, and that
 * letting it go again really does clear the halt rather than stopping once more a moment later.
 * <p>
 * A sound device is not needed. {@link AudioOutput#open()} says so in the log and runs silently when
 * there is not one, which is exactly what happens on the machine that runs CI.
 * <p>
 * The movie tests at the end are here for the same reason as the rewind ones: {@code MovieTests}
 * proves the recorder's own arithmetic, and this proves that the loop asks it the right questions on
 * the right frames -- which is the one part of the feature that only exists on this thread.
 */
class EmulatorRunnerTests {
    /**
     * Long enough for a frame at any speed on any machine, short enough that a broken loop fails the
     * run rather than hanging it.
     */
    private static final long PATIENCE_SECONDS = 10;

    /**
     * The program, at $8000: a store, then a spin. Every address below is one of these.
     *
     * <pre>
     * 8000  A9 42     LDA #$42
     * 8002  8D 00 03  STA $0300
     * 8005  4C 05 80  JMP $8005
     * </pre>
     */
    private static final int STA = 0x8002;
    private static final int SPIN = 0x8005;

    /**
     * How much history the rewind tests below give the machine. Small, because the point is to watch
     * the frame counter move rather than to hold anything for long -- and because every entry is a
     * whole save state.
     */
    private static final int REWIND_FRAMES = 120;

    private NES nes;
    private Debugger debugger;
    private EmulatorRunner runner;
    private ArrayBlockingQueue<Debugger.Stop> stops;

    /**
     * Anything that went wrong writing a movie, which happens on the emulation thread and so cannot
     * fail a test by throwing.
     */
    private ArrayBlockingQueue<Exception> failures;

    @TempDir
    private Path directory;

    @BeforeEach
    void setUp() {
        nes = new NES(Cart.load(rom(), "runner.nes"));
        debugger = new Debugger();
        debugger.attach(nes);
        stops = new ArrayBlockingQueue<>(16);
        failures = new ArrayBlockingQueue<>(16);

        runner = new EmulatorRunner(nes, new ScreenComponent(), debugger, REWIND_FRAMES);
        runner.setStopListener(stops::add);
    }

    @AfterEach
    void tearDown() {
        runner.stop();
    }

    @Test
    void aBreakpointStopsTheMachinePartWayThroughAFrame() throws Exception {
        debugger.addBreakpoint(SPIN);
        runner.start();

        var stop = waitForStop();

        assertEquals(Debugger.Reason.BREAKPOINT, stop.reason());
        assertEquals(SPIN, stop.pc());
        assertTrue(runner.isPaused(), "a breakpoint pauses the machine the way the menu item does");
    }

    @Test
    void aWatchpointNamesTheInstructionThatWrote() throws Exception {
        debugger.addWatchpoint(0x0300);
        runner.start();

        var stop = waitForStop();

        assertEquals(Debugger.Reason.WATCHPOINT, stop.reason());
        assertEquals(Debugger.Access.WRITE, stop.access());
        assertEquals(0x0300, stop.address());
        assertEquals(0x42, stop.value());
        assertEquals(STA, stop.by());
    }

    /**
     * The machine is stopped and a step is the one thing that runs it anyway, which is why the
     * debugger is asked about stepping before the pause is looked at.
     */
    @Test
    void aStepRunsOneInstructionOnAStoppedMachine() throws Exception {
        runner.start();
        runner.breakNow();
        waitForStop();

        runner.stepInstruction();

        assertEquals(Debugger.Reason.STEP, waitForStop().reason());
        assertTrue(runner.isPaused(), "and it is stopped again afterwards");
    }

    @Test
    void aFrameStepRunsToTheEndOfTheFrame() throws Exception {
        runner.start();
        runner.breakNow();
        waitForStop();

        var before = nes.getPPU().getFrame();
        runner.stepFrame();

        assertEquals(Debugger.Reason.FRAME, waitForStop().reason());
        assertTrue(nes.getPPU().getFrame() > before, "a frame should have gone by");
    }

    /**
     * The spin at the end of the program jumps to itself, so a step taken from a breakpoint on it
     * lands straight back on the same breakpoint. Both stop the machine in the same place and the
     * only difference is which word the window uses, so the more particular of the two wins: that
     * there is a breakpoint here is worth knowing, and that a step was asked for is already known by
     * whoever asked.
     */
    @Test
    void aStepThatLandsOnABreakpointSaysSo() throws Exception {
        debugger.addBreakpoint(SPIN);
        runner.start();
        waitForStop();

        runner.stepInstruction();

        assertEquals(Debugger.Reason.BREAKPOINT, waitForStop().reason());
        assertEquals(SPIN, nes.getCPU().getPC(), "the spin jumped to itself");
    }

    /**
     * Resuming has to forget the halt as well as unpause, and the two have to happen in that order
     * on the emulation thread. Done as two calls from here they could interleave, and the machine
     * would stop again one instruction later for no reason anybody could see.
     */
    @Test
    void lettingItGoForgetsTheHaltAsWellAsUnpausing() throws Exception {
        runner.start();
        runner.breakNow();

        assertEquals(Debugger.Reason.ASKED, waitForStop().reason());

        runner.resume();

        assertNull(stops.poll(1, TimeUnit.SECONDS), "it should not stop again on its own");
        assertFalse(runner.isPaused(), "and it should be running");
    }

    @Test
    void breakingStopsAMachineThatWasRunningFreely() throws Exception {
        runner.start();

        // Nothing armed, so this is the ordinary path until the moment it is asked to stop.
        assertFalse(debugger.isArmed(), "nothing to look for yet");

        runner.breakNow();

        assertEquals(Debugger.Reason.ASKED, waitForStop().reason());
    }

    @Test
    void aMachineWithNothingArmedRunsFrames() {
        runner.start();

        waitFor(frame -> frame >= 3, "the ordinary path should still run");

        assertNull(stops.poll(), "and nothing should have stopped it");
    }

    /**
     * The frame counter going down is the whole of the feature, and it is the one thing no other
     * test can see: {@code RewindTests} proves the ring lands on the right machine, and this proves
     * the loop actually asks it to, one frame per tick, for as long as the key is held.
     */
    @Test
    void holdingRewindRunsTheMachineBackwards() {
        runner.start();

        var played = waitFor(frame -> frame >= 30, "the machine never got going");

        runner.setRewinding(true);

        // Going back, rather than merely holding still -- which is what a pause would look like
        // from out here, and what a rewind that loaded the entry it was already standing on would
        // look like too.
        var rewound = waitFor(frame -> frame <= played - 10, "the machine never went backwards");

        runner.setRewinding(false);

        waitFor(frame -> frame > rewound, "and it plays on from wherever the rewind stopped");
    }

    /**
     * {@code rewind.seconds=0} builds no ring, and the key then has nothing to do rather than
     * something to refuse.
     */
    @Test
    void aMachineKeepingNoHistoryIgnoresTheRewindKey() {
        runner = new EmulatorRunner(nes, new ScreenComponent(), debugger, 0);
        runner.start();
        runner.setRewinding(true);

        waitFor(frame -> frame >= 5, "it should have carried on forwards");
    }

    /**
     * Pause is looked at first, so a frozen machine stays frozen. Two ideas about what the screen is
     * showing is worse than a key that does nothing.
     */
    @Test
    void aPausedMachineIsNotRewound() throws Exception {
        runner.start();

        var played = waitFor(frame -> frame >= 20, "the machine never got going");

        runner.setPaused(true);

        // Long enough for the loop to have gone round a few dozen times had it been rewinding.
        Thread.sleep(200);

        runner.setRewinding(true);
        Thread.sleep(200);

        assertTrue(nes.getPPU().getFrame() >= played, "a paused machine holds where it is");
    }

    // ==================================================================================== movies

    /**
     * The mask the loop latches is the mask that gets written down, for every frame that finished.
     * <p>
     * Both calls are posted before the thread starts, so they run before the first frame and the
     * movie starts at frame 0 with nothing missed off the front.
     */
    @Test
    void everyRecordedFrameCarriesTheMaskThatWasLatched() throws Exception {
        var path = directory.resolve("take.mnm");

        runner.setFrameInputSource(() -> Controller.BUTTON_A);
        runner.startRecording(List.of());
        runner.start();

        waitFor(frame -> frame >= 30, "the machine never got going");

        var movie = stopRecordingAndRead(path);

        assertEquals(0, movie.anchorFrame());
        assertTrue(movie.frameCount() >= 30, "it recorded what was played: " + movie.frameCount());

        for (var i = 0L; i < movie.frameCount(); i++) {
            assertEquals(Controller.BUTTON_A, movie.buttonsAt(i), "frame " + i);
        }
    }

    /**
     * The window keeps a state every <em>other</em> frame, so what {@code Rewind.rewind} answers is
     * states and what the recorder has to be told is frames. Passing the wrong one of the two would
     * leave the movie holding twice the frames the machine actually went back over, and the invariant
     * asserted here is what catches it: a movie holds exactly the frames between its anchor and where
     * the machine stands, however many of them were played twice on the way.
     */
    @Test
    void rewindingWhileRecordingDropsTheFramesItTookBack() throws Exception {
        var path = directory.resolve("rewound.mnm");

        runner.setFrameInputSource(() -> 0);
        runner.startRecording(List.of());
        runner.start();

        var played = waitFor(frame -> frame >= 60, "the machine never got going");

        runner.setRewinding(true);
        waitFor(frame -> frame <= 20, "the machine never went backwards");
        runner.setRewinding(false);

        // Paused rather than stopped: posted commands still run, so the movie can still be written,
        // and no more frames finish -- which is what makes the frame counter below worth reading.
        runner.setPaused(true);
        Thread.sleep(200);

        var atRest = nes.getPPU().getFrame();
        var movie = stopRecordingAndRead(path);

        assertEquals(atRest - movie.anchorFrame(), movie.frameCount());
        assertTrue(movie.frameCount() < played,
                "the frames that were taken back are not in it: " + movie.frameCount()
                        + " of " + played);
    }

    /**
     * A session recorded on one machine, played back on another that has never seen a key pressed --
     * and control handed back when it runs out, which is what the window turns into giving the
     * keyboard its game back.
     */
    @Test
    void aMoviePlaysBackAndHandsControlBackAtTheEnd() throws Exception {
        var path = directory.resolve("take.mnm");

        runner.setFrameInputSource(() -> Controller.BUTTON_A);
        runner.startRecording(List.of());
        runner.start();

        waitFor(frame -> frame >= 20, "the machine never got going");

        var movie = stopRecordingAndRead(path);
        runner.stop();

        var ended = new ArrayBlockingQueue<Boolean>(4);
        var second = new NES(Cart.load(rom(), "runner.nes"));

        // No history, so nothing here depends on the ring: the point is the cursor and the handover.
        runner = new EmulatorRunner(second, new ScreenComponent(), new Debugger(), 0);
        runner.setPlaybackEndedListener(() -> ended.add(true));
        runner.startPlayback(movie);
        runner.start();

        assertNotNull(
                ended.poll(PATIENCE_SECONDS, TimeUnit.SECONDS),
                "the movie never finished, or never said so");
        assertTrue(second.getPPU().getFrame() >= movie.frameCount(),
                "and every frame of it was played");
    }

    /**
     * Stops the recording, waits for the file to land, and reads it back.
     * <p>
     * The write happens on the emulation thread, so it is the file appearing rather than the call
     * returning that says it is done -- and it appears whole, since a movie is written through a
     * temporary and moved into place.
     */
    private Movie stopRecordingAndRead(final Path path) throws IOException, InterruptedException {
        runner.stopRecording(path, failures::add);

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);

        while (System.nanoTime() < deadline && !Files.exists(path)) {
            Thread.sleep(10);
        }

        assertNull(failures.poll(), "the movie should have been written without complaint");
        assertTrue(Files.exists(path), path + " was never written");

        return Movie.read(path);
    }

    /**
     * Waits for the frame counter to do something, and says where it got to when it does not.
     */
    private long waitFor(final LongPredicate condition, final String message) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);

        while (System.nanoTime() < deadline) {
            var frame = nes.getPPU().getFrame();

            if (condition.test(frame)) {
                return frame;
            }

            Thread.onSpinWait();
        }

        return fail(message + ", and it is on frame " + nes.getPPU().getFrame());
    }

    private Debugger.Stop waitForStop() throws InterruptedException {
        var stop = stops.poll(PATIENCE_SECONDS, TimeUnit.SECONDS);

        assertNotNull(stop, "the machine never stopped");

        return stop;
    }

    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        var code = new int[]{0xA9, 0x42, 0x8D, 0x00, 0x03, 0x4C, 0x05, 0x80};

        for (var i = 0; i < code.length; i++) {
            image[16 + i] = (byte) code[i];
        }

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
