package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.debug.Disassembler;
import com.github.dimiro1.mynes.state.SaveStateException;

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
            save-state PATH            write the whole machine to a file
            load-state PATH            put one back, from this same ROM
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

    private final Session session;
    private final Options options;
    private final BufferedReader in;
    private final PrintStream out;

    /**
     * Buttons held until released, as opposed to the countdown a press sets up.
     */
    private int held;

    private int pressRemaining;
    private int pressButtons;

    public Repl(
            final Session session,
            final Options options,
            final BufferedReader in,
            final PrintStream out
    ) {
        this.session = session;
        this.options = options;
        this.in = in;
        this.out = out;
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
            case "save-state" -> saveState(words);
            case "load-state" -> loadState(words);
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
            node.set("next", line(next));
            describe(node, stepped.stop());
        });
    }

    private void disasm(final String[] words) throws IOException {
        var address = words.length > 1
                ? (int) number(words[1], "disasm")
                : session.nes().getCPU().getPC();
        var count = words.length > 2 ? (int) number(words[2], "disasm") : DEFAULT_DISASM_LINES;
        var lines = Disassembler.from(session.nes().getMemory()::peek, address, count);

        reply("disasm", node -> {
            node.put("address", address);
            node.put("count", lines.size());

            var array = node.putArray("lines");
            lines.forEach(line -> array.add(line(line)));
        });
    }

    /**
     * The four commands that put a point down or pick one up, which differ only in which set they
     * touch and are not worth four methods.
     */
    private void point(final String name, final String[] words) throws IOException {
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

    private void points(final String[] words) throws IOException {
        if (words.length > 1) {
            if (!words[1].equals("clear")) {
                throw new UsageException(
                        "points takes \"clear\" or nothing, not \"" + words[1] + "\".");
            }

            session.debugger().clear();
        }

        reply("points", this::putPoints);
    }

    private void press(final String[] words) throws IOException {
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

    private void hold(final String[] words) throws IOException {
        if (words.length < 2) {
            throw new UsageException("hold wants buttons, as in \"hold right\".");
        }

        held = InputSchedule.parseButtonList(words[1]);

        reply("hold", node -> buttons(node, held));
    }

    private void release() throws IOException {
        held = 0;
        pressRemaining = 0;
        pressButtons = 0;

        // Answers with the empty list rather than with nothing, so that a reply to release reads
        // the same way as a reply to hold and can be checked the same way.
        reply("release", node -> buttons(node, 0));
    }

    private void reset() throws IOException {
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

    private void state() throws IOException {
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

    private void read(final String name, final String[] words) throws IOException {
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

    private void oam(final String[] words) throws IOException {
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
     * A bookmark, which is what makes trying two things from the same place cheap: save, try one,
     * load, try the other. The reply carries the frame and the picture hash like every other, so the
     * answer to "where am I now" comes back with it.
     */
    private void saveState(final String[] words) throws IOException {
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

    private void loadState(final String[] words) throws IOException {
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
    private void audio() throws IOException {
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
    private void describe(final ObjectNode node, final Debugger.Stop stop) {
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

    private ObjectNode line(final Disassembler.Line disassembled) {
        var node = Json.object();

        node.put("address", disassembled.address());
        node.put("bytes", disassembled.hex());
        node.put("text", disassembled.text());

        return node;
    }

    private void putPoints(final ObjectNode node) {
        var debugger = session.debugger();
        var breakpoints = node.putArray("breakpoints");
        var watchpoints = node.putArray("watchpoints");

        debugger.breakpoints().forEach(breakpoints::add);
        debugger.watchpoints().forEach(watchpoints::add);
    }

    private void buttons(final ObjectNode node, final int mask) {
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

        out.println(Json.compact(node));
    }

    private void error(final String command, final String message) {
        var node = Json.object();

        node.put("ok", false);
        node.put("command", command);
        node.put("error", message);
        node.put("frame", session.frame());

        out.println(Json.compact(node));
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
        void write(ObjectNode node);
    }
}
