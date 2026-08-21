package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recording a session, and playing it back.
 * <p>
 * The claim is the strong one, and every test that can make it does: a replayed machine is not
 * approximately the machine that was recorded, it is that machine, byte for byte. So the comparisons
 * below are of {@link SaveState} bytes rather than of pictures -- the picture stops being evidence
 * as soon as a ROM settles down, and the state carries the cycle counters, the interrupt latches and
 * every APU channel nobody can hear.
 * <p>
 * The other half of what is here is the container, in the shape {@link SaveStateFormatTests} uses:
 * everything a file can be wrong about is found out before any machine is touched.
 */
class MovieTests {

    /**
     * Where these runs press Start, and how long for. The same frames {@link RewindTests} uses, and
     * for the same reason: nestest is known to notice a button and redraw over them, which is what
     * makes a second timeline genuinely a second timeline rather than the same one twice.
     */
    private static final int PRESS_AT = 60;
    private static final int PRESS_FOR = 30;

    /**
     * Enough history for every rewind here, so a ring that evicts is something a test asks for
     * rather than something it stumbles into.
     */
    private static final int ROOMY = 400;

    @TempDir
    private Path directory;

    // ================================================================================== replaying

    /**
     * The whole point, in one test: buttons written down as they were pressed, played back into a
     * machine that has never seen them, arriving at the same bytes.
     */
    @Test
    void aRecordedRunReplaysToByteIdenticalState() throws IOException {
        var nes = load();
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        play(nes, recorder, PRESS_AT, 0);
        play(nes, recorder, PRESS_FOR, Controller.BUTTON_START);
        play(nes, recorder, 20, 0);

        var movie = roundTrip(recorder.movie());

        assertEquals(PRESS_AT + PRESS_FOR + 20, movie.frameCount());

        var replayed = load();
        replay(replayed, movie, movie.frameCount());

        assertArrayEquals(save(nes), save(replayed), "every field, not only the picture");
    }

    /**
     * A movie carries the timeline that was finally played and never the one that was taken back.
     * <p>
     * This is exact rather than a convenient approximation, and it composes out of something already
     * proved elsewhere: {@link RewindTests#rewindingGoesBackToTheFrameItLeft} shows a rewound machine
     * <em>is</em> the machine that never went forward, so truncating the log to match is not losing
     * information -- there was none to lose.
     */
    @Test
    void aRewindWhileRecordingLeavesOnlyTheFinalTimeline() throws IOException {
        var nes = load();
        var rewind = new Rewind(ROOMY);
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        rewind.capture(nes);

        for (var i = 0; i < 110; i++) {
            nes.getController1().setButtons(0);
            advanceFrame(nes);
            rewind.capture(nes);
            recorder.frame(0);
        }

        var moved = rewind.rewind(nes, 30);
        recorder.rewound(nes, moved);

        assertEquals(30, moved);
        assertEquals(80, nes.getPPU().getFrame());
        assertEquals(80, recorder.framesRecorded(), "the thirty that were taken back are gone");
        assertFalse(recorder.anchored(), "and it still starts where it always did");

        // The same frames again, played differently. Without this the two timelines would be one
        // and the test would prove nothing.
        for (var i = 0; i < 30; i++) {
            nes.getController1().setButtons(Controller.BUTTON_START);
            advanceFrame(nes);
            rewind.capture(nes);
            recorder.frame(Controller.BUTTON_START);
        }

        var movie = roundTrip(recorder.movie());

        assertEquals(110, movie.frameCount());
        assertEquals(0, movie.buttonsAt(79), "frame 79 was played with nothing held");
        assertEquals(Controller.BUTTON_START, movie.buttonsAt(80), "and 80 was not");

        var replayed = load();
        replay(replayed, movie, movie.frameCount());

        assertArrayEquals(save(nes), save(replayed),
                "the replay never re-enacts the thirty frames that were undone");
    }

    /**
     * Going back further than the recording itself. There is no longer a log that describes where
     * the machine is, so the recording starts again from where it now stands -- which keeps the take
     * alive for whatever is played next, at the cost of what came before.
     */
    @Test
    void aRewindPastTheStartReanchorsTheRecording() throws IOException {
        var nes = load();
        var rewind = new Rewind(ROOMY);

        rewind.capture(nes);

        for (var i = 0; i < 40; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
        }

        // Recording starts forty frames in, which is what gives the rewind below somewhere to go
        // that the log cannot describe.
        var recorder = MovieRecorder.anchoredAt(nes, List.of());

        for (var i = 0; i < 10; i++) {
            advanceFrame(nes);
            rewind.capture(nes);
            recorder.frame(0);
        }

        var moved = rewind.rewind(nes, 30);
        recorder.rewound(nes, moved);

        assertEquals(30, moved);
        assertEquals(20, nes.getPPU().getFrame());
        assertEquals(0, recorder.framesRecorded(), "the log could not describe frame 20, so it went");
        assertTrue(recorder.anchored());
        assertEquals(20, recorder.anchorFrame(), "and the recording starts where the machine is");

        play(nes, recorder, 25, Controller.BUTTON_START);

        var movie = roundTrip(recorder.movie());

        assertEquals(25, movie.frameCount());
        assertEquals(20, movie.anchorFrame());

        var replayed = load();
        replay(replayed, movie, movie.frameCount());

        assertEquals(45, replayed.getPPU().getFrame(), "twenty anchored plus twenty-five played");
        assertArrayEquals(save(nes), save(replayed));
    }

    /**
     * The console's Reset button, which is a thing that happened to the machine rather than a button
     * on the pad -- so it is recorded separately and applied at the start of the frame it was
     * pressed at.
     */
    @Test
    void aResetIsReplayedOnTheFrameItHappened() throws IOException {
        var nes = load();
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        play(nes, recorder, 40, 0);

        // Told before the machine is, so the index written down is the frame the reset is seen in.
        recorder.reset();
        nes.reset();

        play(nes, recorder, 40, 0);

        var movie = roundTrip(recorder.movie());

        assertArrayEquals(new long[]{40}, movie.resets());
        assertTrue(movie.resetsAt(40));
        assertFalse(movie.resetsAt(39));

        var straight = load();
        for (var i = 0; i < 80; i++) {
            advanceFrame(straight);
        }

        assertFalse(
                Arrays.equals(save(straight), save(nes)),
                "the reset has to have changed something, or this test proves nothing");

        var replayed = load();
        replay(replayed, movie, movie.frameCount());

        assertArrayEquals(save(nes), save(replayed));
    }

    /**
     * A loaded state is a machine nobody played their way to, so there is no timeline to truncate
     * and the movie starts again from where the state put it.
     */
    @Test
    void aLoadedStateWhileRecordingStartsTheMovieThere() throws IOException {
        var elsewhere = load();
        for (var i = 0; i < 50; i++) {
            advanceFrame(elsewhere);
        }

        var bookmark = save(elsewhere);

        var nes = load();
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        play(nes, recorder, 10, 0);

        SaveState.read(nes, new ByteArrayInputStream(bookmark));
        recorder.jumped(nes);

        assertTrue(recorder.anchored(), "a power-on movie stops being one the moment it jumps");
        assertEquals(50, recorder.anchorFrame());
        assertEquals(0, recorder.framesRecorded());

        play(nes, recorder, 20, Controller.BUTTON_START);

        var movie = roundTrip(recorder.movie());
        var replayed = load();

        replay(replayed, movie, movie.frameCount());

        assertEquals(70, replayed.getPPU().getFrame());
        assertArrayEquals(save(nes), save(replayed));
    }

    /**
     * The smaller of the two files, and the more portable: buttons and nothing else. Which is also
     * why it can only be recorded from a machine that has not run -- there is nothing in it to say
     * where "the beginning" was.
     */
    @Test
    void aPowerOnMovieEmbedsNoAnchor() throws IOException {
        var nes = load();
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        play(nes, recorder, 300, 0);

        var movie = roundTrip(recorder.movie());

        assertFalse(movie.anchored());
        assertEquals(0, movie.anchorFrame());

        var started = load();
        movie.applyAnchor(started);

        assertEquals(0, started.getPPU().getFrame(), "nothing was put back, because nothing had to be");

        var alreadyRunning = load();
        advanceFrame(alreadyRunning);

        var refused = assertThrows(
                MovieException.class, () -> movie.applyAnchor(alreadyRunning));

        assertTrue(refused.getMessage().contains("power on"));
    }

    @Test
    void aMovieCannotBeRecordedFromPowerOnAfterTheMachineHasRun() throws IOException {
        var nes = load();
        advanceFrame(nes);

        assertThrows(MovieException.class, () -> MovieRecorder.atPowerOn(nes, List.of()));
    }

    @Test
    void anAnchoredMovieOpensWhereItWasAnchored() throws IOException {
        var nes = load();

        for (var i = 0; i < 120; i++) {
            advanceFrame(nes);
        }

        var atTheAnchor = save(nes);
        var recorder = MovieRecorder.anchoredAt(nes, List.of());

        play(nes, recorder, 30, 0);

        var movie = roundTrip(recorder.movie());
        var opened = load();

        movie.applyAnchor(opened);

        assertEquals(120, opened.getPPU().getFrame());
        assertArrayEquals(atTheAnchor, save(opened), "the whole machine, not just the frame count");
    }

    /**
     * A replay longer than the movie is a legitimate thing to want -- what does the game do when the
     * player stops playing? -- and the honest answer for a frame nobody recorded is that nobody was
     * touching the pad.
     */
    @Test
    void runningPastTheEndPressesNothing() throws IOException {
        var nes = load();
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        play(nes, recorder, 10, Controller.BUTTON_A);

        var movie = recorder.movie();

        assertEquals(Controller.BUTTON_A, movie.buttonsAt(9));
        assertEquals(0, movie.buttonsAt(10));
        assertEquals(0, movie.buttonsAt(9999));
        assertEquals(0, movie.buttonsAt(-1));
    }

    // ================================================================================== the file

    @Test
    void aMovieWrittenAndReadIsTheSameMovie() throws IOException {
        var nes = load();
        var codes = List.of(GameGenieCode.decode("SXIOPO"), GameGenieCode.decode("IKAEAUAK"));
        var recorder = MovieRecorder.atPowerOn(nes, codes);

        play(nes, recorder, 20, 0);
        recorder.reset();
        nes.reset();
        play(nes, recorder, 20, Controller.BUTTON_B | Controller.BUTTON_LEFT);

        var path = directory.resolve("take.mnm");
        recorder.movie().write(path);

        var read = Movie.read(path);

        assertEquals(Movie.VERSION, read.header().formatVersion());
        assertEquals(nes.getCart().sha256(), read.header().romSHA256());
        assertEquals(0, read.header().mapperNumber());
        assertEquals(Region.NTSC, read.header().region());
        assertEquals(1, read.header().ports());
        assertEquals(40, read.frameCount());
        assertFalse(read.anchored());
        assertArrayEquals(new long[]{20}, read.resets());
        assertEquals(0, read.buttonsAt(19));
        assertEquals(Controller.BUTTON_B | Controller.BUTTON_LEFT, read.buttonsAt(20));
        assertEquals(
                List.of("SXIOPO", "IKAEAUAK"),
                read.genie().stream().map(GameGenieCode::text).toList(),
                "the codes are pinned, since nothing about the cartridge says they were in");
    }

    /**
     * What lets a chooser say "1,203 frames, from a state at frame 4,201" for a directory of files
     * without inflating any of them -- and what lets a movie from the wrong cartridge be refused
     * before it is opened properly.
     */
    @Test
    void theHeaderCanBeReadWithoutInflatingTheRest() throws IOException {
        var nes = load();

        for (var i = 0; i < 42; i++) {
            advanceFrame(nes);
        }

        var recorder = MovieRecorder.anchoredAt(nes, List.of());
        play(nes, recorder, 17, 0);

        var path = directory.resolve("anchored.mnm");
        recorder.movie().write(path);

        var header = Movie.header(path);

        assertEquals(Movie.VERSION, header.formatVersion());
        assertEquals(nes.getCart().sha256(), header.romSHA256());
        assertEquals(Region.NTSC, header.region());
        assertTrue(header.anchored());
        assertEquals(42, header.anchorFrame());
        assertEquals(17, header.frameCount());
    }

    @Test
    void aMovieFromAnotherCartridgeIsRefused() throws IOException {
        var nes = load();
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        play(nes, recorder, 5, 0);

        var movie = recorder.movie();
        var other = load("/mmc3-test-2/1-clocking.nes");
        var before = save(other);

        var refused = assertThrows(MovieException.class, () -> movie.applyAnchor(other));

        assertTrue(refused.getMessage().contains("another cartridge"));
        assertArrayEquals(before, save(other), "and the machine was not touched");
    }

    /**
     * A movie is a count of frames and a frame is not the same length on the two machines, so this
     * is refused rather than converted: the buttons would drift apart from the first second.
     */
    @Test
    void aMovieFromTheOtherMachineIsRefused() throws IOException {
        var nes = load();
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        play(nes, recorder, 5, 0);

        var movie = recorder.movie();
        var pal = new NES(nes.getCart(), Region.PAL);

        var refused = assertThrows(MovieException.class, () -> movie.applyAnchor(pal));

        assertTrue(refused.getMessage().contains("PAL"));
    }

    @Test
    void aMovieFromALaterVersionIsRefused() throws IOException {
        var file = recorded(10);

        // The version sits at offset 8, outside the compression, so this is one byte.
        file[9] = (byte) (Movie.VERSION + 1);

        var refused = assertThrows(
                MovieException.class, () -> Movie.read(new ByteArrayInputStream(file)));

        assertTrue(refused.getMessage().contains("version " + (Movie.VERSION + 1)));
    }

    @Test
    void aFileThatIsNotAMovieIsRefused() {
        assertThrows(MovieException.class,
                () -> Movie.read(new ByteArrayInputStream("not a movie at all".getBytes(
                        StandardCharsets.US_ASCII))));

        assertThrows(MovieException.class,
                () -> Movie.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void aDamagedMovieIsRefusedRatherThanPlayedPartWay() throws IOException {
        var file = recorded(30);

        // Well inside the gzipped body, so the CRC32 at the end of it catches this.
        file[Movie.HEADER_BYTES + 20] ^= 0xFF;

        assertThrows(MovieException.class, () -> Movie.read(new ByteArrayInputStream(file)));
    }

    @Test
    void aMovieWhoseFrameCountDisagreesWithItsButtonsIsRefused() throws IOException {
        var file = recorded(30);

        // Offset 52, and outside the compression like everything else in the header.
        file[59] = (byte) 31;

        var refused = assertThrows(
                MovieException.class, () -> Movie.read(new ByteArrayInputStream(file)));

        assertTrue(refused.getMessage().contains("31 frames"));
    }

    @Test
    void aChunkThisVersionHasNeverHeardOfIsSteppedOver() throws IOException {
        var file = withExtraChunk(recorded(25), "ZZZZ", new byte[]{9, 9, 9, 9, 9});
        var movie = Movie.read(new ByteArrayInputStream(file));

        assertEquals(25, movie.frameCount(), "the chunks it does know still landed");
    }

    @Test
    void aMovieCarryingSomethingThatIsNotACodeIsRefused() throws IOException {
        var file = withExtraChunk(
                recorded(5), "GENI", new byte[]{6, 'G', 'O', 'S', 'S', 'I', 'B'});

        assertThrows(MovieException.class, () -> Movie.read(new ByteArrayInputStream(file)));
    }

    // ================================================================================== internals

    private static NES load() throws IOException {
        return load("/nestest/nestest.nes");
    }

    private static NES load(final String resource) throws IOException {
        try (var rom = MovieTests.class.getResourceAsStream(resource)) {
            assertNotNull(rom, resource);
            return new NES(Cart.load(rom.readAllBytes(), resource));
        }
    }

    /**
     * A session played with the pad held one way throughout, written down as it goes -- which is
     * exactly the shape both front ends drive a recorder in.
     */
    private static void play(
            final NES nes, final MovieRecorder recorder, final int frames, final int mask) {
        for (var i = 0; i < frames; i++) {
            nes.getController1().setButtons(mask);
            advanceFrame(nes);
            recorder.frame(mask);
        }
    }

    /**
     * The replay loop, in the order the reset has to happen in: pressed at the start of the frame,
     * then the buttons for it, then the frame itself.
     */
    private static void replay(final NES nes, final Movie movie, final long frames) {
        movie.applyAnchor(nes);

        for (var i = 0L; i < frames; i++) {
            if (movie.resetsAt(i)) {
                nes.reset();
            }

            nes.getController1().setButtons(movie.buttonsAt(i));
            advanceFrame(nes);
        }
    }

    /**
     * Through the file and back, so that every assertion about a replay is also an assertion that
     * the format carried what the recorder held.
     */
    private static Movie roundTrip(final Movie movie) throws IOException {
        var out = new ByteArrayOutputStream();

        movie.write(out);

        return Movie.read(new ByteArrayInputStream(out.toByteArray()));
    }

    private static byte[] recorded(final int frames) throws IOException {
        var nes = load();
        var recorder = MovieRecorder.atPowerOn(nes, List.of());

        play(nes, recorder, frames, 0);

        var out = new ByteArrayOutputStream();
        recorder.movie().write(out);

        return out.toByteArray();
    }

    private static byte[] save(final NES nes) throws IOException {
        var out = new ByteArrayOutputStream();

        SaveState.write(nes, out);

        return out.toByteArray();
    }

    private static void advanceFrame(final NES nes) {
        var ppu = nes.getPPU();
        var frame = ppu.getFrame();

        do {
            nes.tick();
        } while (ppu.getFrame() == frame);
    }

    /**
     * Rebuilds a movie with one more chunk on the end of its body, fixing the declared length in the
     * header so the file is well formed in every way except that this build has never heard of the
     * tag.
     */
    private static byte[] withExtraChunk(
            final byte[] file, final String tag, final byte[] payload) throws IOException {
        var header = Arrays.copyOf(file, Movie.HEADER_BYTES);
        final byte[] body;

        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(
                Arrays.copyOfRange(file, Movie.HEADER_BYTES, file.length)))) {
            body = gzip.readAllBytes();
        }

        var changed = new ByteArrayOutputStream();
        changed.write(body);
        changed.write(tag.getBytes(StandardCharsets.US_ASCII));
        changed.write(new byte[]{0, 0, 0, (byte) payload.length});
        changed.write(payload);

        var grown = changed.toByteArray();

        for (var i = 0; i < 4; i++) {
            header[64 + i] = (byte) (grown.length >> (24 - i * 8));
        }

        var out = new ByteArrayOutputStream();
        out.write(header);

        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(grown);
        }

        return out.toByteArray();
    }

    /**
     * Kept honest about where the movie files land: a temporary directory, never beside a fixture.
     */
    @Test
    void aMovieIsWrittenWhereItWasAsked() throws IOException {
        var path = directory.resolve("nested").resolve("take.mnm");

        Movie.read(new ByteArrayInputStream(recorded(3))).write(path);

        assertTrue(Files.exists(path));
        assertEquals(3, Movie.header(path).frameCount());
    }
}
