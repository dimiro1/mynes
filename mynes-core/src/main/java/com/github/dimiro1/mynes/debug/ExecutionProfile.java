package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPU;
import com.github.dimiro1.mynes.CPUEventListener;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.mappers.Mapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Counts PRG instructions and cycles, including the call paths that reached them. */
public final class ExecutionProfile implements CPUEventListener {
    public record Entry(int prgOffset, long instructions, long cycles) {
    }

    public enum FrameKind { ROOT, CALL, NMI, IRQ, BRK, LOCATION }

    /** A physical PRG offset remains meaningful when the mapper changes banks. */
    public record Frame(FrameKind kind, int prgOffset, int cpuAddress) {
    }

    /** Immutable, inclusive totals for one path in the flame graph. */
    public record FlameNode(Frame frame, long selfInstructions, long selfCycles,
                            long instructions, long cycles, List<FlameNode> children) {
    }

    public record Snapshot(List<Entry> entries, long instructions, long cycles, FlameNode flame) {
        public Snapshot(final List<Entry> entries, final long instructions, final long cycles) {
            this(entries, instructions, cycles,
                    new FlameNode(ROOT, 0, 0, 0, 0, List.of()));
        }
    }

    private static final Frame ROOT = new Frame(FrameKind.ROOT, -1, -1);

    private static final class MutableNode {
        final Frame frame;
        final Map<Frame, MutableNode> children = new LinkedHashMap<>();
        long selfInstructions;
        long selfCycles;

        MutableNode(final Frame frame) {
            this.frame = frame;
        }

        MutableNode child(final Frame frame) {
            return children.computeIfAbsent(frame, MutableNode::new);
        }

        FlameNode snapshot() {
            var copied = new ArrayList<FlameNode>(children.size());
            var instructions = selfInstructions;
            var cycles = selfCycles;
            for (var child : children.values()) {
                var copy = child.snapshot();
                copied.add(copy);
                instructions += copy.instructions();
                cycles += copy.cycles();
            }
            return new FlameNode(frame, selfInstructions, selfCycles,
                    instructions, cycles, List.copyOf(copied));
        }
    }

    private record ActiveFrame(FrameKind kind, MutableNode node, int returnPC) {
    }

    private final CPU cpu;
    private final Mapper mapper;
    private final long[] counts;
    private final long[] cycles;
    private final List<ActiveFrame> active = new ArrayList<>();

    private MutableNode root = new MutableNode(ROOT);
    private MutableNode previousLocation;
    private int previousOffset = -1;
    private long previousRunCycles;
    private int previousOpcode = -1;
    private int previousPC;
    private int previousOperand1;
    private int previousOperand2;
    private FrameKind pendingInterrupt;
    private int interruptReturnPC;
    private long totalInstructions;
    private long totalCycles;

    public ExecutionProfile(final NES nes) {
        cpu = nes.getCPU();
        mapper = nes.getBus().getMapper();
        counts = new long[nes.getCart().prgROM().length];
        cycles = new long[counts.length];
        previousRunCycles = cpu.getRunCycles();
    }

    @Override
    public void onStep(final int pc, final int a, final int x, final int y, final int p,
                       final int sp, final int opcode, final int operand1, final int operand2,
                       final int opcodeLength, final long cycle) {
        chargePending();
        // An interrupt handler's PC is not the next PC of the interrupted instruction.
        // RTS/RTI must be reconciled against the saved return PC before opening its frame.
        applyPreviousInstruction(pendingInterrupt == null ? pc : interruptReturnPC);

        var offset = physicalOffset(pc);
        if (pendingInterrupt != null) {
            push(pendingInterrupt, offset, pc, interruptReturnPC);
            pendingInterrupt = null;
        }

        previousOffset = offset;
        previousRunCycles = cpu.getRunCycles();
        previousLocation = null;
        if (offset >= 0) {
            var parent = active.isEmpty() ? root : active.getLast().node();
            previousLocation = parent.child(new Frame(FrameKind.LOCATION, offset, pc));
            previousLocation.selfInstructions++;
            counts[offset]++;
            totalInstructions++;
        }

        previousOpcode = opcode;
        previousPC = pc;
        previousOperand1 = operand1;
        previousOperand2 = operand2;
    }

    /** Hardware interrupts are reported when serviced, between instruction callbacks. */
    @Override
    public void onInterrupt(final boolean nmi, final int returnPC) {
        pendingInterrupt = nmi ? FrameKind.NMI : FrameKind.IRQ;
        interruptReturnPC = returnPC & 0xFFFF;
    }

    /** A recording resumed after a gap must not reuse an unobserved call stack. */
    public void resume() {
        active.clear();
        previousOpcode = -1;
        previousLocation = null;
        previousOffset = -1;
        pendingInterrupt = null;
        previousRunCycles = cpu.getRunCycles();
    }

    /** Close the last instruction before the listener is removed from the CPU. */
    public void pause() {
        chargePending();
        active.clear();
        previousOpcode = -1;
        previousLocation = null;
        previousOffset = -1;
        pendingInterrupt = null;
    }

    public void reset() {
        Arrays.fill(counts, 0);
        Arrays.fill(cycles, 0);
        var frames = List.copyOf(active);
        root = new MutableNode(ROOT);
        active.clear();
        for (var frame : frames) {
            push(frame.kind(), frame.node().frame.prgOffset(),
                    frame.node().frame.cpuAddress(), frame.returnPC());
        }
        previousLocation = null;
        previousOffset = -1;
        previousRunCycles = cpu.getRunCycles();
        totalInstructions = 0;
        totalCycles = 0;
    }

    public Snapshot snapshot() {
        chargePending();
        var entries = new ArrayList<Entry>();
        for (var offset = 0; offset < counts.length; offset++) {
            if (counts[offset] != 0) {
                entries.add(new Entry(offset, counts[offset], cycles[offset]));
            }
        }
        return new Snapshot(List.copyOf(entries), totalInstructions, totalCycles, root.snapshot());
    }

    private void applyPreviousInstruction(final int nextPC) {
        switch (previousOpcode) {
            case 0x20 -> {
                var target = (previousOperand1 | previousOperand2 << 8) & 0xFFFF;
                push(FrameKind.CALL, physicalOffset(target), target, (previousPC + 3) & 0xFFFF);
            }
            case 0x60 -> popCall(nextPC);
            case 0x40 -> popInterrupt(nextPC);
            case 0x00 -> push(FrameKind.BRK, physicalOffset(nextPC), nextPC,
                    (previousPC + 2) & 0xFFFF);
            default -> { }
        }
    }

    private void push(final FrameKind kind, final int offset, final int pc,
                      final int returnPC) {
        var parent = active.isEmpty() ? root : active.getLast().node();
        var node = parent.child(new Frame(kind, offset, pc & 0xFFFF));
        active.add(new ActiveFrame(kind, node, returnPC));
    }

    private void popCall(final int nextPC) {
        if (active.isEmpty() || active.getLast().kind() != FrameKind.CALL
                || active.getLast().returnPC() != (nextPC & 0xFFFF)) {
            active.clear();
            return;
        }
        active.removeLast();
    }

    private void popInterrupt(final int nextPC) {
        for (var index = active.size() - 1; index >= 0; index--) {
            var frame = active.get(index);
            if (frame.kind() == FrameKind.NMI || frame.kind() == FrameKind.IRQ
                    || frame.kind() == FrameKind.BRK) {
                if (frame.returnPC() != (nextPC & 0xFFFF)) {
                    active.clear();
                } else {
                    active.subList(index, active.size()).clear();
                }
                return;
            }
        }
        active.clear();
    }

    private int physicalOffset(final int pc) {
        if (pc < 0x8000) return -1;
        var offset = mapper.prgOffset(pc);
        return offset >= 0 && offset < counts.length ? offset : -1;
    }

    private void chargePending() {
        var now = cpu.getRunCycles();
        if (previousOffset >= 0 && previousLocation != null && now >= previousRunCycles) {
            var elapsed = now - previousRunCycles;
            cycles[previousOffset] += elapsed;
            previousLocation.selfCycles += elapsed;
            totalCycles += elapsed;
        }
        previousRunCycles = now;
    }
}
