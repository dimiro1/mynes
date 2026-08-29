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
     * 8005  AD 00 03  LDA $0300
     * 8008  A2 07     LDX #$07
     * 800A  4C 0A 80  JMP $800A
     * </pre>
     * <p>
     * One store and one load of the same address, so that a write watchpoint and a read watchpoint
     * each have exactly one thing to catch and the two can be told apart. A spin at the end, so that
     * running too far is harmless rather than a crash into unwritten memory.
     */
    private static final int[] CODE = {
            0xA9, 0x42,
            0x8D, 0x00, 0x03,
            0xAD, 0x00, 0x03,
            0xA2, 0x07,
            0x4C, 0x0A, 0x80,
    };

    private static final int LDA = 0x8000;
    private static final int STA = 0x8002;
    private static final int LDA_ABS = 0x8005;
    private static final int LDX = 0x8008;
    private static final int JMP = 0x800A;

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
        assertEquals(STA, stop.by(), "the STA, not wherever the CPU got to");
        assertEquals(Debugger.Access.WRITE, stop.access());
        assertEquals(LDA_ABS, stop.pc(), "and it stops on the instruction after it");
    }

    /**
     * The other half of the question, and the reason a watchpoint has a direction at all: this
     * cartridge writes $0300 once and reads it once, and a watch that could not say which it wanted
     * would answer both with the earlier one.
     */
    @Test
    void aReadWatchpointNamesTheInstructionThatRead() {
        debugger.addWatchpoint(0x0300, Debugger.Access.READ);

        var stop = run(10);

        assertNotNull(stop, "the load should have been caught");
        assertEquals(Debugger.Reason.WATCHPOINT, stop.reason());
        assertEquals(Debugger.Access.READ, stop.access());
        assertEquals(0x0300, stop.address());
        assertEquals(0x42, stop.value(), "the byte the CPU actually got");
        assertEquals(LDA_ABS, stop.by(), "the LDA, not the STA that put it there");
        assertEquals(LDX, stop.pc());
    }

    @Test
    void aReadWatchpointSleepsThroughTheWrite() {
        debugger.addWatchpoint(0x0300, Debugger.Access.READ);

        var stop = run(10);

        assertNotNull(stop);
        assertEquals(LDA_ABS, stop.by(), "the STA came first and was not what was asked for");
    }

    @Test
    void aWriteWatchpointSleepsThroughTheRead() {
        debugger.addWatchpoint(0x0300, Debugger.Access.WRITE);

        var stop = run(10);

        assertNotNull(stop);
        assertEquals(STA, stop.by());
    }

    /**
     * Both, which catches whichever comes first -- here the store, since nothing can read the
     * address before something has put a byte in it.
     */
    @Test
    void watchingBothCatchesWhicheverHappens() {
        debugger.addWatchpoint(0x0300, Debugger.Access.BOTH);

        var stop = run(10);

        assertNotNull(stop);
        assertEquals(Debugger.Access.WRITE, stop.access());
        assertEquals(STA, stop.by());
    }

    /**
     * Every instruction the CPU fetches is a read, so a read watchpoint inside a routine reports the
     * fetch. Not a defect to work around -- it is what the bus does, and it is the whole reason
     * {@code break} is a separate thing from {@code watch}.
     */
    @Test
    void aReadWatchpointOnCodeCatchesTheOpcodeFetch() {
        debugger.addWatchpoint(LDX, Debugger.Access.READ);

        var stop = run(10);

        assertNotNull(stop);
        assertEquals(LDX, stop.address());
        assertEquals(0xA2, stop.value(), "the opcode byte itself");
        assertEquals(LDX, stop.by(), "fetched by the instruction it starts");
    }

    /**
     * The window's debugger outlives its machines: a power cycle builds a new console and attaches
     * the same debugger to it, so points set against the old one have to find the new one's bus.
     */
    @Test
    void aWatchpointSetBeforeAttachingStillFires() {
        var waiting = new Debugger();

        waiting.addWatchpoint(0x0300, Debugger.Access.BOTH);
        waiting.attach(nes);

        Debugger.Stop stop = null;

        for (var i = 0; i < 10 && stop == null; i++) {
            var wasPC = nes.getCPU().getPC();

            nes.step();
            stop = waiting.afterInstruction(nes.getCPU().getPC(), wasPC);
        }

        assertNotNull(stop, "the hooks belong to whichever machine was attached last");
        assertEquals(STA, stop.by());
    }

    @Test
    void changingWhichWayAWatchpointLooksReplacesIt() {
        debugger.addWatchpoint(0x0300, Debugger.Access.WRITE);
        debugger.addWatchpoint(0x0300, Debugger.Access.READ);

        assertEquals(
                java.util.Map.of(0x0300, Debugger.Access.READ),
                debugger.watchpoints(),
                "one address holds one watchpoint");

        var stop = run(10);

        assertNotNull(stop);
        assertEquals(LDA_ABS, stop.by());
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
        assertEquals(LDA, stop.by(), "the one it ran");
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

        var frameStop = debugger.afterFrame(nes.getCPU().getPC());

        assertNotNull(frameStop);
        assertEquals(Debugger.Reason.FRAME, frameStop.reason());
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

        assertArrayStartsWith(new int[]{LDA, STA, LDA_ABS, LDX}, debugger.trail());
    }

    @Test
    void clearingForgetsEveryPoint() {
        debugger.addBreakpoint(LDX);
        debugger.addWatchpoint(0x0300);

        debugger.clear();

        assertEquals(java.util.Set.of(), debugger.breakpoints());
        assertEquals(java.util.Map.of(), debugger.watchpoints());
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

    // ============================================================================== conditions

    @Test
    void aConditionalBreakpointStopsOnlyWhereItHolds() {
        debugger.addBreakpoint(LDX, Condition.parse("a == $42"));

        var stop = run(20);

        assertNotNull(stop, "A really is $42 by the time the LDX is reached");
        assertEquals(LDX, stop.pc());
    }

    @Test
    void aConditionThatNeverHoldsNeverStops() {
        debugger.addBreakpoint(LDX, Condition.parse("a == $00"));

        assertTrue(debugger.isArmed(), "the point is still down; it simply never fires");
        assertNull(run(20));
    }

    /**
     * The JMP is reached over and over, which is what makes it the one place a condition can be
     * shown to be asked afresh on every pass rather than once when it was set.
     */
    @Test
    void aConditionIsAskedOnEveryPass() {
        debugger.addBreakpoint(JMP, Condition.parse("x == $07"));

        assertNull(run(3), "X is still zero on the way there");
        assertNotNull(run(20), "and $07 by the time the spin is reached");
    }

    @Test
    void aConditionCanReadMemory() {
        debugger.addBreakpoint(LDX, Condition.parse("[$0300] == $42"));

        assertNotNull(run(20));
    }

    @Test
    void settingTheSameBreakpointAgainReplacesItsCondition() {
        debugger.addBreakpoint(LDX, Condition.parse("a == $00"));
        debugger.addBreakpoint(LDX);

        assertEquals(java.util.Map.of(), debugger.conditions(), "the bare form takes it off");
        assertNotNull(run(20));
    }

    @Test
    void removingABreakpointForgetsItsCondition() {
        debugger.addBreakpoint(LDX, Condition.parse("a == $42"));
        debugger.removeBreakpoint(LDX);

        assertEquals(java.util.Map.of(), debugger.conditions());
        assertFalse(debugger.isArmed());
    }

    @Test
    void theConditionsAreListedByAddress() {
        debugger.addBreakpoint(LDX, Condition.parse("A==66"));
        debugger.addBreakpoint(JMP);

        assertEquals(
                java.util.Set.of(LDX, JMP),
                debugger.breakpoints(),
                "both are breakpoints, conditional or not");
        assertEquals(
                "a == $42",
                debugger.conditions().get(LDX).text(),
                "listed the one way round rather than however it was typed");
        assertNull(debugger.conditions().get(JMP));
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
