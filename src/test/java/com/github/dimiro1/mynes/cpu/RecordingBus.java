package com.github.dimiro1.mynes.cpu;

import com.github.dimiro1.mynes.CPUBus;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * A flat 64KB address space that records every bus access the CPU makes, in order.
 * <p>
 * There is no PPU, APU, cartridge or mirroring here: address {@code n} is byte {@code n}.
 * That is exactly the machine the Tom Harte single-step tests describe, and it is also the
 * simplest environment in which to assert interrupt timing cycle by cycle.
 * <p>
 * A single instance is reused across the 10,000 cases of an opcode file. {@link #reset()}
 * only rewinds the addresses that were actually touched, so resetting is proportional to the
 * handful of bytes a case uses rather than to the size of the address space.
 */
final class RecordingBus implements CPUBus {

    /**
     * One bus access.
     *
     * @param address the address that was put on the bus.
     * @param value   the byte that was transferred.
     * @param read    true for a read, false for a write.
     */
    record Activity(int address, int value, boolean read) {
        @Override
        public @NotNull String toString() {
            return String.format("[%04X, %02X, %s]", address, value, read ? "read" : "write");
        }
    }

    private static final int MEMORY_SIZE = 0x10000;

    // Past this many touched addresses, wiping the whole array beats replaying the list.
    private static final int DIRTY_LIMIT = 4096;

    private final int[] memory = new int[MEMORY_SIZE];
    private final List<Activity> activities = new ArrayList<>();
    private int[] dirty = new int[256];
    private int dirtyCount;
    private boolean dirtyOverflow;

    @Override
    public int read(final int address) {
        var addr = address & 0xFFFF;
        var value = memory[addr];
        activities.add(new Activity(addr, value, true));
        return value;
    }

    @Override
    public void write(final int address, final int data) {
        var addr = address & 0xFFFF;
        var value = data & 0xFF;
        activities.add(new Activity(addr, value, false));
        touch(addr);
        memory[addr] = value;
    }

    @Override
    public boolean tickDMA(final long cpuCycle) {
        return false;
    }

    @Override
    public int peek(final int address) {
        return memory[address & 0xFFFF];
    }

    /**
     * Seeds a byte of memory without recording a bus activity.
     */
    void preload(final int address, final int value) {
        var addr = address & 0xFFFF;
        touch(addr);
        memory[addr] = value & 0xFF;
    }

    /**
     * The bus accesses recorded since the last {@link #reset()}, in the order they happened.
     */
    List<Activity> activities() {
        return activities;
    }

    /**
     * Discards the recorded activity and zeroes every byte written or preloaded since the
     * last reset.
     */
    void reset() {
        activities.clear();

        if (dirtyOverflow) {
            Arrays.fill(memory, 0);
            dirtyOverflow = false;
        } else {
            for (var i = 0; i < dirtyCount; i++) {
                memory[dirty[i]] = 0;
            }
        }

        dirtyCount = 0;
    }

    private void touch(final int address) {
        if (dirtyOverflow) {
            return;
        }

        if (dirtyCount == dirty.length) {
            if (dirty.length >= DIRTY_LIMIT) {
                dirtyOverflow = true;
                return;
            }
            dirty = Arrays.copyOf(dirty, dirty.length * 2);
        }

        dirty[dirtyCount++] = address;
    }
}
