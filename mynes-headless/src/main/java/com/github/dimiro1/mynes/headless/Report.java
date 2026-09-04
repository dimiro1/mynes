package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.state.Movie;
import com.github.dimiro1.mynes.video.VideoFilter;

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

    /**
     * A patch that was applied, and what it turned out to contain. The counts are here because a
     * patch cut against a different dump of the same game applies without complaint and does nothing
     * useful, and "0 records" is the only sign of it the report can offer.
     */
    public record Patch(Path path, int records, int bytes) {
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
     * @param patches         the patches applied to the ROM image before it was read as a cartridge.
     * @param screenshots     the frames photographed.
     * @param dumps           the memories written out.
     * @param expectations    what was asked of the run, and whether it held.
     * @param recorded        the movie this run wrote, or null if it recorded nothing.
     * @param recordedTo      where that went.
     * @param replayed        the movie this run played, or null if it played none.
     * @param exitCode        what the process is about to return.
     */
    public record Outcome(
            long frames,
            StoppedBecause stoppedBecause,
            long wallClockMillis,
            Instant startedAt,
            List<Patch> patches,
            List<Long> screenshots,
            List<Dump> dumps,
            List<Expectation> expectations,
            Movie recorded,
            Path recordedTo,
            Movie replayed,
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

        // And which things the console does not do were switched on, which is the third. Read back
        // off the machine rather than off the command line, so that a REPL session that flipped one
        // half way through is reported as it ended rather than as it started. Always present, and
        // one key per hack rather than a list, so two reports compare key for key.
        var hacks = run.putObject("hacks");
        hacks.put("unlimitedSprites", ppu.isUnlimitedSprites());

        // An object rather than a number, because "how many lines" is two questions with different
        // answers -- and because a key that was sometimes a boolean and sometimes a count would not
        // compare. Always present, and both zero on a machine nobody overclocked.
        var overclock = hacks.putObject("overclock");
        overclock.put("beforeNmi", ppu.getOverclock().beforeNmi());
        overclock.put("afterNmi", ppu.getOverclock().afterNmi());

        // And which Game Genie codes were in, which is the fourth -- and the one that matters most,
        // because it is the only one of the four a digest cannot stand in for. A patched run has its
        // own cart.sha256; a run with codes in has the cartridge's, since the cartridge really is
        // untouched. So this array is the whole of what tells it apart from a plain one.
        //
        // An array rather than one key per code: the set is open ended, so the key-for-key comparison
        // that run.hacks is shaped for cannot be had here. Empty rather than absent when there are
        // none, so two reports still line up.
        var codes = run.putArray("genie");
        for (var code : session.genie().codes()) {
            var node = codes.addObject();
            node.put("code", code.text());
            node.put("address", code.address());
            node.put("value", code.value());

            if (code.hasCompare()) {
                node.put("compare", code.compare());
            } else {
                node.putNull("compare");
            }
        }

        // Where the run started, which decides whether it is comparable with another one at all. A
        // run that began from a save state and one that began at power on are not two measurements
        // of the same thing, and telling them apart is the whole job of this document.
        //
        // And how much of it was played twice, for the same reason: a session that went back thirty
        // frames and ran them again visited those frames with the machine in a state the frame
        // counter no longer describes, so its frameChanges and its sound are not the straight run's.
        // Always present and 0 when nobody rewound, so two reports still compare key for key.
        var state = run.putObject("state");

        // A replayed run started wherever the movie says it did, which is a state inside the movie
        // whenever the movie is anchored -- so a replay of an anchored take is no more comparable
        // with a power-on run than a --load-state one is.
        state.put("startedFromPowerOn",
                options.loadState() == null
                        && (outcome.replayed() == null || !outcome.replayed().anchored()));
        put(state, "loadedFrom", options.loadState());
        put(state, "savedTo", options.saveState());
        state.put("framesRewound", session.framesRewound());

        // The fifth and sixth things that decide whether two runs are comparable, and the only two
        // that describe a whole session rather than a moment in one. Always present, with explicit
        // nulls, so two reports line up key for key whether either run touched a movie.
        var recorded = run.putObject("record");
        put(recorded, "savedTo", outcome.recordedTo());
        describe(recorded, outcome.recorded());

        var replayed = run.putObject("replay");
        put(replayed, "playedFrom", options.play());
        describe(replayed, outcome.replayed());

        var cartridge = report.putObject("cart");
        cartridge.put("file", cart.filename());
        cartridge.put("name", Path.of(cart.filename()).getFileName().toString());

        // Of the image the machine actually ran, which is the patched one when anything below this
        // is non-empty. It names what ran rather than what is on disk, which is what a digest in
        // this document is for.
        cartridge.put("sha256", cart.sha256());

        var patches = cartridge.putArray("patches");
        for (var patch : outcome.patches()) {
            var node = patches.addObject();
            node.put("path", patch.path().toString());
            node.put("records", patch.records());
            node.put("bytes", patch.bytes());
        }

        // Which header was read decides what the rest of it meant, so it goes first. The RAM
        // sizes are the header's claim; sram.bytes below is what the mapper fitted, and the two
        // differ where a header says nothing and the board gets the 8KB every board here has.
        cartridge.put("format", cart.format().id());
        cartridge.put("mapper", cart.mapperNumber());
        cartridge.put("submapper", cart.submapper());
        cartridge.put("prgROMBytes", cart.prgROM().length);
        cartridge.put("chrROMBytes", cart.chrROM().length);
        cartridge.put("prgRAMBytes", cart.ram().prgRAM());
        cartridge.put("prgNVRAMBytes", cart.ram().prgNVRAM());
        cartridge.put("chrRAMBytes", cart.ram().chrRAM());
        cartridge.put("chrNVRAMBytes", cart.ram().chrNVRAM());
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
        // Whether the beam is on a line the overclock is running again, which is what explains a
        // report that stopped on line 240 or the last line of blanking and looks stuck there.
        picture.put("onExtraLine", ppu.isOnExtraLine());
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
        video.put("filter", session.filter().id());

        // Explicitly null rather than absent when the bare palette is drawing, the way run.record
        // and run.replay are: the question "how hard was the filter applied" has an answer of
        // "there was none".
        if (session.filter() != VideoFilter.NONE) {
            video.put("filterStrength", session.strength().id());
        } else {
            video.putNull("filterStrength");
        }

        // And the same again for the glass, which only the tube has.
        if (session.filter() == VideoFilter.CRT) {
            video.put("warp", session.warp());
        } else {
            video.putNull("warp");
        }

        // Never null, unlike the two above it, because the shape of a pixel is not a setting on a
        // filter: every one of the three draws them square or draws them the television's shape.
        // Not on the comparability checklist either, for the reason the filters are not -- the hash
        // and the colour counts are taken over the indices the chip emitted, so all this moves is
        // how wide the PNGs are.
        video.put("tvAspect", session.tvAspect());

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

        // The one thing on the comparability checklist that is not under run, and put here on
        // purpose: everything a muted voice changes is one of the five numbers above it, and nothing
        // it changes is anything the machine did. Read back off the machine rather than off the
        // command line, for the reason run.hacks is: a session that switched one half way through is
        // reported as it ended. Walked in the enum's own order rather than the set's, so two reports
        // of one run list them the same way round, and empty rather than absent when nobody muted
        // anything.
        var muted = audio.putArray("muted");

        for (var channel : APUChannel.values()) {
            if (nes.getAPU().isChannelMuted(channel)) {
                muted.add(channel.id());
            }
        }

        if (options.audio()) {
            audio.put("wav", options.wavPath().toString());
        } else {
            audio.putNull("wav");
        }

        var input = report.putObject("input");
        input.put("pressFrames", options.pressFrames());
        input.put("framesWithInput", framesWithInput(options, outcome));

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
     * <p>
     * A replay is asked the movie rather than the schedule, which {@code --play} refused and which
     * is therefore empty: answering 0 for a run that pressed something on every frame would be the
     * one number in this document most likely to be believed.
     */
    private static long framesWithInput(final Options options, final Outcome outcome) {
        var count = 0L;

        for (var frame = 0L; frame < outcome.frames(); frame++) {
            var buttons = outcome.replayed() != null
                    ? outcome.replayed().buttonsAt(frame)
                    : options.input().buttonsAt(frame);

            if (buttons != 0) {
                count++;
            }
        }

        return count;
    }

    /**
     * What a movie was, or the same three keys holding nulls where there was no movie. The path it
     * came from or went to is put by the caller, since only that knows which of the two this is.
     */
    private static void describe(final Json.Object node, final Movie movie) {
        if (movie == null) {
            node.putNull("frames");
            node.putNull("anchored");
            node.putNull("anchorFrame");
            return;
        }

        node.put("frames", movie.frameCount());
        node.put("anchored", movie.anchored());
        node.put("anchorFrame", movie.anchorFrame());
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

    // A convenience for the parts of a document that are the same wherever they are built.
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
