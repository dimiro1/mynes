package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs a debugger against a cartridge built here rather than against a real one, so that every
 * instruction is at an address this file states out loud. A watchpoint that fired on the right
 * address of the wrong game would look exactly like one that worked.
 */
class DebuggerTests {
    private static final int PROGRAM = 0x8000;

    /**
     * The whole cartridge:
     *
     * <pre>
     * 8000  A9 42     LDA #$42
     * 8002  8D 00 03  STA $0300
     * 8005  A2 07     LDX #$07
     * 8007  4C 07 80  JMP $8007
     * </pre>
     * <p>
     * One store, so a watchpoint has exactly one thing to catch, and a spin at the end so that
     * running too far is harmless rather than a crash into unwritten memory.
     */
    private static final int[] CODE = {
            0xA9, 0x42,
            0x8D, 0x00, 0x03,
            0xA2, 0x07,
            0x4C, 0x07, 0x80,
    };

    private static final int LDA = 0x8000;
    private static final int STA = 0x8002;
    private static final int LDX = 0x8005;
    private static final int JMP = 0x8007;

    private NES nes;
    private Debugger debugger;

    @BeforeEach
    void setUp() {
        nes = new NES(Cart.load(rom(), "debugger.nes"));
        debugger = new Debugger();
        debugger.attach(nes);

        // The reset sequence is not an instruction and does not go through the loop below, so it is
        // spent here. Afterwards the CPU is standing on the first instruction, having run none.
        nes.step();
    }

    @Test
    void anIdleDebuggerIsNotArmed() {
        assertFalse(debugger.isArmed(), "nothing asked for, so nothing to watch the machine for");
        assertFalse(debugger.isStepping());
    }

    @Test
    void aBreakpointArmsIt() {
        debugger.addBreakpoint(LDX);

        assertTrue(debugger.isArmed());
    }

    @Test
    void aBreakpointStopsBeforeTheInstructionAtIt() {
        debugger.addBreakpoint(LDX);

        var stop = run(10);

        assertNotNull(stop, "the breakpoint should have been reached");
        assertEquals(Debugger.Reason.BREAKPOINT, stop.reason());
        assertEquals(LDX, stop.pc());
        assertEquals(0, nes.getCPU().getState().x(), "LDX #$07 must not have run yet");
    }

    @Test
    void aBreakpointSomewhereTheProgramNeverGoesNeverFires() {
        debugger.addBreakpoint(0x9000);

        assertNull(run(20));
    }

    @Test
    void aRemovedBreakpointStopsStopping() {
        debugger.addBreakpoint(LDX);
        debugger.removeBreakpoint(LDX);

        assertFalse(debugger.isArmed());
        assertNull(run(20));
    }

    /**
     * The question a watchpoint exists to answer is "what wrote this", and the instruction that did
     * it is not the one the CPU is standing on by the time anybody can be told -- that one has
     * already moved along.
     */
    @Test
    void aWatchpointNamesTheInstructionThatWrote() {
        debugger.addWatchpoint(0x0300);

        var stop = run(10);

        assertNotNull(stop, "the store should have been caught");
        assertEquals(Debugger.Reason.WATCHPOINT, stop.reason());
        assertEquals(0x0300, stop.address());
        assertEquals(0x42, stop.value());
        assertEquals(STA, stop.writtenBy(), "the STA, not wherever the CPU got to");
        assertEquals(LDX, stop.pc(), "and it stops on the instruction after it");
    }

    @Test
    void aWatchpointStopsAfterTheWriteSoTheValueCanBeLookedAt() {
        debugger.addWatchpoint(0x0300);
        run(10);

        assertEquals(0x42, nes.getMemory().peek(0x0300), "the byte is in memory by now");
    }

    @Test
    void aWatchpointOnAnAddressNobodyWritesNeverFires() {
        debugger.addWatchpoint(0x0301);

        assertNull(run(20));
    }

    @Test
    void aStepStopsAfterOneInstruction() {
        debugger.stepInstruction();

        assertTrue(debugger.isStepping());

        var stop = run(10);

        assertNotNull(stop);
        assertEquals(Debugger.Reason.STEP, stop.reason());
        assertEquals(LDA, stop.writtenBy(), "the one it ran");
        assertEquals(STA, stop.pc(), "the one it is standing on");
        assertFalse(debugger.isStepping(), "a step is spent once it is taken");
    }

    @Test
    void breakingStopsAtTheNextInstructionBoundary() {
        debugger.halt();

        var stop = run(10);

        assertNotNull(stop);
        assertEquals(Debugger.Reason.ASKED, stop.reason());
        assertEquals(STA, stop.pc(), "one instruction on from where it was");
        assertFalse(debugger.isArmed(), "and it is not still waiting to break again");
    }

    @Test
    void aFrameStepIsOnlyReportedAtTheEndOfAFrame() {
        debugger.stepFrame();

        assertNull(run(10), "an instruction boundary is not the end of a frame");
        assertEquals(Debugger.Reason.FRAME, debugger.afterFrame(nes.getCPU().getPC()).reason());
    }

    @Test
    void runningForgetsWhateverWasPending() {
        debugger.halt();
        debugger.stepInstruction();
        debugger.run();

        assertFalse(debugger.isArmed());
    }

    @Test
    void theTrailRemembersWhatActuallyRan() {
        debugger.addBreakpoint(JMP);
        run(10);

        assertArrayStartsWith(new int[]{LDA, STA, LDX}, debugger.trail());
    }

    @Test
    void clearingForgetsEveryPoint() {
        debugger.addBreakpoint(LDX);
        debugger.addWatchpoint(0x0300);

        debugger.clear();

        assertEquals(java.util.Set.of(), debugger.breakpoints());
        assertEquals(java.util.Set.of(), debugger.watchpoints());
        assertFalse(debugger.isArmed());
        assertNull(run(20));
    }

    @Test
    void thePointsAreListedInAddressOrder() {
        debugger.addBreakpoint(0x9000);
        debugger.addBreakpoint(0x8000);
        debugger.addBreakpoint(0x8800);

        assertEquals(java.util.List.of(0x8000, 0x8800, 0x9000),
                java.util.List.copyOf(debugger.breakpoints()));
    }

    @Test
    void togglingSaysWhichWayItWent() {
        assertTrue(debugger.toggleBreakpoint(LDX), "put one down");
        assertFalse(debugger.toggleBreakpoint(LDX), "and picked it up again");
        assertEquals(java.util.Set.of(), debugger.breakpoints());
    }

    // ================================================================================== internals

    /**
     * The loop both real drivers run: one instruction, then ask. Written out here rather than shared
     * with them so that a change to either has to be made in front of these assertions.
     */
    private Debugger.Stop run(final int instructions) {
        for (var i = 0; i < instructions; i++) {
            var wasPC = nes.getCPU().getPC();

            nes.step();

            var stop = debugger.afterInstruction(nes.getCPU().getPC(), wasPC);

            if (stop != null) {
                return stop;
            }
        }

        return null;
    }

    private static void assertArrayStartsWith(final int[] expected, final int[] actual) {
        assertTrue(actual.length >= expected.length,
                "only " + actual.length + " instructions ran");

        for (var i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "instruction " + i);
        }
    }

    /**
     * An NROM cartridge holding {@link #CODE}, with the reset vector pointing at it. One 16KB PRG
     * bank, which the mapper mirrors into $C000-$FFFF, so the vector at $FFFC is the last but three
     * byte of the bank.
     */
    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        for (var i = 0; i < CODE.length; i++) {
            image[16 + i] = (byte) CODE[i];
        }

        image[16 + 0x3FFC] = (byte) (PROGRAM & 0xFF);
        image[16 + 0x3FFD] = (byte) (PROGRAM >> 8);

        return image;
    }
}
