package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the machine did to its hardware, and where in the frame it did it.
 * <p>
 * The claim worth checking is not that a write is seen -- a hook that fires is easy -- but that it
 * is stamped with <em>where the beam was</em>, because that is the only reason to record any of
 * this. So the first tests run a real cartridge whose main loop deliberately writes $2005 in the
 * middle of the picture, which is what a status bar split looks like, and ask which scanline came
 * back.
 * <p>
 * The rest are about what is <b>not</b> recorded. A log that also held work RAM and instruction
 * fetches would be a log of the whole program with a frame's worth of hardware buried in it, so the
 * cheapest way for this to be useless is for one of those to creep in.
 */
class DebuggerEventsTests {
    /**
     * One thing the machine did: what {@link Debugger.EventSink} hands over, kept in the shape a
     * test wants to read.
     */
    private record Event(Debugger.EventKind kind, int address, int value, int scanline, int dot) {
    }

    /**
     * Where the split ROM below aims its $2005 writes, near enough. It counts about 15400 cycles
     * from the NMI, which is 135 scanlines past 241 and so a little over a hundred lines into the
     * following picture.
     */
    private static final int VISIBLE_LINES = 240;

    /**
     * Where the PPU raises its interrupt.
     */
    private static final int VBLANK_LINE = 241;

    private static List<Event> fromTheSplitROM;

    @BeforeAll
    static void runTheSplitROM() {
        fromTheSplitROM = run(SplitROM.image(), 10, false);
    }

    @Test
    void aSplitIsRecordedOnTheScanlineItHappenedOn() {
        var splits = fromTheSplitROM.stream()
                .filter(event -> event.kind() == Debugger.EventKind.PPU_WRITE)
                .filter(event -> event.address() == 0x2005)
                .toList();

        assertFalse(splits.isEmpty(), "the cartridge writes $2005 twice a frame");

        for (var split : splits) {
            assertTrue(
                    split.scanline() >= 0 && split.scanline() < VISIBLE_LINES,
                    "the write lands in the picture rather than in the blanking, at line "
                            + split.scanline());
        }

        // The same line every frame, which is the whole of what makes a split a split. Nothing in
        // the program is timed off anything but the interrupt and a counted delay, so a scanline
        // that wandered would mean the stamp was being taken somewhere other than at the access.
        var lines = splits.stream().map(Event::scanline).distinct().toList();

        assertEquals(1, lines.size(), "and on the same line every frame, not " + lines);
    }

    @Test
    void anInterruptIsRecordedWhereTheBeamWas() {
        var interrupts = fromTheSplitROM.stream()
                .filter(event -> event.kind() == Debugger.EventKind.NMI)
                .toList();

        assertFalse(interrupts.isEmpty(), "the cartridge switches the NMI on and leaves it on");

        for (var nmi : interrupts) {
            assertEquals(
                    VBLANK_LINE,
                    nmi.scanline(),
                    "an NMI is served within a few cycles of the flag going up on line 241");
            assertEquals(0xFFFA, nmi.address(), "and names its own vector");
        }
    }

    /**
     * The program writes to $0200 on every pass. A log that carried work RAM would be a log of the
     * game thinking rather than of the machine doing anything, and would bury the frame's worth of
     * hardware in thousands of lines.
     */
    @Test
    void writesToWorkRamAreNotEvents() {
        for (var event : fromTheSplitROM) {
            assertTrue(
                    event.address() >= 0x2000,
                    "nothing below $2000 is hardware, and $" + Integer.toHexString(event.address())
                            + " was recorded");
        }
    }

    @Test
    void nothingIsAReadUnlessReadsWereAskedFor() {
        for (var event : fromTheSplitROM) {
            assertFalse(event.kind().isRead(), "reads were not asked for");
        }
    }

    /**
     * The one that matters most, and the reason reads are a decision rather than a filter: the CPU
     * takes its opcodes off the same bus as everything else, so a log that recorded every read
     * would be thirty thousand instruction fetches a frame.
     */
    @Test
    void anInstructionFetchIsNeverAnEventEvenWithReadsOn() {
        var events = run(SplitROM.image(), 6, true);

        assertTrue(
                events.stream().anyMatch(event -> event.kind().isRead()),
                "with reads on, the cartridge's own $2002 polls are recorded");

        for (var event : events) {
            var isInterrupt = event.kind() == Debugger.EventKind.NMI
                    || event.kind() == Debugger.EventKind.IRQ;

            assertTrue(
                    isInterrupt || event.address() < 0x4020,
                    "the program lives above $8000 and none of it is an event, but $"
                            + Integer.toHexString(event.address()) + " was recorded");
        }
    }

    /**
     * The point of the whole design. Everything else the debugger watches costs the driver its fast
     * loop, because a breakpoint has to be looked at between instructions; this rides on hooks the
     * bus already carries, so the game being recorded is a game running normally.
     */
    @Test
    void recordingDoesNotArmTheMachine() {
        var nes = new NES(Cart.load(SplitROM.image(), "events.nes"));
        var debugger = new Debugger();

        debugger.attach(nes);
        debugger.setEventSink((kind, address, value, scanline, dot, pc) -> { }, true);

        assertFalse(debugger.isArmed(), "a sink is not a reason to stop anywhere");
    }

    @Test
    void takingTheSinkOffStopsTheRecording() {
        var nes = new NES(Cart.load(SplitROM.image(), "events.nes"));
        var debugger = new Debugger();
        var seen = new ArrayList<Event>();

        debugger.attach(nes);
        debugger.setEventSink(
                (kind, address, value, scanline, dot, pc) ->
                        seen.add(new Event(kind, address, value, scanline, dot)),
                false);

        nes.getMemory().write(0x2005, 0x40);
        assertEquals(1, seen.size());

        debugger.setEventSink(null, false);
        nes.getMemory().write(0x2005, 0x40);

        assertEquals(1, seen.size(), "nothing more after the sink came off");
    }

    /**
     * A watchpoint and a recording want the same hooks and neither knows about the other, so the
     * one that is taken away must not take the other's hook with it.
     */
    @Test
    void aWatchpointAndARecordingDoNotUnhookEachOther() {
        var nes = new NES(Cart.load(SplitROM.image(), "events.nes"));
        var debugger = new Debugger();
        var seen = new ArrayList<Event>();

        debugger.attach(nes);
        debugger.addWatchpoint(0x0200, Debugger.Access.WRITE);
        debugger.setEventSink(
                (kind, address, value, scanline, dot, pc) ->
                        seen.add(new Event(kind, address, value, scanline, dot)),
                false);

        debugger.removeWatchpoint(0x0200);
        nes.getMemory().write(0x2005, 0x40);

        assertEquals(1, seen.size(), "the recording still has its hook");
    }

    // ================================================================================== the runs

    private static List<Event> run(final byte[] rom, final int frames, final boolean reads) {
        var nes = new NES(Cart.load(rom, "events.nes"));
        var debugger = new Debugger();
        var seen = new ArrayList<Event>();

        debugger.attach(nes);
        debugger.setEventSink(
                (kind, address, value, scanline, dot, pc) ->
                        seen.add(new Event(kind, address, value, scanline, dot)),
                reads);

        for (var frame = 0; frame < frames; frame++) {
            var was = nes.getPPU().getFrame();

            while (nes.getPPU().getFrame() == was) {
                nes.tick();
            }
        }

        // The frames before the interrupt is switched on are the program waiting for the PPU to
        // warm up, and what they hold is a handful of $2002 polls rather than anything it does.
        return seen.stream().filter(event -> event.scanline() >= 0).toList();
    }
}
