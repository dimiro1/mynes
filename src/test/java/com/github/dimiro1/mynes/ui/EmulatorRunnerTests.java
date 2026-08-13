package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    private NES nes;
    private Debugger debugger;
    private EmulatorRunner runner;
    private ArrayBlockingQueue<Debugger.Stop> stops;

    @BeforeEach
    void setUp() {
        nes = new NES(Cart.load(rom(), "runner.nes"));
        debugger = new Debugger();
        debugger.attach(nes);
        stops = new ArrayBlockingQueue<>(16);

        runner = new EmulatorRunner(nes, new ScreenComponent(), debugger);
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
        assertEquals(0x0300, stop.address());
        assertEquals(0x42, stop.value());
        assertEquals(STA, stop.writtenBy());
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
    void aMachineWithNothingArmedRunsFrames() throws Exception {
        runner.start();

        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(PATIENCE_SECONDS);

        while (nes.getPPU().getFrame() < 3 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        assertTrue(nes.getPPU().getFrame() >= 3, "the ordinary path should still run");
        assertNull(stops.poll(), "and nothing should have stopped it");
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
