package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.nestests.NestestLogParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Held against nestest's own log, which is the only fixture that can settle whether a trace is
 * right: it is a real machine's execution written down by somebody else, in the format this writes.
 * A tracer tested against itself would agree with itself about the wrong thing.
 */
class TracerTests {
    private static final String ROM = "/nestest/nestest.nes";
    private static final String LOG = "/nestest/nestest.log";

    /**
     * How much of the log to check. Long enough to get well past the opening JMP and through the
     * addressing modes the first few hundred instructions exercise, short enough that the test is
     * a tenth of a second.
     */
    private static final int LINES = 500;

    /**
     * nestest is driven from $C000 rather than from its reset vector, which is what puts it in the
     * automated mode the log was taken in.
     */
    private static final int START_PC = 0xC000;
    private static final int START_CYCLES = 7;

    @Test
    void aTraceSaysWhatNestestSaysHappened(@TempDir final Path directory) throws Exception {
        var path = directory.resolve("trace.log");
        var lines = trace(path, LINES, LINES);
        var expected = expected();
        var actual = parse(path);

        assertEquals(LINES, lines.size());

        for (var i = 0; i < LINES; i++) {
            assertEquals(expected.get(i), actual.get(i), "line " + (i + 1) + ": " + lines.get(i));
        }
    }

    /**
     * The columns as well as the values, which the parser above does not check: it skips over the
     * disassembly and the PPU position on its way to the registers, and those are two thirds of
     * what makes a trace readable beside somebody else's.
     * <p>
     * nestest's own first line reads {@code PPU:  0, 21}, and the six dots between that and this
     * one are two things rather than a rounding. One is this harness: the emulator's reset takes
     * eight CPU cycles where the log's machine counted seven, and {@code setCycles} puts the counter
     * right without moving the beam. The other is the order inside {@code NES.tick()}, which clocks
     * the PPU before the CPU -- so this column is where the beam is as the opcode is fetched, one
     * CPU cycle past where a log printing three times its cycle count puts it. Both are true, and
     * they answer slightly different questions; a cross-emulator diff belongs on the CPU columns
     * either way.
     */
    @Test
    void aTraceLinesUpWithNestestColumnForColumn(@TempDir final Path directory) throws Exception {
        var first = trace(directory.resolve("trace.log"), 1, 1).getFirst();

        assertEquals(
                "C000  4C F5 C5  JMP $C5F5                       "
                        + "A:00 X:00 Y:00 P:24 SP:FD PPU:  0, 27 CYC:7",
                first);
    }

    @Test
    void aTracerStopsAtItsLimit(@TempDir final Path directory) throws Exception {
        var path = directory.resolve("trace.log");
        var nes = nestest();

        try (var tracer = Tracer.to(path, nes.getPPU(), 10)) {
            nes.getCPU().addEventListener(tracer);

            for (var i = 0; i < 200; i++) {
                nes.step();
            }

            assertTrue(tracer.isFull());
            assertEquals(10, tracer.lines());
        }

        assertEquals(10, Files.readAllLines(path).size(),
                "and the ones it did write are on disk, since reaching the limit closes the file");
    }

    @Test
    void anUnlimitedTracerKeepsGoing(@TempDir final Path directory) throws Exception {
        var path = directory.resolve("trace.log");
        var nes = nestest();

        try (var tracer = Tracer.to(path, nes.getPPU(), 0)) {
            nes.getCPU().addEventListener(tracer);

            for (var i = 0; i < 200; i++) {
                nes.step();
            }

            assertFalse(tracer.isFull());
            assertEquals(200, tracer.lines());
        }
    }

    /**
     * Taking the tracer off is what a {@code trace off} does, and a machine that carried on writing
     * afterwards would be a file that grew after somebody stopped it.
     */
    @Test
    void aRemovedTracerWritesNothingMore(@TempDir final Path directory) throws Exception {
        var path = directory.resolve("trace.log");
        var nes = nestest();
        var tracer = Tracer.to(path, nes.getPPU(), 0);

        nes.getCPU().addEventListener(tracer);
        nes.step();
        nes.getCPU().removeEventListener(tracer);

        for (var i = 0; i < 50; i++) {
            nes.step();
        }

        tracer.close();

        assertEquals(1, Files.readAllLines(path).size());
    }

    @Test
    void theDirectoriesItNeedsAreMade(@TempDir final Path directory) throws Exception {
        var path = directory.resolve("a").resolve("b").resolve("trace.log");

        Tracer.to(path, nestest().getPPU(), 0).close();

        assertTrue(Files.exists(path));
    }

    // ================================================================================== internals

    /**
     * Runs nestest with a tracer on it and hands back what it wrote.
     */
    private static List<String> trace(final Path path, final long limit, final int instructions)
            throws IOException {

        var nes = nestest();

        try (var tracer = Tracer.to(path, nes.getPPU(), limit)) {
            nes.getCPU().addEventListener(tracer);

            for (var i = 0; i < instructions; i++) {
                nes.step();
            }
        }

        return Files.readAllLines(path);
    }

    /**
     * The machine the log was taken from: reset spent, then dropped at $C000 with the cycle counter
     * where the log's first line says it was.
     */
    private static NES nestest() throws IOException {
        try (var stream = TracerTests.class.getResourceAsStream(ROM)) {
            assertNotNull(stream, "ROM file not found: " + ROM);

            var nes = new NES(Cart.load(stream.readAllBytes(), ROM));

            nes.step();
            nes.getCPU().setPC(START_PC);
            nes.getCPU().setCycles(START_CYCLES);

            return nes;
        }
    }

    /**
     * The trace read back through the parser the golden log is read with, which is half of what
     * this file is checking: a line that would not go through it is a line nothing else can read
     * either.
     */
    private static List<NestestLogParser.Entry> parse(final Path path) throws IOException {
        try (var stream = Files.newInputStream(path)) {
            return NestestLogParser.parse(stream);
        }
    }

    private static List<NestestLogParser.Entry> expected() throws IOException {
        try (var stream = TracerTests.class.getResourceAsStream(LOG)) {
            assertNotNull(stream, "log file not found: " + LOG);

            return NestestLogParser.parse(stream).subList(0, LINES);
        }
    }
}
