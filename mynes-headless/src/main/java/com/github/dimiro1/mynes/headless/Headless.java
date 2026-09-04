package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.archive.Archive;
import com.github.dimiro1.mynes.archive.InvalidArchiveException;
import com.github.dimiro1.mynes.patch.IPSPatch;
import com.github.dimiro1.mynes.patch.InvalidPatchException;
import com.github.dimiro1.mynes.state.BatteryRAM;
import com.github.dimiro1.mynes.state.Movie;
import com.github.dimiro1.mynes.state.MovieException;
import com.github.dimiro1.mynes.state.SaveStateException;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.VideoFilter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

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
        } catch (UsageException | SaveStateException | MovieException e) {
            // A save state that will not load is a mistake on the command line in every sense that
            // matters to a script: the file named was the wrong one. A movie that will not play is
            // the same mistake. No sixth exit code for either.
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
        final byte[] file;

        try {
            file = Files.readAllBytes(options.rom());
        } catch (IOException e) {
            System.err.println(options.rom() + " could not be read: " + e.getMessage());
            return EXIT_ROM;
        }

        var unzipped = unzip(options, file);

        if (unzipped == null) {
            return EXIT_ROM;
        }

        var image = unzipped.image();

        final Patched patched;

        try {
            patched = patch(options, image);
        } catch (IOException e) {
            System.err.println("a patch could not be read: " + e.getMessage());
            return EXIT_ROM;
        } catch (InvalidPatchException e) {
            System.err.println(e.getMessage());
            return EXIT_ROM;
        }

        final Cart cart;

        try {
            cart = Cart.load(patched.image(), options.rom().toString());
        } catch (RuntimeException e) {
            // Everything Cart.load throws is unchecked, and a file that is not a cartridge can
            // fail in several ways -- a bad magic number, a mapper nobody has written, a truncated
            // image that runs the buffer out. They are all the same answer to the caller.
            System.err.println(options.rom() + " is not a cartridge this can run: " + e);
            return EXIT_ROM;
        }

        if (!patched.applied().isEmpty()) {
            // The digest is of the patched image, so it names what ran rather than the file on disk.
            // Worth saying out loud, since it is the number somebody comparing two runs reads first.
            logger.log(Level.INFO, "running a patched image, sha256 " + cart.sha256());
        }

        var region = options.regionFor(cart);
        var palette = options.paletteFor(region);

        // Here rather than while the command line is being read, because until the cartridge has
        // been looked at nobody knows which machine this is. Refused rather than quietly dropped
        // back to the palette, for the reason a misspelled --palette is: the picture would not be
        // the one that was asked for.
        if (options.filter() == VideoFilter.NTSC && region == Region.PAL) {
            System.err.println("--filter ntsc decodes the 2C02's signal, and this is a "
                    + region.label() + " machine. The 2C07 draws ten samples to a pixel against"
                    + " eight and alternates its burst phase every line, so it needs a decoder of"
                    + " its own rather than this one. Leave --filter off, or --region ntsc.");
            return EXIT_USAGE;
        }

        // Before the machine exists, because a movie is checked from top to bottom without one --
        // so a file that is not a movie, or is damaged, or is from a later build, stops the run
        // rather than half-playing it. Which cartridge it belongs to is Session.beginReplay's
        // question, and is asked below.
        var movie = options.play() == null ? null : Movie.read(options.play());

        // How long a movie is cannot be known while the command line is being read, so this is
        // where --frames finally means something. Naming one explicitly still wins: running on past
        // the end with nothing held is how to see what the game does when the player stops playing.
        var frames = movie != null && !options.framesSet() ? movie.frameCount() : options.frames();

        if (cart.timing() == Cart.Timing.DENDY && options.region() == null) {
            logger.log(Level.WARNING, options.rom().getFileName()
                    + " says Dendy, which is not modelled; running it as PAL");
        }

        logger.log(Level.INFO, "running " + options.rom().getFileName()
                + ", mapper " + cart.mapperNumber()
                + (cart.submapper() == 0 ? "" : "." + cart.submapper())
                + ", " + region.label()
                + ", " + frames + " frames");

        Files.createDirectories(options.outDir());

        var startedAt = Instant.now();
        var startedNanos = System.nanoTime();

        // No AudioOutput: opening a sound card would make the run depend on the computer it ran on,
        // and there is nobody here to listen to it anyway. The samples are counted, and written to
        // a file if one was asked for.
        try (var wav = options.audio() ? new WavWriter(options.wavPath()) : null) {
            var session = new Session(
                    new NES(cart, region),
                    palette.colours(),
                    options.filter(),
                    options.strength(),
                    options.warp(),
                    wav);

            // Before either of the two below it, because a hack is not machine state: a save state
            // carries none of these, so switching one on afterwards would leave it depending on
            // whether the run started from power on.
            session.nes().getPPU().setUnlimitedSprites(
                    options.hacks().contains(Options.UNLIMITED_SPRITES));

            // Not machine state either, and for the same reason as the layer switches rather than as
            // the hacks: this is whoever is listening rather than anything the chip holds. Which is
            // also why it survives a --load-state and rides freely with --play -- a replay does not
            // depend on which voices anybody could hear.
            for (var channel : options.mute()) {
                session.nes().getAPU().setChannelMuted(channel, true);
            }

            if (!options.mute().isEmpty()) {
                logger.log(Level.INFO, "muted " + options.mute().size()
                        + " of the APU's five voices; the machine is unchanged, so audio.muted"
                        + " rather than the picture is what tells this run from a plain one");
            }

            // The other hack, and the one that has to come off the movie when there is one: it
            // changes how much of its work the game gets through in a frame, so a replay at another
            // setting is a replay of a different game. --play and --hack overclock refuse each
            // other, so these two are never both asking for something.
            var overclock = movie != null ? movie.overclock() : options.overclock();

            session.nes().getPPU().setOverclock(overclock);

            if (!overclock.isNone()) {
                logger.log(Level.INFO, "running with " + overclock
                        + "; the game gets more time a frame, so run.hacks rather than the picture"
                        + " is what tells this run from a plain one");
            }

            // And a Game Genie is not machine state either, for the same reason and one more: the
            // cartridge it is plugged into is untouched, so a state taken with codes in has nothing
            // in it to say so.
            //
            // A replay takes them from the movie rather than from the command line, which is the
            // whole reason a movie carries them: the cartridge a code was played against is byte
            // for byte the cartridge it was not, so nothing else in the file could say so. --genie
            // and --play refuse each other, so these two are never both non-empty.
            var codes = movie != null ? movie.genie() : options.genie();

            for (var code : codes) {
                var replaced = session.genie().add(code);

                if (replaced != null) {
                    logger.log(Level.WARNING, code + " replaces " + replaced
                            + ", since one address holds one code");
                }
            }

            if (!codes.isEmpty()) {
                logger.log(Level.INFO, "put " + codes.size() + " Game Genie codes in;"
                        + " the cartridge is unchanged, so run.genie rather than cart.sha256 is"
                        + " what tells this run from a plain one");
            }

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

            // The other way of starting somewhere, and refused alongside --load-state rather than
            // ordered against it: a movie already says where it begins.
            if (movie != null) {
                session.beginReplay(movie);
                logger.log(Level.INFO, "playing " + options.play() + ", "
                        + movie.frameCount() + " frames"
                        + (movie.anchored() ? " from a state at frame " + movie.anchorFrame()
                        : " from power on"));
            }

            // Last, so the first frame it writes down is the first frame that runs. A movie that
            // carries no state is only honest when there is nothing to carry: --load-state has put
            // the machine somewhere, and --sram-in has filled a battery a movie has no way to hold.
            if (options.record() != null) {
                session.startRecording(options.loadState() == null && options.sramIn() == null);
            }

            var outcome = options.interactive()
                    ? interactive(options, session)
                    : oneShot(options, session, frames, movie);

            // Read before the movie is written, so what the report calls the run is the run and not
            // the few milliseconds of filing that follow it.
            var wallClockMillis = (System.nanoTime() - startedNanos) / 1_000_000;

            var recorded = outcome.recorded();
            var recordedTo = outcome.recordedTo();

            if (session.recording()) {
                // Still running when the session ended, which is the ordinary case for --record and
                // the forgetful one for a REPL that never said "record stop".
                recorded = session.stopRecording();

                if (options.record() != null) {
                    recorded.write(options.record());
                    recordedTo = options.record();

                    logger.log(Level.INFO, "wrote a " + recorded.frameCount()
                            + " frame movie to " + options.record());
                } else {
                    logger.log(Level.WARNING, "a recording of " + recorded.frameCount()
                            + " frames was still running and nowhere was named to write it to,"
                            + " so it was dropped");
                    recorded = null;
                }
            }

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
                            unzipped.entry(),
                            patched.applied(),
                            outcome.screenshots(),
                            dumps,
                            expectations,
                            recorded,
                            recordedTo,
                            movie,
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
     *
     * @param recorded   a movie the session itself wrote, which only an interactive one can do --
     *                   {@code record stop PATH} is a command. Null otherwise, including for the
     *                   ordinary {@code --record} case, which is finished off after the run.
     * @param recordedTo where that went.
     */
    private record Outcome(
            long frames,
            Report.StoppedBecause stoppedBecause,
            List<Long> screenshots,
            Movie recorded,
            Path recordedTo) {
    }

    /**
     * The extension a cartridge inside a zip goes by. The only one: this runs iNES and NES 2.0
     * images, and a name it does not recognise is better refused with a list of what was in there
     * than handed to {@link Cart#load} to fail as "not a cartridge".
     */
    private static final String ROM_EXTENSION = "nes";

    /**
     * The cartridge image, and the name it had inside the zip it came out of.
     *
     * @param entry null when the ROM was a plain file, which is what the report prints for it.
     */
    private record Unzipped(byte[] image, String entry) {
    }

    /**
     * Takes the cartridge out of the zip, when the ROM is one.
     * <p>
     * Decided by what is in the file rather than by what it is called, so a cartridge saved as
     * {@code game.zip.nes} still opens and a zip renamed on the way through a mail server still
     * does. Nothing is unpacked to disk: the bytes go straight on to the patcher, which is why
     * {@code --patch} needs to know nothing about any of this -- an offset in a patch is counted
     * from the front of the cartridge either way.
     *
     * @return what to run, or null when it has already said on standard error why there is nothing
     *         to run. Every one of those is the same answer to a script -- the file named by
     *         {@code --rom} did not produce a cartridge -- so they all cost exit 5.
     */
    private static Unzipped unzip(final Options options, final byte[] file) {
        if (!Archive.looksLikeOne(file)) {
            if (options.entry() != null) {
                System.err.println("--entry names a file inside a zip, and " + options.rom()
                        + " is not one.");
                return null;
            }

            return new Unzipped(file, null);
        }

        final Archive archive;

        try {
            archive = Archive.open(file, options.rom().toString());
        } catch (InvalidArchiveException e) {
            System.err.println(e.getMessage());
            return null;
        }

        if (options.entry() != null) {
            for (var candidate : archive.files()) {
                if (candidate.name().equalsIgnoreCase(options.entry())
                        || candidate.fileName().equalsIgnoreCase(options.entry())) {
                    logger.log(Level.INFO, "running " + candidate.name() + " out of "
                            + options.rom().getFileName());

                    return new Unzipped(candidate.bytes(), candidate.name());
                }
            }

            // Everything rather than only the cartridges, since somebody who named one by hand has
            // most likely mistyped it and wants to see what is actually in there.
            System.err.println(options.rom() + " holds nothing called " + options.entry()
                    + ". It holds " + namesOf(archive.files()) + ".");
            return null;
        }

        var cartridges = archive.endingIn(ROM_EXTENSION);

        if (cartridges.isEmpty()) {
            System.err.println(options.rom() + " holds nothing named like a cartridge. It holds "
                    + namesOf(archive.files()) + ".");
            return null;
        }

        if (cartridges.size() > 1) {
            // Refused rather than guessed at. The first entry in a zip is whichever the packer
            // happened to write first, so picking it would run a different game from the one
            // somebody meant without anything in the report saying which -- and a run nobody can
            // identify afterwards is worse than a run that did not start.
            System.err.println(options.rom() + " holds " + cartridges.size()
                    + " cartridges, so --entry has to say which: " + namesOf(cartridges) + ".");
            return null;
        }

        var only = cartridges.getFirst();

        logger.log(Level.INFO, "running " + only.name() + " out of " + options.rom().getFileName());

        return new Unzipped(only.bytes(), only.name());
    }

    /**
     * The names, for a message that is meant to be read rather than parsed.
     */
    private static String namesOf(final List<Archive.Entry> entries) {
        return entries.isEmpty() ? "nothing at all"
                : entries.stream().map(Archive.Entry::name).collect(Collectors.joining(", "));
    }

    /**
     * A ROM image with whatever {@code --patch} asked for already in it, and what to say about how
     * it got that way.
     */
    private record Patched(byte[] image, List<Report.Patch> applied) {
    }

    /**
     * Applies the patches, in the order they were named.
     * <p>
     * Before the cartridge is parsed rather than after, because a patch may change the header: one
     * that adds a bank moves the mapper number, the PRG count and everything downstream of them. The
     * file on disk is never opened for writing -- the whole point of patching here is that the ROM
     * somebody owns stays the ROM they own, and a run leaves nothing behind to clean up.
     */
    private static Patched patch(final Options options, final byte[] image) throws IOException {
        var patched = image;
        var applied = new ArrayList<Report.Patch>();

        for (var path : options.patches()) {
            var patch = IPSPatch.read(Files.readAllBytes(path), path.toString());

            patched = patch.applyTo(patched);
            applied.add(new Report.Patch(path, patch.records(), patch.bytes()));

            logger.log(Level.INFO, "applied " + patch.records() + " records from "
                    + path.getFileName() + ", leaving " + patched.length + " bytes");
        }

        return new Patched(patched, List.copyOf(applied));
    }

    /**
     * Plays the schedule, or the movie.
     *
     * @param frames how many to run, which is the movie's own length when there is one and nobody
     *               named a number.
     * @param movie  the movie to play, or null to walk {@code --input} and {@code --reset-at}.
     */
    private static Outcome oneShot(
            final Options options, final Session session, final long frames, final Movie movie)
            throws IOException {
        var resets = new HashSet<>(options.resetAt());
        var screenshots = new ArrayList<Long>();
        var deadline = System.nanoTime() + options.timeout().toNanos();
        var stoppedBecause = Report.StoppedBecause.FRAMES;

        for (var frame = 1L; frame <= frames; frame++) {
            if (movie != null) {
                // Counted from the movie's own start rather than from the machine's, which are the
                // same number only for a movie that begins at power on. Reset first, then the
                // buttons, then the frame: the order a recorder wrote them down in.
                if (movie.resetsAt(frame - 1)) {
                    session.reset();
                }

                session.setButtons(movie.buttonsAt(frame - 1));
            } else {
                if (resets.contains(frame)) {
                    session.reset();
                }

                // Set before the frame is emulated rather than after, so that a one frame press is
                // held for the whole of the frame a game might read the pad anywhere in.
                session.setButtons(options.input().buttonsAt(frame));
            }

            session.advanceFrame();

            if (options.wantsScreenshotAt(frame)) {
                shoot(options, session, frame);
                screenshots.add(frame);
            }

            if (System.nanoTime() - deadline >= 0) {
                stoppedBecause = Report.StoppedBecause.TIMEOUT;
                logger.log(Level.WARNING, "timed out at frame " + frame + " of " + frames);
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

        return new Outcome(
                session.frame(), stoppedBecause, List.copyOf(screenshots), null, null);
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
            var frames = repl.run();

            return new Outcome(
                    frames,
                    Report.StoppedBecause.QUIT,
                    List.of(),
                    repl.recordedMovie(),
                    repl.recordedPath());
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
