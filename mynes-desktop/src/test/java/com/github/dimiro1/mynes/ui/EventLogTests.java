package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.debug.Debugger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The frame's worth of events, between the machine making them and the panel drawing them.
 * <p>
 * Two claims, and both are about boundaries rather than about recording. A frame holds its own
 * events and nobody else's, since two frames of marks on one raster would be two games drawn on top
 * of each other; and a frame that made more than the log holds says so, because the case where that
 * happens is exactly the case somebody is looking at.
 */
class EventLogTests {
    private static void write(final EventLog log, final int address, final int scanline) {
        log.record(Debugger.EventKind.PPU_WRITE, address, 0x40, scanline, 100, 0xC000);
    }

    @Test
    void whatWentInComesOutInOrder() {
        var log = new EventLog();

        write(log, 0x2005, 30);
        write(log, 0x2006, 31);

        var frame = log.snapshot();

        assertEquals(2, frame.events().size());
        assertEquals(0x2005, frame.events().get(0).address());
        assertEquals(30, frame.events().get(0).scanline());
        assertEquals(0x2006, frame.events().get(1).address());
        assertEquals(0, frame.dropped());
    }

    @Test
    void aFrameHoldsItsOwnEventsAndNobodyElses() {
        var log = new EventLog();

        write(log, 0x2005, 30);
        log.startFrame();
        write(log, 0x2006, 31);

        var frame = log.snapshot();

        assertEquals(1, frame.events().size(), "the frame before is gone");
        assertEquals(0x2006, frame.events().get(0).address());
    }

    /**
     * A program waiting out a stretch of the picture polls $2002 every seven cycles, which is
     * thousands in a frame. Saying how many were lost is the honest answer; quietly drawing a
     * shorter frame is not, since the frame that overflows is the frame worth looking at.
     */
    @Test
    void moreThanTheLogHoldsIsCountedRatherThanForgottenQuietly() {
        var log = new EventLog();

        for (var i = 0; i < EventLog.CAPACITY + 17; i++) {
            write(log, 0x2002, 100);
        }

        var frame = log.snapshot();

        assertEquals(EventLog.CAPACITY, frame.events().size());
        assertEquals(17, frame.dropped());
    }

    @Test
    void aNewFrameForgetsWhatItDropped() {
        var log = new EventLog();

        for (var i = 0; i < EventLog.CAPACITY + 5; i++) {
            write(log, 0x2002, 100);
        }

        log.startFrame();
        write(log, 0x2005, 30);

        var frame = log.snapshot();

        assertEquals(1, frame.events().size());
        assertEquals(0, frame.dropped());
    }

    /**
     * The list a readout carries crosses to the event dispatch thread, so it must not be the log's
     * own working memory.
     */
    @Test
    void aSnapshotIsSharedWithNothing() {
        var log = new EventLog();

        write(log, 0x2005, 30);

        var frame = log.snapshot();

        log.startFrame();
        write(log, 0x2006, 31);

        assertEquals(1, frame.events().size(), "the snapshot did not follow the log");
        assertEquals(0x2005, frame.events().get(0).address());
    }

    @Test
    void theCountsAreByKind() {
        var log = new EventLog();

        write(log, 0x2005, 30);
        write(log, 0x2006, 31);
        log.record(Debugger.EventKind.NMI, 0xFFFA, -1, 241, 3, 0xC000);

        var frame = log.snapshot();

        assertEquals(2, frame.count(Debugger.EventKind.PPU_WRITE));
        assertEquals(1, frame.count(Debugger.EventKind.NMI));
        assertEquals(0, frame.count(Debugger.EventKind.IRQ));
    }

    @Test
    void aReadoutTakenWhereNothingWasRecordingCarriesNoFrame() {
        assertTrue(Readout.Events.NONE.events().isEmpty());
        assertEquals(0, Readout.Events.NONE.dropped());
    }
}
