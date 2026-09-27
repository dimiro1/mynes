package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPU;
import com.github.dimiro1.mynes.CPUEventListener;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.mappers.Mapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Counts executed PRG locations and attributes CPU run cycles to the preceding instruction. */
public final class ExecutionProfile implements CPUEventListener {
    public record Entry(int prgOffset, long instructions, long cycles) {
    }

    public record Snapshot(List<Entry> entries, long instructions, long cycles) {
    }

    private final CPU cpu;
    private final Mapper mapper;
    private final long[] counts;
    private final long[] cycles;

    private int previousOffset = -1;
    private long previousRunCycles;
    private long totalInstructions;
    private long totalCycles;

    public ExecutionProfile(final NES nes) {
        cpu = nes.getCPU();
        mapper = nes.getBus().getMapper();
        counts = new long[nes.getCart().prgROM().length];
        cycles = new long[counts.length];
    }

    @Override
    public void onStep(final int pc, final int a, final int x, final int y, final int p,
                       final int sp, final int opcode, final int operand1, final int operand2,
                       final int opcodeLength, final long cycle) {
        chargePending();
        var now = cpu.getRunCycles();

        var offset = pc >= 0x8000 ? mapper.prgOffset(pc) : -1;
        previousOffset = offset >= 0 && offset < counts.length ? offset : -1;
        previousRunCycles = now;
        if (previousOffset >= 0) {
            counts[previousOffset]++;
            totalInstructions++;
        }
    }

    public void reset() {
        Arrays.fill(counts, 0);
        Arrays.fill(cycles, 0);
        previousOffset = -1;
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
        return new Snapshot(List.copyOf(entries), totalInstructions, totalCycles);
    }

    private void chargePending() {
        var now = cpu.getRunCycles();
        if (previousOffset >= 0 && now >= previousRunCycles) {
            var elapsed = now - previousRunCycles;
            cycles[previousOffset] += elapsed;
            totalCycles += elapsed;
        }
        previousRunCycles = now;
    }
}
