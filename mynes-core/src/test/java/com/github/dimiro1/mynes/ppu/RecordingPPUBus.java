package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.PPUBus;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link PPUBus} that watches the /NMI line instead of passing it to a CPU.
 * <p>
 * The PPU settles the line on every event that could have changed it, so most calls say the same
 * thing as the last one. Only the transitions are kept, because those are what a CPU would
 * actually notice.
 */
class RecordingPPUBus implements PPUBus {
    private final List<Boolean> edges = new ArrayList<>();

    private boolean level;

    @Override
    public void setNMILine(final boolean level) {
        if (level != this.level) {
            this.level = level;
            edges.add(level);
        }
    }

    /**
     * @return every change of the line since power on, in order, true meaning asserted.
     */
    List<Boolean> edges() {
        return List.copyOf(edges);
    }

    /**
     * @return how many times the line has been asserted, which is how many NMIs a CPU behind this
     * bus would have taken.
     */
    long assertions() {
        return edges.stream().filter(Boolean::booleanValue).count();
    }

    boolean level() {
        return level;
    }

    void reset() {
        edges.clear();
    }
}
