package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.Cart;

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * What happened, as JSON.
 * <p>
 * Two things shape it. Everything that could differ between two runs of the same command is under
 * {@code host} and nowhere else, so that {@code jq 'del(.host)' a.json b.json} comes out identical
 * -- which turns the determinism from something true into something a person can check. And it is
 * bounded: memory goes to files rather than into the document, and a long list of screenshot frames
 * is cut short, so the report is a few kilobytes however long the run was and printing it to
 * standard output is always safe.
 */
public final class Report {
    /**
     * How many screenshot frames the report names before it stops listing them and leaves the
     * pattern to say where the rest are.
     */
    private static final int MAX_LISTED_FRAMES = 200;

    /**
     * The version of this document's shape. Bumped when something already in it changes meaning,
     * so that a reader written against an older emulator can tell.
     */
    private static final int VERSION = 1;

    /**
     * Why the run stopped.
     */
    public enum StoppedBecause {
        /**
         * It ran the frames it was asked for.
         */
        FRAMES,

        /**
         * Real time ran out first, so the report describes a shorter run than the one requested.
         */
        TIMEOUT,

        /**
         * Somebody ended an interactive session.
         */
        QUIT
    }

    public record Dump(String what, Path path, int bytes) {
    }

    public record Expectation(String name, boolean passed, String detail) {
    }

    /**
     * Everything about the run that is not in the machine itself.
     *
     * @param frames          how many frames it got through.
     * @param stoppedBecause  why it stopped.
     * @param wallClockMillis how long that took in real time.
     * @param startedAt       when it started.
     * @param screenshots     the frames photographed.
     * @param dumps           the memories written out.
     * @param expectations    what was asked of the run, and whether it held.
     * @param exitCode        what the process is about to return.
     */
    public record Outcome(
            long frames,
            StoppedBecause stoppedBecause,
            long wallClockMillis,
            Instant startedAt,
            List<Long> screenshots,
            List<Dump> dumps,
            List<Expectation> expectations,
            int exitCode) {
    }

    private Report() {
    }

    /**
     * Builds the whole document.
     *
     * @param options what was asked for.
     * @param cart    the cartridge.
     * @param session the machine, at the moment the run stopped.
     * @param outcome everything else.
     * @return the report, indented.
     */
    public static String write(
            final Options options,
            final Cart cart,
            final Session session,
            final Outcome outcome
    ) {
        var nes = session.nes();
        var cpuState = nes.getCPU().getState();
        var ppu = nes.getPPU();
        var region = nes.getRegion();
        var analysis = session.analyse();
        var audioStats = session.audioStats();

        var report = Json.object();
        report.put("reportVersion", VERSION);

        var run = report.putObject("run");
        run.put("frames", outcome.frames());
        run.put("cpuCycles", cpuState.cycles());
        run.put("apuCycles", nes.getAPU().getCycles());
        run.put("completed", outcome.stoppedBecause() != StoppedBecause.TIMEOUT);
        run.put("stoppedBecause", outcome.stoppedBecause().name().toLowerCase());

        // Which machine this actually was, which is the other thing besides the ROM and the input
        // that decides whether two runs are comparable at all. A PAL frame is 106392 dots and an
        // NTSC one 89342, so nothing below this line means the same on both.
        run.put("region", region.id());
        run.put("regionForced", options.region() != null);

        // Where the run started, which decides whether it is comparable with another one at all. A
        // run that began from a save state and one that began at power on are not two measurements
        // of the same thing, and telling them apart is the whole job of this document.
        var state = run.putObject("state");
        state.put("startedFromPowerOn", options.loadState() == null);
        put(state, "loadedFrom", options.loadState());
        put(state, "savedTo", options.saveState());

        var cartridge = report.putObject("cart");
        cartridge.put("file", cart.filename());
        cartridge.put("name", Path.of(cart.filename()).getFileName().toString());
        cartridge.put("sha256", cart.sha256());
        cartridge.put("mapper", cart.mapperNumber());
        cartridge.put("prgROMBytes", cart.prgROM().length);
        cartridge.put("chrROMBytes", cart.chrROM().length);
        cartridge.put("headerMirror", cart.mirror());
        // Live rather than out of the header: MMC1 changes it while the game runs, so what the
        // cartridge is doing now is more use than what it started as.
        cartridge.put("mirroring", cart.mapper().mirroring().name());
        cartridge.put("battery", cart.hasBattery());

        // What the header claimed, and what that came to. They differ for the two claims that are
        // not machines: multi-region, which is run as NTSC, and Dendy, which is run as PAL.
        cartridge.put("timing", cart.timing().id());
        cartridge.put("region", cart.region().id());

        var sram = cartridge.putObject("sram");
        sram.put("bytes", cart.mapper().prgRAM().length);
        put(sram, "in", options.sramIn());
        put(sram, "out", options.sramOut());

        var cpu = report.putObject("cpu");
        cpu.put("a", cpuState.a());
        cpu.put("x", cpuState.x());
        cpu.put("y", cpuState.y());
        cpu.put("sp", cpuState.sp());
        cpu.put("pc", cpuState.pc());
        cpu.put("p", cpuState.p());
        cpu.put("cycles", cpuState.cycles());

        var picture = report.putObject("ppu");
        picture.put("frame", ppu.getFrame());
        picture.put("scanline", ppu.getScanline());
        picture.put("dot", ppu.getDot());
        picture.put("v", ppu.getV());
        picture.put("t", ppu.getT());
        picture.put("fineX", ppu.getFineX());
        picture.put("writeLatch", ppu.isWriteLatchSet());
        picture.put("renderingEnabled", ppu.isRenderingEnabled());
        picture.put("backgroundVisible", ppu.isBackgroundLayerVisible());
        picture.put("spriteVisible", ppu.isSpriteLayerVisible());

        var paletteRAM = picture.putArray("paletteRAM");
        for (var entry : session.readPalette()) {
            paletteRAM.add(entry);
        }

        var video = report.putObject("video");
        video.put("palette", options.paletteFor(region).id());
        video.put("overscan", options.fullFrame() ? "full" : "cropped");
        video.put("scale", options.scale());

        var finalFrame = video.putObject("finalFrame");
        finalFrame.put("hash", hex(analysis.hash()));
        finalFrame.put("uniqueColours", analysis.uniqueColours());
        finalFrame.put("blank", analysis.blank());

        var topColours = finalFrame.putArray("topColours");
        for (var colour : analysis.topColours()) {
            var node = topColours.addObject();
            node.put("entry", colour.entry());
            node.put("count", colour.count());
        }

        video.put("frameChanges", session.frameChanges());
        video.put("framesSinceLastChange", session.framesSinceLastChange());

        var screenshots = video.putObject("screenshots");
        screenshots.put("count", outcome.screenshots().size());
        screenshots.put("pattern", options.screenshotPattern());

        var frames = screenshots.putArray("frames");
        for (var frame : outcome.screenshots().stream().limit(MAX_LISTED_FRAMES).toList()) {
            frames.add(frame);
        }

        if (outcome.screenshots().size() > MAX_LISTED_FRAMES) {
            screenshots.put("first", outcome.screenshots().getFirst());
            screenshots.put("last", outcome.screenshots().getLast());
            screenshots.put("truncated", true);
        }

        var audio = report.putObject("audio");
        audio.put("sampleRate", APU.SAMPLE_RATE);
        audio.put("samples", audioStats.samples());
        audio.put("seconds", seconds(audioStats.samples()));
        audio.put("peak", audioStats.peak());
        audio.put("rms", audioStats.rms());
        audio.put("silentFrames", audioStats.silentFrames());

        if (options.audio()) {
            audio.put("wav", options.wavPath().toString());
        } else {
            audio.putNull("wav");
        }

        var input = report.putObject("input");
        input.put("pressFrames", options.pressFrames());
        input.put("framesWithInput", framesWithInput(options, outcome.frames()));

        var resetAt = input.putArray("resetAt");
        for (var frame : options.resetAt()) {
            resetAt.add(frame);
        }

        // The parse echoed back rather than the text as typed, so a spec that was read differently
        // from how it was meant shows up here instead of as a game that mysteriously did nothing.
        var events = input.putArray("events");
        for (var event : options.input().events()) {
            var node = events.addObject();
            node.put("kind", event.kind().name().toLowerCase());
            node.put("from", event.from());

            switch (event.kind()) {
                case HOLD -> node.put("to", event.to());
                case PULSE -> {
                    node.put("every", event.every());

                    if (event.count() > 0) {
                        node.put("count", event.count());
                    } else {
                        node.putNull("count");
                    }
                }
                case PRESS -> {
                }
            }

            var buttons = node.putArray("buttons");
            InputSchedule.describe(event.buttons()).forEach(buttons::add);
        }

        var dumps = report.putArray("dumps");
        for (var dump : outcome.dumps()) {
            var node = dumps.addObject();
            node.put("what", dump.what());
            node.put("path", dump.path().toString());
            node.put("bytes", dump.bytes());
        }

        var expectations = report.putArray("expectations");
        for (var expectation : outcome.expectations()) {
            var node = expectations.addObject();
            node.put("name", expectation.name());
            node.put("passed", expectation.passed());
            node.put("detail", expectation.detail());
        }

        report.put("exitCode", outcome.exitCode());

        // Everything below this line is a fact about the computer rather than about the run, which
        // is the whole reason it is fenced off: del(.host) is what makes two reports comparable.
        var host = report.putObject("host");
        host.put("workingDirectory", Path.of("").toAbsolutePath().toString());
        host.put("wallClockMillis", outcome.wallClockMillis());
        host.put("framesPerSecond", framesPerSecond(outcome));
        host.put("startedAt", outcome.startedAt().truncatedTo(ChronoUnit.SECONDS).toString());
        host.put("java", System.getProperty("java.version"));

        return Json.pretty(report);
    }

    /**
     * How many frames of the run had a button held down. A schedule that turns out to press nothing
     * is the commonest reason a headless run of a real cartridge shows a title screen and no game.
     */
    private static long framesWithInput(final Options options, final long frames) {
        var count = 0L;

        for (var frame = 0L; frame < frames; frame++) {
            if (options.input().buttonsAt(frame) != 0) {
                count++;
            }
        }

        return count;
    }

    private static double framesPerSecond(final Outcome outcome) {
        if (outcome.wallClockMillis() <= 0) {
            return 0.0;
        }

        var rate = outcome.frames() * 1000.0 / outcome.wallClockMillis();

        return Math.round(rate * 10.0) / 10.0;
    }

    private static double seconds(final long samples) {
        return Math.round(samples * 1_000.0 / APU.SAMPLE_RATE) / 1_000.0;
    }

    /**
     * How a frame hash is spelled: sixteen hex digits, so that two of them line up when they are
     * read side by side.
     */
    static String hex(final long hash) {
        return String.format("%016x", hash);
    }

    /**
     * A convenience for the parts of a document that are the same wherever they are built.
     */
    /**
     * A path, or a null where there was no path. Explicitly null rather than absent, so that
     * {@code jq} over two reports compares the same set of keys either way.
     */
    private static void put(final Json.Object node, final String name, final Path path) {
        if (path == null) {
            node.putNull(name);
        } else {
            node.put(name, path.toString());
        }
    }

    static void putStateOf(final Json.Object node, final Session session) {
        node.put("frame", session.frame());
        node.put("hash", hex(session.analyse().hash()));
    }
}
