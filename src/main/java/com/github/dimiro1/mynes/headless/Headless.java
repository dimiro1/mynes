package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.state.BatteryRAM;
import com.github.dimiro1.mynes.state.SaveStateException;
import com.github.dimiro1.mynes.ui.palette.Palettes;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Runs a cartridge with nobody watching, and writes down what happened.
 *
 * @see com.github.dimiro1.mynes.headless
 */
public final class Headless {
    private static final Logger logger = System.getLogger("HEADLESS");

    public static final int EXIT_OK = 0;
    public static final int EXIT_ERROR = 1;
    public static final int EXIT_USAGE = 2;
    public static final int EXIT_TIMEOUT = 3;
    public static final int EXIT_EXPECTATION = 4;
    public static final int EXIT_ROM = 5;

    private Headless() {
    }

    /**
     * Does the whole thing.
     *
     * @param args the command line, with {@code --headless} already taken off the front.
     * @return the code the process should exit with.
     */
    public static int run(final String[] args) {
        final Options options;

        try {
            options = Options.parse(args);
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            return EXIT_USAGE;
        }

        if (options.help()) {
            System.out.print(Options.usage());
            return EXIT_OK;
        }

        if (options.listPalettes()) {
            for (var palette : Palettes.all()) {
                System.out.printf("%-18s %s%n", palette.id(), palette.name());
            }

            return EXIT_OK;
        }

        try {
            return runCartridge(options);
        } catch (UsageException | SaveStateException e) {
            // A save state that will not load is a mistake on the command line in every sense that
            // matters to a script: the file named was the wrong one. No sixth exit code for it.
            System.err.println(e.getMessage());
            return EXIT_USAGE;
        } catch (IOException e) {
            System.err.println("could not write what the run produced: " + e.getMessage());
            return EXIT_ERROR;
        } catch (RuntimeException e) {
            logger.log(Level.ERROR, "the run failed", e);
            return EXIT_ERROR;
        }
    }

    private static int runCartridge(final Options options) throws IOException {
        final byte[] image;

        try {
            image = Files.readAllBytes(options.rom());
        } catch (IOException e) {
            System.err.println(options.rom() + " could not be read: " + e.getMessage());
            return EXIT_ROM;
        }

        final Cart cart;

        try {
            cart = Cart.load(image, options.rom().toString());
        } catch (RuntimeException e) {
            // Everything Cart.load throws is unchecked, and a file that is not a cartridge can
            // fail in several ways -- a bad magic number, a mapper nobody has written, a truncated
            // image that runs the buffer out. They are all the same answer to the caller.
            System.err.println(options.rom() + " is not a cartridge this can run: " + e);
            return EXIT_ROM;
        }

        var region = options.regionFor(cart);
        var palette = options.paletteFor(region);

        if (cart.timing() == Cart.Timing.DENDY && options.region() == null) {
            logger.log(Level.WARNING, options.rom().getFileName()
                    + " says Dendy, which is not modelled; running it as PAL");
        }

        logger.log(Level.INFO, "running " + options.rom().getFileName()
                + ", mapper " + cart.mapperNumber()
                + ", " + region.label()
                + ", " + options.frames() + " frames");

        Files.createDirectories(options.outDir());

        var startedAt = Instant.now();
        var startedNanos = System.nanoTime();

        // No AudioOutput: opening a sound card would make the run depend on the computer it ran on,
        // and there is nobody here to listen to it anyway. The samples are counted, and written to
        // a file if one was asked for.
        try (var wav = options.audio() ? new WavWriter(options.wavPath()) : null) {
            var session = new Session(
                    new NES(cart, region), palette.colours(), wav);

            // The cartridge RAM first and the save state second, because a state carries its own copy
            // of that RAM and is the more specific answer of the two.
            if (options.sramIn() != null) {
                var read = BatteryRAM.read(session.nes(), options.sramIn());

                if (read < 0) {
                    throw new UsageException(options.sramIn()
                            + " cannot be loaded: this cartridge has no RAM at $6000 to put it in.");
                }

                logger.log(Level.INFO,
                        "filled " + read + " bytes of cartridge RAM from " + options.sramIn());
            }

            if (options.loadState() != null) {
                session.loadState(options.loadState());
                logger.log(Level.INFO,
                        "started from " + options.loadState() + ", at frame " + session.frame());
            }

            var outcome = options.interactive()
                    ? interactive(options, session)
                    : oneShot(options, session);

            var wallClockMillis = (System.nanoTime() - startedNanos) / 1_000_000;

            if (options.saveState() != null) {
                session.saveState(options.saveState());
                logger.log(Level.INFO, "wrote a save state to " + options.saveState());
            }

            if (options.sramOut() != null) {
                // Deliberately not asking the cartridge whether it has a battery. The window obeys
                // the cartridge; the command line obeys the flag, which is also what makes this
                // testable against the only ROM vendored here.
                var written = BatteryRAM.write(session.nes(), options.sramOut());

                if (written < 0) {
                    throw new UsageException(
                            "there is no cartridge RAM to write to " + options.sramOut() + ".");
                }

                logger.log(Level.INFO,
                        "wrote " + written + " bytes of cartridge RAM to " + options.sramOut());
            }

            var dumps = writeDumps(options, session);
            var expectations = check(options, session);
            var exitCode = exitCode(outcome.stoppedBecause(), expectations);

            var report = Report.write(
                    options,
                    cart,
                    session,
                    new Report.Outcome(
                            outcome.frames(),
                            outcome.stoppedBecause(),
                            wallClockMillis,
                            startedAt,
                            outcome.screenshots(),
                            dumps,
                            expectations,
                            exitCode));

            publish(options, report);

            logger.log(Level.INFO, "stopped after " + outcome.frames()
                    + " frames because " + outcome.stoppedBecause().name().toLowerCase()
                    + ", exit " + exitCode);

            return exitCode;
        }
    }

    /**
     * What the run itself produced, before anything is asked of it.
     */
    private record Outcome(
            long frames, Report.StoppedBecause stoppedBecause, List<Long> screenshots) {
    }

    /**
     * Plays the schedule.
     */
    private static Outcome oneShot(final Options options, final Session session)
            throws IOException {
        var resets = new HashSet<>(options.resetAt());
        var screenshots = new ArrayList<Long>();
        var deadline = System.nanoTime() + options.timeout().toNanos();
        var stoppedBecause = Report.StoppedBecause.FRAMES;

        for (var frame = 1L; frame <= options.frames(); frame++) {
            if (resets.contains(frame)) {
                session.reset();
            }

            // Set before the frame is emulated rather than after, so that a one frame press is
            // held for the whole of the frame a game might read the pad anywhere in.
            session.setButtons(options.input().buttonsAt(frame));
            session.advanceFrame();

            if (options.wantsScreenshotAt(frame)) {
                shoot(options, session, frame);
                screenshots.add(frame);
            }

            if (System.nanoTime() - deadline >= 0) {
                stoppedBecause = Report.StoppedBecause.TIMEOUT;
                logger.log(Level.WARNING, "timed out at frame " + frame + " of " + options.frames());
                break;
            }
        }

        // Last is whichever frame the run actually ended on, which is not the same as the frame it
        // was asked for once a timeout has had its say.
        if (options.screenshotLast() && !screenshots.contains(session.frame())) {
            shoot(options, session, session.frame());
            screenshots.add(session.frame());
        }

        screenshots.sort(Long::compare);

        return new Outcome(session.frame(), stoppedBecause, List.copyOf(screenshots));
    }

    /**
     * Takes commands instead.
     */
    private static Outcome interactive(final Options options, final Session session)
            throws IOException {
        try (var in = options.scriptPath() != null
                ? Files.newBufferedReader(options.scriptPath())
                : new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {

            var repl = new Repl(session, options, in, System.out, wantsText(options));

            return new Outcome(repl.run(), Report.StoppedBecause.QUIT, List.of());
        }
    }

    /**
     * Whether the REPL should answer in readable text rather than compact JSON.
     * <p>
     * {@code --format} has the final say when it names one. Left on {@code auto}, a person typing at
     * a terminal gets text and anything piped or scripted gets JSON, so the "one JSON document per
     * line" contract that machines read against is only ever broken for a human session.
     */
    private static boolean wantsText(final Options options) {
        return switch (options.format()) {
            case TEXT -> true;
            case JSON -> false;
            case AUTO -> options.scriptPath() == null && System.console() != null;
        };
    }

    private static void shoot(final Options options, final Session session, final long frame)
            throws IOException {
        session.screenshot(options.screenshotPath(frame), !options.fullFrame(), options.scale());
    }

    private static List<Report.Dump> writeDumps(final Options options, final Session session)
            throws IOException {
        var dumps = new ArrayList<Report.Dump>();

        for (var what : options.dumps()) {
            var bytes = session.dump(what);
            var path = options.dumpPath(what);

            Files.write(path, bytes);
            dumps.add(new Report.Dump(what, path, bytes.length));
        }

        return List.copyOf(dumps);
    }

    /**
     * Answers the three questions the run was asked to answer.
     * <p>
     * Three and no more, on purpose. The report holds everything else, and {@code jq} is a better
     * query language than a growing pile of flags would be; what these are for is the two things
     * {@code jq} cannot do, which are failing a build and being written down before the run rather
     * than after it.
     */
    private static List<Report.Expectation> check(final Options options, final Session session) {
        var expectations = new ArrayList<Report.Expectation>();

        if (options.expectNotBlank()) {
            var analysis = session.analyse();

            expectations.add(new Report.Expectation(
                    "not-blank",
                    !analysis.blank(),
                    analysis.blank()
                            ? "the whole picture was colour index " + analysis.dominantColour()
                            : null));
        }

        if (options.expectAudio()) {
            var audio = session.audioStats();

            expectations.add(new Report.Expectation(
                    "audio",
                    audio.peak() > 0,
                    audio.peak() > 0 ? null : "peak was " + audio.peak()));
        }

        if (options.expectMotion() >= 0) {
            var changes = session.frameChanges();

            expectations.add(new Report.Expectation(
                    "motion",
                    changes >= options.expectMotion(),
                    changes >= options.expectMotion() ? null
                            : changes + " frames changed, wanted " + options.expectMotion()));
        }

        return List.copyOf(expectations);
    }

    private static int exitCode(
            final Report.StoppedBecause stoppedBecause,
            final List<Report.Expectation> expectations
    ) {
        // A run that never finished is the more fundamental answer: whatever the expectations say
        // about it, they were asked of a shorter run than the one requested.
        if (stoppedBecause == Report.StoppedBecause.TIMEOUT) {
            return EXIT_TIMEOUT;
        }

        for (var expectation : expectations) {
            if (!expectation.passed()) {
                return EXIT_EXPECTATION;
            }
        }

        return EXIT_OK;
    }

    /**
     * Files the report, prints it, or both.
     * <p>
     * The report goes to standard output and the logging goes to standard error, which is what
     * keeps {@code --report - | jq .} clean without anything having to be switched off.
     */
    private static void publish(final Options options, final String report) throws IOException {
        if (options.reportPath() != null) {
            var parent = options.reportPath().getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(options.reportPath(), report + System.lineSeparator());
        }

        if (options.reportPath() == null || !options.quiet()) {
            System.out.println(report);
        }
    }

}
