package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.cheat.GameGenie;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.cheat.InvalidGameGenieCodeException;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.debug.Disassembler;
import com.github.dimiro1.mynes.state.Movie;
import com.github.dimiro1.mynes.state.MovieException;
import com.github.dimiro1.mynes.state.Rewind;
import com.github.dimiro1.mynes.state.SaveStateException;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The machine, driven a command at a time.
 * <p>
 * One command per line in, one JSON document per line out, which is a shape a reader can take
 * incrementally: a caller can pipe a whole session in at once and still get an answer for each step
 * rather than only at the end. The one-shot mode is the right tool when the question is already
 * known; this is for when finding the question out is the job -- which frame a title screen lands
 * on, whether that address is the one holding the score, what the game does if Start is held.
 * <p>
 * Nothing here ends the session. A command that cannot be read is answered with an error and the
 * next one is taken, because the state built up over a hundred frames is worth more than the typo
 * that follows it.
 */
public final class Repl {
    private static final String HELP = """
            run [N]                    advance N frames, default 1
            run-until-change [MAX]     advance until the picture differs, at most MAX frames
            run-until-still [N] [MAX]  advance until the picture has held for N frames
            step [N]                   advance N instructions, default 1
            disasm [ADDR] [COUNT]      disassemble, from the PC by default
            break ADDR                 stop before the instruction at ADDR
            unbreak ADDR               forget that one
            watch ADDR                 stop after an instruction writes to ADDR
            unwatch ADDR               forget that one
            points [clear]             list every breakpoint and watchpoint, or drop them all
            press BUTTONS [N]          hold BUTTONS for the next N frames
            hold BUTTONS               hold BUTTONS until released
            release                    let go of everything
            reset                      the console Reset button
            screenshot PATH            write a PNG of the picture as it stands
            state                      everything the report knows, for right now
            read ADDR [COUNT]          CPU bus, without side effects
            read-ppu ADDR [COUNT]      PPU bus: pattern tables and nametables
            oam [START] [COUNT]        object attribute memory
            dump WHAT PATH             ram, oam, palette, nametables, prgram or chr
            hack NAME on|off           unlimited-sprites, which --hack also switches on
            genie [CODE]               list the Game Genie codes, or put one in
            ungenie CODE               take one out
            genie clear                take them all out
            save-state PATH            write the whole machine to a file
            load-state PATH            put one back, from this same ROM
            rewind on [FRAMES]         start keeping history, 30 seconds of it by default
            rewind N                   go back N frames, or as far as the history goes
            rewind off                 stop keeping it
            record                     say whether a movie is being recorded, and how long it is
            record start               start writing down what is pressed
            record stop [PATH]         stop, and write it where --record said if PATH is left off
            audio                      peak, RMS and silence since the last audio command
            help                       this
            quit                       stop

            Addresses take 0x, $ or plain decimal. Buttons join with +, as in a+right.""";

    /**
     * How far {@code run-until-change} and {@code run-until-still} look before giving up, when
     * they are not told. Ten seconds of emulated time, which is longer than any title screen takes
     * to do something.
     */
    private static final long DEFAULT_MAX_FRAMES = 600;

    /**
     * How long {@code run-until-still} wants the picture to hold before it calls it settled.
     */
    private static final long DEFAULT_STILL_FRAMES = 30;

    /**
     * How much {@code disasm} shows when it is not told. About a screenful, and enough to see the
     * end of whatever routine the machine stopped in.
     */
    private static final int DEFAULT_DISASM_LINES = 16;

    /**
     * How much history {@code rewind on} keeps when it is not told, in seconds rather than frames
     * because that is the unit the answer is wanted in -- and because the two machines put a
     * different number of frames in a second. The window's default is the same thirty.
     */
    private static final int DEFAULT_REWIND_SECONDS = 30;

    private final Session session;
    private final Options options;
    private final BufferedReader in;
    private final PrintStream out;

    /**
     * Whether replies are spelled as readable text rather than compact JSON. Resolved once by the
     * caller from {@code --format} and whether a person is at the terminal, so nothing here has to
     * decide it again per reply.
     */
    private final boolean text;

    /**
     * Buttons held until released, as opposed to the countdown a press sets up.
     */
    private int held;

    private int pressRemaining;
    private int pressButtons;

    /**
     * The last movie this session wrote and where it went, so the report can name it. Null until
     * {@code record stop} has written one, and never touched by the {@code --record} path, which is
     * finished off by {@link Headless} after the session has ended.
     */
    private @Nullable Movie recordedMovie;

    private @Nullable Path recordedPath;

    public Repl(
            final Session session,
            final Options options,
            final BufferedReader in,
            final PrintStream out,
            final boolean text
    ) {
        this.session = session;
        this.options = options;
        this.in = in;
        this.out = out;
        this.text = text;
    }

    /**
     * The movie {@code record stop} wrote, or null if this session wrote none.
     */
    public @Nullable Movie recordedMovie() {
        return recordedMovie;
    }

    /**
     * Where that went.
     */
    public @Nullable Path recordedPath() {
        return recordedPath;
    }

    /**
     * Takes commands until {@code quit} or the end of the input.
     *
     * @return how many frames the session got through.
     */
    public long run() throws IOException {
        String line;

        while ((line = in.readLine()) != null) {
            var command = line.trim();

            if (command.isEmpty() || command.startsWith("#")) {
                continue;
            }

            var words = command.split("\\s+");

            if (words[0].equals("quit") || words[0].equals("exit")) {
                reply("quit", node -> {
                });
                break;
            }

            try {
                dispatch(words);
            } catch (UsageException e) {
                error(words[0], e.getMessage());
            }
        }

        return session.frame();
    }

    private void dispatch(final String[] words) throws IOException {
        var name = words[0];

        switch (name) {
            case "run" -> run(words.length > 1 ? number(words[1], name) : 1);
            case "run-until-change" -> runUntilChange(
                    words.length > 1 ? number(words[1], name) : DEFAULT_MAX_FRAMES);
            case "run-until-still" -> runUntilStill(
                    words.length > 1 ? number(words[1], name) : DEFAULT_STILL_FRAMES,
                    words.length > 2 ? number(words[2], name) : DEFAULT_MAX_FRAMES);
            case "step" -> stepInstructions(words.length > 1 ? number(words[1], name) : 1);
            case "disasm" -> disasm(words);
            case "break", "unbreak", "watch", "unwatch" -> point(name, words);
            case "points" -> points(words);
            case "press" -> press(words);
            case "hold" -> hold(words);
            case "release" -> release();
            case "reset" -> reset();
            case "screenshot" -> screenshot(words);
            case "state" -> state();
            case "read", "read-ppu" -> read(name, words);
            case "oam" -> oam(words);
            case "dump" -> dump(words);
            case "hack" -> hack(words);
            case "genie", "ungenie" -> genie(name, words);
            case "save-state" -> saveState(words);
            case "load-state" -> loadState(words);
            case "rewind" -> rewind(words);
            case "record" -> record(words);
            case "audio" -> audio();
            case "help" -> reply("help", node -> node.put("commands", HELP));
            default -> error(name, "\"" + name + "\" is not a command. Try help.");
        }
    }

    // ================================================================================== commands

    private void run(final long frames) throws IOException {
        var start = session.frame();
        var changed = false;
        Debugger.Stop stop = null;

        for (var i = 0L; i < frames && stop == null; i++) {
            var frame = step();

            changed |= frame.changed();
            stop = frame.stop();
        }

        var didChange = changed;
        var stopped = stop;

        reply("run", node -> {
            // How many finished, not how many were asked for. A breakpoint part way through one
            // leaves it unfinished, and counting it would put every later frame number out by one.
            node.put("frames", session.frame() - start);
            node.put("changed", didChange);
            describe(node, stopped);
        });
    }

    private void runUntilChange(final long max) throws IOException {
        var start = session.frame();
        var found = false;
        Debugger.Stop stop = null;

        for (var i = 0L; i < max && !found && stop == null; i++) {
            var frame = step();

            found = frame.changed();
            stop = frame.stop();
        }

        var didChange = found;
        var stopped = stop;

        reply("run-until-change", node -> {
            node.put("changed", didChange);
            node.put("framesRun", session.frame() - start);
            describe(node, stopped);
        });
    }

    private void runUntilStill(final long still, final long max) throws IOException {
        var start = session.frame();
        var settled = false;
        Debugger.Stop stop = null;

        for (var i = 0L; i < max && !settled && stop == null; i++) {
            stop = step().stop();
            settled = session.framesSinceLastChange() >= still;
        }

        var didSettle = settled;
        var stopped = stop;

        reply("run-until-still", node -> {
            node.put("still", didSettle);
            node.put("framesRun", session.frame() - start);
            describe(node, stopped);
        });
    }

    /**
     * Advances instructions rather than frames, and says what the machine is standing on afterwards.
     * <p>
     * The disassembly is of the instruction about to run rather than the one that just did, because
     * the next question after a step is almost always "and now what?".
     */
    private void stepInstructions(final long instructions) throws IOException {
        var stepped = session.stepInstructions(instructions);
        var cpuState = session.nes().getCPU().getState();
        var next = Disassembler.at(session.nes().getMemory()::peek, cpuState.pc());

        reply("step", node -> {
            node.put("instructions", stepped.instructions());
            node.put("pc", cpuState.pc());
            node.put("a", cpuState.a());
            node.put("x", cpuState.x());
            node.put("y", cpuState.y());
            node.put("sp", cpuState.sp());
            node.put("p", cpuState.p());
            node.put("cycles", cpuState.cycles());
            line(node.putObject("next"), next);
            describe(node, stepped.stop());
        });
    }

    private void disasm(final String[] words) {
        var address = words.length > 1
                ? (int) number(words[1], "disasm")
                : session.nes().getCPU().getPC();
        var count = words.length > 2 ? (int) number(words[2], "disasm") : DEFAULT_DISASM_LINES;
        var lines = Disassembler.from(session.nes().getMemory()::peek, address, count);

        reply("disasm", node -> {
            node.put("address", address);
            node.put("count", lines.size());

            var array = node.putArray("lines");
            lines.forEach(disassembled -> line(array.addObject(), disassembled));
        });
    }

    /**
     * The four commands that put a point down or pick one up, which differ only in which set they
     * touch and are not worth four methods.
     */
    private void point(final String name, final String[] words) {
        if (words.length < 2) {
            throw new UsageException(name + " wants an address, as in \"" + name + " $C000\".");
        }

        var address = (int) number(words[1], name) & 0xFFFF;
        var debugger = session.debugger();

        switch (name) {
            case "break" -> debugger.addBreakpoint(address);
            case "unbreak" -> debugger.removeBreakpoint(address);
            case "watch" -> debugger.addWatchpoint(address);
            default -> debugger.removeWatchpoint(address);
        }

        reply(name, node -> {
            node.put("address", address);
            putPoints(node);
        });
    }

    private void points(final String[] words) {
        if (words.length > 1) {
            if (!words[1].equals("clear")) {
                throw new UsageException(
                        "points takes \"clear\" or nothing, not \"" + words[1] + "\".");
            }

            session.debugger().clear();
        }

        reply("points", this::putPoints);
    }

    private void press(final String[] words) {
        if (words.length < 2) {
            throw new UsageException("press wants buttons, as in \"press start\".");
        }

        pressButtons = InputSchedule.parseButtonList(words[1]);
        pressRemaining = words.length > 2
                ? (int) number(words[2], "press")
                : options.pressFrames();

        reply("press", node -> {
            node.put("frames", pressRemaining);
            buttons(node, pressButtons);
        });
    }

    private void hold(final String[] words) {
        if (words.length < 2) {
            throw new UsageException("hold wants buttons, as in \"hold right\".");
        }

        held = InputSchedule.parseButtonList(words[1]);

        reply("hold", node -> buttons(node, held));
    }

    private void release() {
        held = 0;
        pressRemaining = 0;
        pressButtons = 0;

        // Answers with the empty list rather than with nothing, so that a reply to release reads
        // the same way as a reply to hold and can be checked the same way.
        reply("release", node -> buttons(node, 0));
    }

    private void reset() {
        session.reset();

        reply("reset", node -> {
        });
    }

    private void screenshot(final String[] words) throws IOException {
        if (words.length < 2) {
            throw new UsageException("screenshot wants somewhere to write it.");
        }

        var path = Path.of(words[1]);
        session.screenshot(path, !options.fullFrame(), options.scale());

        reply("screenshot", node -> node.put("path", path.toString()));
    }

    private void state() {
        var nes = session.nes();
        var cpuState = nes.getCPU().getState();
        var ppu = nes.getPPU();
        var analysis = session.analyse();
        var audioStats = session.audioStats();

        reply("state", node -> {
            // First, because it is what the two numbers below mean: a scanline of 300 is a normal
            // place for the beam to be on one machine and impossible on the other.
            node.put("region", nes.getRegion().id());

            var cpu = node.putObject("cpu");
            cpu.put("a", cpuState.a());
            cpu.put("x", cpuState.x());
            cpu.put("y", cpuState.y());
            cpu.put("sp", cpuState.sp());
            cpu.put("pc", cpuState.pc());
            cpu.put("p", cpuState.p());
            cpu.put("cycles", cpuState.cycles());

            var picture = node.putObject("ppu");
            picture.put("scanline", ppu.getScanline());
            picture.put("dot", ppu.getDot());
            picture.put("renderingEnabled", ppu.isRenderingEnabled());

            var video = node.putObject("video");
            video.put("uniqueColours", analysis.uniqueColours());
            video.put("blank", analysis.blank());
            video.put("frameChanges", session.frameChanges());
            video.put("framesSinceLastChange", session.framesSinceLastChange());

            var audio = node.putObject("audio");
            audio.put("samples", audioStats.samples());
            audio.put("peak", audioStats.peak());
            audio.put("rms", audioStats.rms());

            buttons(node, session.buttons());
        });
    }

    private void read(final String name, final String[] words) {
        if (words.length < 2) {
            throw new UsageException(name + " wants an address.");
        }

        var address = (int) number(words[1], name);
        var count = words.length > 2 ? (int) number(words[2], name) : 1;
        var values = "read-ppu".equals(name)
                ? session.readPPU(address, count)
                : session.readCPU(address, count);

        reply(name, node -> {
            node.put("address", address);
            node.put("count", count);
            node.put("bytes", toHex(values));
        });
    }

    private void oam(final String[] words) {
        var start = words.length > 1 ? (int) number(words[1], "oam") : 0;
        var count = words.length > 2 ? (int) number(words[2], "oam") : 256;
        var values = session.readOAM(start, count);

        reply("oam", node -> {
            node.put("address", start);
            node.put("count", count);
            node.put("bytes", toHex(values));
        });
    }

    private void dump(final String[] words) throws IOException {
        if (words.length < 3) {
            throw new UsageException(
                    "dump wants what to write and where, as in \"dump ram ram.bin\".");
        }

        var what = words[1];
        var path = Path.of(words[2]);
        var bytes = session.dump(what);
        var parent = path.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        Files.write(path, bytes);

        reply("dump", node -> {
            node.put("what", what);
            node.put("path", path.toString());
            node.put("bytes", bytes.length);
        });
    }

    /**
     * Switches one of the things the console does not do on or off, mid-session.
     * <p>
     * Worth having as a command rather than only as a flag because the difference a hack makes is
     * something to look at: run to the frame the sprites flicker on, turn it on, take a screenshot,
     * turn it off, take another. Nothing about the machine changes, so the two pictures are of the
     * same moment.
     */
    private void hack(final String[] words) {
        if (words.length < 3) {
            throw new UsageException(
                    "hack wants a name and on or off, as in \"hack "
                            + Options.UNLIMITED_SPRITES + " on\".");
        }

        var name = words[1].toLowerCase(Locale.ROOT);

        var on = switch (words[2].toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new UsageException(
                    "hack is switched on or off, not \"" + words[2] + "\".");
        };

        switch (name) {
            case Options.UNLIMITED_SPRITES -> session.nes().getPPU().setUnlimitedSprites(on);
            default -> throw new UsageException(
                    "hack does not know \"" + words[1] + "\". It knows "
                            + String.join(", ", Options.HACKS) + ".");
        }

        reply("hack", node -> {
            node.put("hack", name);
            node.put("on", on);
        });
    }

    /**
     * Puts a Game Genie code in or takes one out, mid-session.
     * <p>
     * The shape of {@code break}/{@code unbreak}/{@code points clear} rather than of {@code hack},
     * because a code is a thing put down and picked up rather than a two-position switch on a fixed
     * set of names. And for the same reason {@code hack} is worth having as a command: the difference
     * a code makes is something to look at, so run to the frame that matters, put it in, take a
     * screenshot, take it out, take another -- two pictures of the same moment.
     * <p>
     * {@code clear} cannot be mistaken for a code. There is no C or R in the sixteen letters, and it
     * is five long where a code is six or eight.
     */
    private void genie(final String name, final String[] words) {
        var device = session.genie();

        // Every form of this except "list them" changes what is in the slot, and a movie pinned the
        // codes at the moment it started: a file whose header names one set and whose frames were
        // played against another cannot be replayed and would not say so. Refused rather than
        // silently re-pinned, since which of the two somebody meant is not knowable from here.
        if (session.recording() && changesTheCodes(name, words)) {
            throw new UsageException(
                    "a movie is being recorded, and it pinned the Game Genie codes when it started."
                            + " Stop the recording first, or take the codes out before starting"
                            + " one.");
        }

        if (name.equals("ungenie")) {
            if (words.length < 2) {
                throw new UsageException(
                        "ungenie wants a code to take out, as in \"ungenie SXIOPO\".");
            }

            var code = decode(words[1]);

            if (!device.remove(code)) {
                throw new UsageException(code.text() + " is not one of the codes that are in.");
            }

            reply("ungenie", node -> {
                node.put("code", code.text());
                putCodes(node, device);
            });

            return;
        }

        if (words.length > 1 && words[1].equalsIgnoreCase("clear")) {
            device.clear();
        } else if (words.length > 1) {
            var code = decode(words[1]);
            var replaced = device.add(code);

            reply("genie", node -> {
                node.put("code", code.text());
                node.put("address", code.address());
                node.put("value", code.value());

                if (code.hasCompare()) {
                    node.put("compare", code.compare());
                } else {
                    node.putNull("compare");
                }

                // One address holds one code, the way the cartridge does, so saying which one this
                // pushed out is more use than silently having two of them.
                if (replaced == null) {
                    node.putNull("replaced");
                } else {
                    node.put("replaced", replaced.text());
                }

                putCodes(node, device);
            });

            return;
        }

        reply("genie", node -> putCodes(node, device));
    }

    /**
     * Whether this {@code genie} or {@code ungenie} would change what is in the cartridge slot, as
     * opposed to only listing it.
     */
    private static boolean changesTheCodes(final String name, final String[] words) {
        return name.equals("ungenie") || words.length > 1;
    }

    private static GameGenieCode decode(final String word) {
        try {
            return GameGenieCode.decode(word);
        } catch (InvalidGameGenieCodeException e) {
            throw new UsageException(e.getMessage());
        }
    }

    private static void putCodes(final Json.Object node, final GameGenie device) {
        var codes = node.putArray("codes");

        for (var code : device.codes()) {
            codes.add(code.text());
        }
    }

    /**
     * A bookmark, which is what makes trying two things from the same place cheap: save, try one,
     * load, try the other. The reply carries the frame and the picture hash like every other, so the
     * answer to "where am I now" comes back with it.
     */
    private void saveState(final String[] words) {
        if (words.length < 2) {
            throw new UsageException(
                    "save-state wants somewhere to write it, as in \"save-state before.mn\".");
        }

        var path = Path.of(words[1]);

        // A file that cannot be written is a bad command rather than the end of the session, the same
        // as a misspelled address -- so it takes the UsageException route and gets answered with an
        // error. Only a failure writing the *reply* is allowed to escape.
        try {
            var parent = path.getParent();

            if (parent != null) {
                Files.createDirectories(parent);
            }

            session.saveState(path);
        } catch (IOException | SaveStateException e) {
            throw new UsageException("could not write " + path + ": " + e.getMessage());
        }

        reply("save-state", node -> {
            node.put("path", path.toString());
            node.put("bytes", sizeOf(path));
        });
    }

    private void loadState(final String[] words) {
        if (words.length < 2) {
            throw new UsageException(
                    "load-state wants a file to read, as in \"load-state before.mn\".");
        }

        var path = Path.of(words[1]);

        // Likewise, and doubly so here: the machine is untouched by a state that would not load, so
        // there is a session left to carry on with. A hundred frames of setting something up is worth
        // more than the typo that follows it.
        try {
            session.loadState(path);
        } catch (IOException | SaveStateException e) {
            throw new UsageException("could not load " + path + ": " + e.getMessage());
        }

        reply("load-state", node -> node.put("path", path.toString()));
    }

    /**
     * Starts keeping history, goes back through it, or says how much of it there is.
     * <p>
     * The one command rather than three because the three are one idea, and because the shape reads
     * the way it is used: {@code rewind on}, some frames, {@code rewind 30}. Unlike {@code hack} it
     * is not a two-position switch -- the interesting form is the middle one, which takes a number.
     * <p>
     * Worth having at all because this is where the feature can be checked. A window is somebody
     * holding a key down and a picture that looks about right; here a rewound machine's frame and
     * hash come back on the same line, so "it went back to where it was" is an assertion rather than
     * an impression.
     */
    private void rewind(final String[] words) {
        if (words.length < 2) {
            reply("rewind", this::putRewind);
            return;
        }

        switch (words[1].toLowerCase(Locale.ROOT)) {
            case "on" -> {
                session.armRewind(words.length > 2
                        ? (int) number(words[2], "rewind")
                        : Rewind.framesFor(session.nes().getRegion(), DEFAULT_REWIND_SECONDS));

                reply("rewind", this::putRewind);
            }
            case "off" -> {
                session.disarmRewind();

                reply("rewind", this::putRewind);
            }
            default -> {
                // How far it actually went, not how far it was asked to go. A history that ran out
                // is the ordinary answer to a key held down, and the difference between the two
                // numbers is the whole of what a caller wants to know about it.
                var moved = session.rewind((int) number(words[1], "rewind"));

                reply("rewind", node -> {
                    node.put("framesRewound", moved);
                    putRewind(node);
                });
            }
        }
    }

    /**
     * Starts a movie, stops one, or says whether there is one.
     * <p>
     * The shape of {@code rewind} rather than of {@code hack}, and for the same reason: the three
     * forms are one idea and they read the way they are used -- {@code record start}, some frames,
     * {@code record stop take.mnm}.
     * <p>
     * Worth having as a command rather than only as a flag because this is where the claim can be
     * checked. Record a session that rewinds half way through, play it back, and compare the two
     * save states: a window is somebody's impression that it looked right, and here it is an
     * assertion about bytes.
     */
    private void record(final String[] words) {
        if (words.length < 2) {
            reply("record", this::putRecord);
            return;
        }

        switch (words[1].toLowerCase(Locale.ROOT)) {
            case "start" -> {
                // A movie that carries no state is only honest when there is nothing to carry: a
                // machine that has run is somewhere a file of buttons cannot describe, and --sram-in
                // has filled a battery a movie has no way to hold.
                session.startRecording(session.frame() == 0 && options.sramIn() == null);

                reply("record", this::putRecord);
            }
            case "stop" -> {
                var path = words.length > 2 ? Path.of(words[2]) : options.record();

                // Asked this way round so that a session which never started one is told that,
                // rather than told to name a file for a movie that does not exist. Nothing has been
                // stopped yet either way, so the take survives both refusals.
                if (session.recording() && path == null) {
                    throw new UsageException(
                            "record stop wants somewhere to write it, as in \"record stop"
                                    + " take.mnm\" -- or a --record on the command line.");
                }

                var movie = session.stopRecording();

                // A file that cannot be written is a bad command rather than the end of the
                // session, the same as a misspelled address -- and the take is still in hand, so
                // this is the one refusal here that actually costs something.
                try {
                    movie.write(path);
                } catch (IOException | MovieException e) {
                    throw new UsageException("could not write " + path + ": " + e.getMessage());
                }

                recordedMovie = movie;
                recordedPath = path;

                reply("record", node -> {
                    node.put("path", path.toString());
                    node.put("frames", movie.frameCount());
                    node.put("anchored", movie.anchored());
                    node.put("anchorFrame", movie.anchorFrame());
                    node.put("bytes", sizeOf(path));
                    putRecord(node);
                });
            }
            default -> throw new UsageException(
                    "record takes \"start\", \"stop\" or nothing, not \"" + words[1] + "\".");
        }
    }

    private void putRecord(final Json.Object node) {
        node.put("on", session.recording());

        if (session.recording()) {
            node.put("frames", session.framesRecorded());
            node.put("anchored", session.recordingAnchored());
            node.put("anchorFrame", session.recordingAnchorFrame());
        }
    }

    private void putRewind(final Json.Object node) {
        node.put("on", session.rewinding());

        if (session.rewinding()) {
            node.put("capacity", session.rewindCapacity());
            node.put("rewindable", session.rewindable());
        }
    }

    private static long sizeOf(final Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * What the sound has been doing since this was last asked, which is the form the question
     * usually takes: not "does this cartridge make any noise" but "did that make a noise".
     */
    private void audio() {
        var since = session.audioSinceMark();
        var total = session.audioStats();

        session.markAudio();

        reply("audio", node -> {
            node.put("samples", since.samples());
            node.put("peak", since.peak());
            node.put("rms", since.rms());
            node.put("silentFrames", since.silentFrames());
            node.put("silent", since.peak() == 0.0);
            node.put("totalSamples", total.samples());
            node.put("totalPeak", total.peak());
        });
    }

    // ================================================================================== internals

    /**
     * Advances one frame, having first put down whatever a press has run out of and picked up
     * whatever is being held.
     */
    private Session.Frame step() throws IOException {
        var buttons = held;

        if (pressRemaining > 0) {
            buttons |= pressButtons;
        }

        session.setButtons(buttons);

        var before = session.frame();
        var frame = session.advanceFrame();

        // Only a frame that actually finished uses one up. A run a breakpoint stopped part way
        // through carries on into the same frame next time, and counting it twice would let go of
        // the button before the game had a whole frame to notice it.
        if (pressRemaining > 0 && session.frame() != before) {
            pressRemaining--;
        }

        return frame;
    }

    /**
     * Says why the machine stopped, and says nothing at all when it simply ran out of frames.
     * <p>
     * Absent rather than null, unlike the report's convention, because a stop is a thing that
     * happened rather than a field with no value: a caller watching a stream of these is asking
     * "did anything stop it?", and {@code jq 'select(.stopped)'} is the whole of the answer.
     */
    private void describe(final Json.Object node, final Debugger.Stop stop) {
        if (stop == null) {
            return;
        }

        node.put("stopped", stop.reason().name().toLowerCase(Locale.ROOT));
        node.put("stoppedAt", stop.pc());

        if (stop.address() >= 0) {
            node.put("address", stop.address());
            node.put("value", stop.value());
        }

        if (stop.writtenBy() >= 0) {
            node.put("writtenBy", stop.writtenBy());
        }
    }

    /**
     * Fills a node that has already been put somewhere, rather than making one to hand over.
     * {@link Json.Array} takes objects by creating them, so this is the shape that works both for a
     * line inside a listing and for the single line a step reports.
     */
    private void line(final Json.Object node, final Disassembler.Line disassembled) {
        node.put("address", disassembled.address());
        node.put("bytes", disassembled.hex());
        node.put("text", disassembled.text());
    }

    private void putPoints(final Json.Object node) {
        var debugger = session.debugger();
        var breakpoints = node.putArray("breakpoints");
        var watchpoints = node.putArray("watchpoints");

        debugger.breakpoints().forEach(address -> breakpoints.add(address));
        debugger.watchpoints().forEach(address -> watchpoints.add(address));
    }

    private void buttons(final Json.Object node, final int mask) {
        var array = node.putArray("buttons");
        InputSchedule.describe(mask).forEach(array::add);
    }

    /**
     * Answers a command.
     * <p>
     * Every reply carries the frame the machine is on and a hash of what is on screen, whatever the
     * command was. Without them a caller reading a stream of these would have to keep count itself,
     * and the hash is what makes "did that do anything?" answerable without a screenshot.
     */
    private void reply(final String command, final Body body) {
        var node = Json.object();

        node.put("ok", true);
        node.put("command", command);
        body.write(node);
        Report.putStateOf(node, session);

        out.println(text ? Json.text(node) : Json.compact(node));
    }

    private void error(final String command, final String message) {
        var node = Json.object();

        node.put("ok", false);
        node.put("command", command);
        node.put("error", message);
        node.put("frame", session.frame());

        out.println(text ? Json.text(node) : Json.compact(node));
    }

    private long number(final String text, final String command) {
        var trimmed = text.trim();

        try {
            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Long.parseLong(trimmed.substring(2), 16);
            }

            if (trimmed.startsWith("$")) {
                return Long.parseLong(trimmed.substring(1), 16);
            }

            return Long.parseLong(trimmed);
        } catch (NumberFormatException e) {
            throw new UsageException(command + ": \"" + text + "\" is not a number.");
        }
    }

    private static String toHex(final int[] values) {
        var hex = new StringBuilder(values.length * 2);

        for (var value : values) {
            hex.append(String.format("%02x", value & 0xFF));
        }

        return hex.toString();
    }

    /**
     * The part of a reply that is particular to the command that produced it.
     */
    @FunctionalInterface
    private interface Body {
        void write(Json.Object node);
    }
}
