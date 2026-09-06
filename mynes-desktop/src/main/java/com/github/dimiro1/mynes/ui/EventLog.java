package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.debug.Debugger;

import java.util.ArrayList;
import java.util.List;

/**
 * One frame of what the machine did to its hardware, collected as it does it.
 * <p>
 * {@link Debugger#setEventSink} hands over six primitives per access, thousands of times a second
 * in the worst case, and this is what catches them. Parallel arrays rather than a list of records
 * for exactly that reason: nothing here allocates while the machine is running, and the records the
 * panel reads are made once when a readout is taken, four times a second.
 * <p>
 * <b>A frame at a time, rather than a rolling window.</b> The Pads tab keeps the last two seconds
 * because what it measures is a rate; this keeps one frame because what it draws is a raster, and
 * two frames of events on one raster would be two games superimposed. {@link #startFrame} is
 * called at the boundary, and the readout taken just before it carries the frame that has ended.
 * <p>
 * The emulation thread owns one of these and nothing else touches it. The list handed out by
 * {@link #snapshot} is made fresh and shared with nothing, which is what makes it safe to read on
 * the event dispatch thread afterwards.
 */
final class EventLog {
    /**
     * How many events one frame can hold.
     * <p>
     * A game with a busy music driver and a raster split makes a couple of hundred, so this is
     * generous for what anybody meant to record. What it is really sized for is reads: a program
     * waiting out a long stretch of the picture polls $2002 every seven cycles, which is a few
     * thousand in a frame, and the honest thing is to hold most of them and say how many were lost
     * rather than to stop the machine or to quietly draw a shorter frame.
     */
    static final int CAPACITY = 4096;

    private final byte[] kinds = new byte[CAPACITY];
    private final int[] addresses = new int[CAPACITY];
    private final int[] values = new int[CAPACITY];
    private final int[] scanlines = new int[CAPACITY];
    private final int[] dots = new int[CAPACITY];
    private final int[] pcs = new int[CAPACITY];

    /**
     * Held rather than fetched, since {@code values()} copies its array on every call and this is
     * read once per event.
     */
    private static final Debugger.EventKind[] KINDS = Debugger.EventKind.values();

    private int count;
    private int dropped;

    /**
     * The sink itself. Called on the thread clocking the machine, from inside a bus write or an
     * interrupt sequence.
     */
    void record(
            final Debugger.EventKind kind,
            final int address,
            final int value,
            final int scanline,
            final int dot,
            final int pc) {

        if (count == CAPACITY) {
            dropped++;
            return;
        }

        kinds[count] = (byte) kind.ordinal();
        addresses[count] = address;
        values[count] = value;
        scanlines[count] = scanline;
        dots[count] = dot;
        pcs[count] = pc;
        count++;
    }

    /**
     * A frame has begun. Everything before it belonged to the last one.
     */
    void startFrame() {
        count = 0;
        dropped = 0;
    }

    /**
     * What has been collected so far, as records. Taken at the frame boundary, before
     * {@link #startFrame}, so what it holds is the frame that has just finished.
     */
    Readout.Events snapshot() {
        var out = new ArrayList<Debugger.Event>(count);

        for (var i = 0; i < count; i++) {
            out.add(new Debugger.Event(
                    KINDS[kinds[i]], addresses[i], values[i], scanlines[i], dots[i], pcs[i]));
        }

        return new Readout.Events(List.copyOf(out), dropped);
    }
}
