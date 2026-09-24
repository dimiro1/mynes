package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The log of what the machine was told, and where in the frame it was told it.
 * <p>
 * Two things are worth holding this to. The first is that it says the same thing
 * {@link DebuggerEventsTests} already checks the sink says -- the split lands on one scanline, on
 * every frame -- since a writer that lost the beam position would be a file of writes with the only
 * column anybody wanted missing. The second is the <b>shape</b>: a log is read by grep and awk far
 * more often than by eye, so seven fields on every line whatever happened is a promise rather than
 * a courtesy.
 */
class EventTracerTests {
    /**
     * Where the PPU raises its interrupt, which is where the split ROM's NMI is served.
     */
    private static final int VBLANK_LINE = 241;

    @TempDir
    private Path directory;

    private NES machine() {
        return new NES(Cart.load(SplitROM.image(), "events.nes"));
    }

    /**
     * Runs the split ROM with a log attached and hands back the file's records, header dropped.
     */
    private List<String> log(final int frames, final boolean reads, final long limit)
            throws IOException {

        var path = records(frames, reads, limit);

        return Files.readAllLines(path).stream().filter(line -> !line.startsWith("#")).toList();
    }

    /**
     * A record cut into its fields. Trimmed first, since every column but the event and the
     * addresses is right aligned and so every line starts with the padding -- which is what awk
     * skips for itself and {@code String.split} hands back as an empty first field.
     */
    private static String[] fields(final String record) {
        return record.trim().split("\\s+");
    }

    private Path records(final int frames, final boolean reads, final long limit)
            throws IOException {

        var nes = machine();
        var debugger = new Debugger();
        var path = directory.resolve("events.log");

        debugger.attach(nes);

        try (var tracer = EventTracer.to(path, nes.getPPU(), limit)) {
            debugger.setEventSink(tracer, reads);

            for (var frame = 0; frame < frames; frame++) {
                var was = nes.getPPU().getFrame();

                while (nes.getPPU().getFrame() == was) {
                    nes.tick();
                }
            }

            debugger.setEventSink(null, false);
        }

        return path;
    }

    @Test
    void theSplitIsWrittenDownOnTheScanlineItHappenedOn() throws Exception {
        var splits = log(10, false, 0).stream()
                .filter(line -> line.contains("$2005"))
                .toList();

        assertFalse(splits.isEmpty(), "the cartridge writes $2005 twice a frame");

        var lines = splits.stream().map(line -> fields(line)[1]).distinct().toList();

        assertEquals(1, lines.size(), "and on the same line every frame, not " + lines);
    }

    /**
     * The column that is not in what the sink is handed, and the one that turns a pile of writes
     * into "every frame, on line 167". A log with it stuck would say the same thing as one taken
     * over a single frame.
     */
    @Test
    void theFrameColumnCountsFrames() throws Exception {
        var frames = log(10, false, 0).stream()
                .map(line -> Long.parseLong(fields(line)[0]))
                .distinct()
                .toList();

        assertTrue(frames.size() > 5, "ten frames of a game that writes every frame, not " + frames);

        for (var i = 1; i < frames.size(); i++) {
            assertTrue(
                    frames.get(i) > frames.get(i - 1),
                    "written in the order they happened, not " + frames);
        }
    }

    /**
     * The promise the whole format rests on. Five of the seven kinds are two words when they are
     * spelled for a person, so a line written with the labels would have six fields or seven
     * depending on what happened -- and {@code awk '{print $6}'} would read the value of a PPU
     * write as the word "write".
     */
    @Test
    void everyRecordHasSevenFieldsWhicheverKindItIs() throws Exception {
        var records = log(10, true, 0);
        var kinds = records.stream().map(line -> fields(line)[3]).distinct().sorted().toList();

        assertTrue(kinds.contains("ppu-write"), "the split, in " + kinds);
        assertTrue(kinds.contains("ppu-read"), "the $2002 polls, in " + kinds);
        assertTrue(kinds.contains("nmi"), "and the interrupt, in " + kinds);

        for (var record : records) {
            assertEquals(7, fields(record).length, record);
        }
    }

    /**
     * An interrupt reads and writes nothing, and a zero in the byte column would be a byte somebody
     * could believe.
     */
    @Test
    void anInterruptHasDashesWhereAByteWouldBe() throws Exception {
        var interrupts = log(10, false, 0).stream()
                .filter(line -> fields(line)[3].equals("nmi"))
                .toList();

        assertFalse(interrupts.isEmpty(), "the cartridge switches the NMI on and leaves it on");

        for (var interrupt : interrupts) {
            var fields = fields(interrupt);

            assertEquals(String.valueOf(VBLANK_LINE), fields[1], interrupt);
            assertEquals("$FFFA", fields[4], "and names its own vector");
            assertEquals("--", fields[5], "and has no byte");
        }
    }

    /**
     * The header is furniture, so it is marked as a comment and does not count against the limit --
     * a log asked for ten records that wrote nine and a heading would be off by one in the one
     * number a caller checks.
     */
    @Test
    void theHeaderIsACommentAndIsNotARecord() throws Exception {
        var path = records(10, false, 10);
        var all = Files.readAllLines(path);

        assertTrue(all.get(0).startsWith("#"), all.get(0));
        assertEquals(11, all.size(), "ten records and the heading");

        for (var i = 1; i < all.size(); i++) {
            assertFalse(all.get(i).startsWith("#"), "only the first line is a comment");
        }
    }

    /**
     * The edge the header makes: a heading counted against the limit would turn a log asked for one
     * record into a file with a heading and nothing in it.
     */
    @Test
    void aLimitOfOneStillWritesOneRecord() throws Exception {
        var all = Files.readAllLines(records(10, false, 1));

        assertEquals(2, all.size(), "the heading and the one record");
        assertFalse(all.get(1).startsWith("#"));
    }

    @Test
    void aLogStopsAtItsLimitAndSaysSo() throws Exception {
        var nes = machine();
        var debugger = new Debugger();
        var path = directory.resolve("short.log");

        debugger.attach(nes);

        var tracer = EventTracer.to(path, nes.getPPU(), 5);

        debugger.setEventSink(tracer, false);

        // Ten frames, which is three times the five records asked for: the cartridge makes an
        // interrupt and two scroll writes a frame once it is going.
        for (var frame = 0; frame < 10; frame++) {
            var was = nes.getPPU().getFrame();

            while (nes.getPPU().getFrame() == was) {
                nes.tick();
            }
        }

        assertTrue(tracer.isFull());
        assertEquals(5, tracer.records());
        assertNull(tracer.failure(), "nothing went wrong; it simply wrote all it was asked for");

        // Closed itself, so what it wrote is on disk without anybody having to stop it -- and
        // closing it again is not an error, which is what lets a session close one either way.
        assertEquals(6, Files.readAllLines(path).size());
        tracer.close();

        debugger.setEventSink(null, false);
    }

    /**
     * The whole reason this exists rather than a watchpoint. Everything else the debugger watches
     * costs the driver its fast loop; this rides on hooks the bus already carries, so the game
     * being logged is a game running normally -- which it has to be, since where a split lands is a
     * question about a main loop that is finishing on time.
     */
    @Test
    void loggingDoesNotArmTheMachine() throws Exception {
        var nes = machine();
        var debugger = new Debugger();

        debugger.attach(nes);

        try (var tracer = EventTracer.to(directory.resolve("armed.log"), nes.getPPU(), 0)) {
            debugger.setEventSink(tracer, true);

            assertFalse(debugger.isArmed(), "a log is not a reason to stop anywhere");

            debugger.setEventSink(null, false);
        }
    }

    /**
     * Work RAM is the game thinking rather than the machine being told anything, and the program
     * writes $0200 on every pass to say so.
     */
    @Test
    void nothingBelowTheHardwareIsWrittenDown() throws Exception {
        for (var record : log(10, false, 0)) {
            var address = Integer.parseInt(fields(record)[4].substring(1), 16);

            assertTrue(address >= 0x2000, record);
        }
    }

    /**
     * Two runs of the same cartridge produce the same file, which is the claim the rest of the
     * headless mode rests on and the reason a log is worth diffing at all.
     */
    @Test
    void twoRunsWriteTheSameFile() throws Exception {
        var first = String.join("\n", log(10, true, 0));
        var second = String.join("\n", log(10, true, 0));

        assertEquals(first, second);
        assertNotEquals("", first);
    }
}
