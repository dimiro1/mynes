package com.github.dimiro1.mynes.cpu;

import com.github.dimiro1.mynes.CPU;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Cycle level tests for the interrupt engine.
 * <p>
 * None of this is reachable from the ROM based tests: nothing in the emulator asserts IRQ or NMI
 * yet, and the timing rules being checked here only show up in the exact cycle an interrupt is
 * raised on. The tests drive the CPU one {@link CPU#tick()} at a time so the lines can be moved
 * between two specific cycles, which is the only way to observe rules like the branch quirk or
 * the one instruction of latency CLI buys.
 * <p>
 * The machine is a bare 64KB address space ({@link RecordingBus}), so an address is just memory
 * and the vectors can be pointed anywhere.
 *
 * @see <a href="https://www.nesdev.org/wiki/CPU_interrupts">NESdev: CPU interrupts</a>
 */
public class CPUInterruptTests {
    private static final int NMI_VECTOR = 0xFFFA;
    private static final int RST_VECTOR = 0xFFFC;
    private static final int IRQ_VECTOR = 0xFFFE;

    private static final int PROGRAM = 0x0600;
    private static final int IRQ_HANDLER = 0x0A00;
    private static final int NMI_HANDLER = 0x0900;
    private static final int RST_HANDLER = 0x0800;

    private static final int INITIAL_SP = 0xFD;

    // Status register with only the unused bit 5 set, so interrupts are unmasked.
    private static final int IRQ_ENABLED = 0x20;
    private static final int IRQ_DISABLED = 0x24;

    private static final int FLAG_I = 0x04;
    private static final int FLAG_B = 0x10;

    private static final int NOP = 0xEA;
    private static final int BRK = 0x00;
    private static final int CLI = 0x58;
    private static final int SEI = 0x78;
    private static final int PLP = 0x28;
    private static final int BEQ = 0xF0;
    private static final int LDA_ZP = 0xA5;

    private RecordingBus bus;
    private CPU cpu;

    @BeforeEach
    void setUp() {
        bus = new RecordingBus();
        cpu = new CPU(bus);

        vector(NMI_VECTOR, NMI_HANDLER);
        vector(RST_VECTOR, RST_HANDLER);
        vector(IRQ_VECTOR, IRQ_HANDLER);
    }

    @Nested
    @DisplayName("the interrupt sequences themselves")
    class Sequences {
        @Test
        void irqPushesReturnStateAndVectorsThroughFFFE() {
            program(PROGRAM, NOP, NOP);
            begin(PROGRAM, IRQ_ENABLED);
            cpu.setIRQLine(true);

            cpu.step();  // NOP, whose last cycle polls and latches the request
            var afterInstruction = cpu.getState().cycles();
            cpu.step();  // the interrupt sequence

            var state = cpu.getState();
            assertEquals(IRQ_HANDLER, state.pc(), "should vector through $FFFE");
            assertEquals(7, state.cycles() - afterInstruction, "the sequence takes 7 cycles");
            assertEquals(INITIAL_SP - 3, state.sp(), "should push PCH, PCL and P");
            assertEquals(FLAG_I, state.p() & FLAG_I, "should mask further IRQs");

            // The pushed program counter is the instruction that would have run next.
            assertEquals(0x06, bus.peek(0x0100 + INITIAL_SP), "pushed PCH");
            assertEquals(0x01, bus.peek(0x0100 + INITIAL_SP - 1), "pushed PCL");
            assertEquals(
                    IRQ_ENABLED, bus.peek(0x0100 + INITIAL_SP - 2),
                    "a hardware interrupt pushes P with the B flag clear"
            );
        }

        @Test
        void irqTouchesTheBusExactlyOncePerCycle() {
            // The Harte set never raises an interrupt, so this is the only check on the order
            // the sequence puts things on the bus in.
            program(PROGRAM, NOP, NOP);
            begin(PROGRAM, IRQ_ENABLED);
            cpu.setIRQLine(true);

            cpu.step();
            var from = bus.activities().size();
            cpu.step();

            assertEquals(
                    List.of(
                            // The instruction fetch that was already under way, discarded twice.
                            new RecordingBus.Activity(PROGRAM + 1, NOP, true),
                            new RecordingBus.Activity(PROGRAM + 1, NOP, true),
                            new RecordingBus.Activity(0x0100 + INITIAL_SP, 0x06, false),
                            new RecordingBus.Activity(0x0100 + INITIAL_SP - 1, 0x01, false),
                            new RecordingBus.Activity(0x0100 + INITIAL_SP - 2, IRQ_ENABLED, false),
                            new RecordingBus.Activity(IRQ_VECTOR, IRQ_HANDLER & 0xFF, true),
                            new RecordingBus.Activity(IRQ_VECTOR + 1, IRQ_HANDLER >> 8, true)
                    ),
                    bus.activities().subList(from, bus.activities().size())
            );
        }

        @Test
        void nmiVectorsThroughFFFA() {
            program(PROGRAM, NOP, NOP);
            begin(PROGRAM, IRQ_DISABLED);  // NMI is not maskable
            cpu.requestNMI();

            cpu.step();
            cpu.step();

            assertEquals(NMI_HANDLER, cpu.getState().pc());
        }

        @Test
        void brkPushesTheBreakFlagAndSkipsTheSignatureByte() {
            program(PROGRAM, BRK, 0xFF, NOP);
            begin(PROGRAM, IRQ_ENABLED);

            cpu.step();

            var state = cpu.getState();
            assertEquals(IRQ_HANDLER, state.pc());
            assertEquals(7, state.cycles(), "BRK takes 7 cycles");
            assertEquals(
                    0x06, bus.peek(0x0100 + INITIAL_SP),
                    "the pushed return address skips the byte after BRK"
            );
            assertEquals(0x02, bus.peek(0x0100 + INITIAL_SP - 1));
            assertEquals(
                    IRQ_ENABLED | FLAG_B, bus.peek(0x0100 + INITIAL_SP - 2),
                    "BRK pushes P with the B flag set"
            );
        }

        @Test
        void resetPushesNothingButStillMovesTheStackPointer() {
            program(PROGRAM, NOP);
            begin(PROGRAM, IRQ_ENABLED);
            cpu.loadState(new CPU.State(0, 0, 0, 0x80, PROGRAM, IRQ_ENABLED, 0));

            cpu.requestRST();
            cpu.step();

            var state = cpu.getState();
            assertEquals(RST_HANDLER, state.pc());
            assertEquals(8, state.cycles(), "the reset sequence takes 8 cycles");
            assertEquals(0x80 - 3, state.sp(), "three dummy stack cycles move SP without writing");
            assertEquals(FLAG_I, state.p() & FLAG_I);
            assertEquals(0, bus.peek(0x0180), "reset must not write to the stack");
        }

        @Test
        void powerOnRunsTheResetSequence() {
            // No loadState here: a freshly constructed CPU boots through the reset vector.
            cpu.step();

            var state = cpu.getState();
            assertEquals(RST_HANDLER, state.pc());
            assertEquals(0xFD, state.sp());
            assertEquals(0x24, state.p());
            assertEquals(8, state.cycles());
        }
    }

    @Nested
    @DisplayName("when the request arrives")
    class Timing {
        @Test
        void requestBeforeTheLastCycleIsTakenAtTheNextBoundary() {
            program(PROGRAM, NOP, NOP);
            begin(PROGRAM, IRQ_ENABLED);

            tick(1);                 // cycle 1 of the NOP: the opcode fetch
            cpu.setIRQLine(true);
            tick(1);                 // cycle 2 polls at its start and sees the line

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc(), "should be serviced immediately");
        }

        @Test
        void requestOnTheLastCycleIsDelayedByOneInstruction() {
            program(PROGRAM, NOP, NOP, NOP);
            begin(PROGRAM, IRQ_ENABLED);

            cpu.step();              // the whole first NOP runs with the line released
            cpu.setIRQLine(true);    // too late for its poll

            cpu.step();
            assertEquals(PROGRAM + 2, cpu.getState().pc(), "the second NOP runs first");

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc());
        }

        @Test
        void takenBranchThatStaysInPageDelaysTheRequest() {
            // BEQ +2 with Z set: three cycles, and the last one deliberately does not poll.
            program(PROGRAM, BEQ, 0x02);
            program(PROGRAM + 4, NOP, NOP);
            begin(PROGRAM, IRQ_ENABLED | 0x02);

            tick(2);                 // opcode fetch and the operand fetch that polls
            cpu.setIRQLine(true);
            tick(1);                 // the last cycle of the taken branch: no poll

            cpu.step();
            assertEquals(PROGRAM + 5, cpu.getState().pc(), "the branch target runs first");

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc());
        }

        @Test
        void threeCycleInstructionDoesPollOnItsLastCycle() {
            // The control for the branch quirk: same shape, same timing, but LDA does poll.
            program(PROGRAM, LDA_ZP, 0x10, NOP);
            begin(PROGRAM, IRQ_ENABLED);

            tick(2);
            cpu.setIRQLine(true);
            tick(1);                 // the last cycle polls at its start

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc());
        }

        @Test
        void pageCrossingBranchPollsOnItsExtraCycle() {
            // BEQ from $06FD lands at $070F, so the branch takes a fourth cycle, which polls.
            var branch = 0x06FD;
            program(branch, BEQ, 0x10);
            program(0x070F, NOP);
            begin(branch, IRQ_ENABLED | 0x02);

            tick(3);                 // through the cycle that would have ended a same-page branch
            cpu.setIRQLine(true);
            tick(1);                 // the page-crossing cycle polls

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc(), "the crossing path must not delay it");
        }
    }

    @Nested
    @DisplayName("instructions that change the interrupt disable flag")
    class FlagChanges {
        @Test
        void cliLetsExactlyOneMoreInstructionRun() {
            program(PROGRAM, CLI, NOP, NOP);
            begin(PROGRAM, IRQ_DISABLED);
            cpu.setIRQLine(true);

            cpu.step();              // CLI polls before it clears the flag, so nothing is latched
            assertEquals(PROGRAM + 1, cpu.getState().pc());

            cpu.step();              // one instruction of latency
            assertEquals(PROGRAM + 2, cpu.getState().pc());

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc());
        }

        @Test
        void seiDoesNotCancelTheRequestItAlreadySaw() {
            program(PROGRAM, SEI, NOP);
            begin(PROGRAM, IRQ_ENABLED);
            cpu.setIRQLine(true);

            cpu.step();              // SEI polls before it sets the flag, so the request is latched
            assertEquals(FLAG_I, cpu.getState().p() & FLAG_I);

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc(), "the latched request is still serviced");
        }

        @Test
        void plpThatClearsTheFlagBehavesLikeCli() {
            program(PROGRAM, PLP, NOP, NOP);
            begin(PROGRAM, IRQ_DISABLED);
            push(IRQ_ENABLED);
            cpu.setIRQLine(true);

            cpu.step();              // PLP polls before it restores P
            assertEquals(0, cpu.getState().p() & FLAG_I);

            cpu.step();
            assertNotEquals(IRQ_HANDLER, cpu.getState().pc(), "one instruction of latency");

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc());
        }

        @Test
        void plpThatSetsTheFlagBehavesLikeSei() {
            program(PROGRAM, PLP, NOP);
            begin(PROGRAM, IRQ_ENABLED);
            push(IRQ_DISABLED);
            cpu.setIRQLine(true);

            cpu.step();
            assertEquals(FLAG_I, cpu.getState().p() & FLAG_I);

            cpu.step();
            assertEquals(IRQ_HANDLER, cpu.getState().pc(), "the latched request is still serviced");
        }
    }

    @Nested
    @DisplayName("when more than one source is active")
    class Priority {
        @Test
        void nmiIsServicedFirstAndTheHeldIrqLineSurvives() {
            program(PROGRAM, NOP, NOP);
            program(NMI_HANDLER, CLI, NOP);
            begin(PROGRAM, IRQ_ENABLED);

            cpu.setIRQLine(true);
            cpu.requestNMI();

            cpu.step();              // NOP, latching both
            cpu.step();
            assertEquals(NMI_HANDLER, cpu.getState().pc(), "NMI outranks IRQ");

            cpu.step();              // CLI, which unmasks IRQ again
            cpu.step();              // one instruction of latency
            cpu.step();
            assertEquals(
                    IRQ_HANDLER, cpu.getState().pc(),
                    "the IRQ line is still held, so the request was never lost"
            );
        }

        @Test
        void oneNmiEdgeIsServicedOnlyOnce() {
            program(PROGRAM, NOP, NOP);
            program(NMI_HANDLER, NOP, NOP, NOP);
            begin(PROGRAM, IRQ_DISABLED);
            cpu.requestNMI();

            cpu.step();
            cpu.step();
            assertEquals(NMI_HANDLER, cpu.getState().pc());

            cpu.step();
            cpu.step();
            assertEquals(
                    NMI_HANDLER + 2, cpu.getState().pc(),
                    "the edge was consumed, so the handler runs uninterrupted"
            );
        }

        @Test
        void nmiRaisedDuringBrkStealsItsVector() {
            program(PROGRAM, BRK, 0xFF, NOP);
            program(NMI_HANDLER, NOP);
            begin(PROGRAM, IRQ_ENABLED);

            // The vector is chosen on the cycle that pushes the status byte, not on the one that
            // fetches it, so this is the last cycle an NMI can arrive on and still take it.
            // AccuracyCoin's NMI Overlap BRK walks the whole window a PPU dot at a time and its
            // answer key is fifteen dots wide, which is what puts the edge here rather than a
            // cycle later.
            tick(4);
            cpu.requestNMI();
            tick(3);                 // the vector fetch takes $FFFA instead

            var state = cpu.getState();
            assertEquals(NMI_HANDLER, state.pc(), "the NMI hijacks the vector fetch");
            assertEquals(7, state.cycles(), "still a single seven cycle sequence");
            assertEquals(
                    IRQ_ENABLED | FLAG_B, bus.peek(0x0100 + INITIAL_SP - 2),
                    "the status byte was pushed before the hijack, so B stays set"
            );

            cpu.step();
            assertEquals(
                    NMI_HANDLER + 1, cpu.getState().pc(),
                    "the edge was consumed by the hijack, so nothing is serviced again"
            );
        }

        @Test
        void irqIsIgnoredWhileTheFlagIsSet() {
            program(PROGRAM, NOP, NOP, NOP, NOP);
            begin(PROGRAM, IRQ_DISABLED);
            cpu.setIRQLine(true);

            cpu.step();
            cpu.step();
            cpu.step();

            assertEquals(PROGRAM + 3, cpu.getState().pc(), "masked requests must not vector");
        }

        @Test
        void releasingTheLineBeforeTheFlagClearsDropsTheRequest() {
            program(PROGRAM, CLI, NOP, NOP);
            begin(PROGRAM, IRQ_DISABLED);

            cpu.setIRQLine(true);
            cpu.step();              // CLI, still masked when it polled
            cpu.setIRQLine(false);   // the device gives up before the CPU could act

            cpu.step();
            cpu.step();
            assertEquals(PROGRAM + 3, cpu.getState().pc(), "a level request that went away is gone");
        }
    }

    private void program(final int address, final int... bytes) {
        for (var i = 0; i < bytes.length; i++) {
            bus.preload(address + i, bytes[i]);
        }
    }

    private void vector(final int vector, final int address) {
        bus.preload(vector, address & 0xFF);
        bus.preload(vector + 1, (address >> 8) & 0xFF);
    }

    private void begin(final int pc, final int p) {
        cpu.loadState(new CPU.State(0, 0, 0, INITIAL_SP, pc, p, 0));
    }

    /**
     * Puts a byte where the next PLP will find it.
     */
    private void push(final int value) {
        bus.preload(0x0100 + INITIAL_SP + 1, value);
    }

    private void tick(final int count) {
        for (var i = 0; i < count; i++) {
            cpu.tick();
        }
    }
}
