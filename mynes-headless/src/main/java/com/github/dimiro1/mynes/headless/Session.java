package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.cheat.GameGenie;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.state.Movie;
import com.github.dimiro1.mynes.state.MovieRecorder;
import com.github.dimiro1.mynes.state.Rewind;
import com.github.dimiro1.mynes.state.SaveState;
import com.github.dimiro1.mynes.video.FrameAnalysis;
import com.github.dimiro1.mynes.video.FrameRenderer;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.NTSCFilter;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * A machine being run by hand, with somebody taking notes.
 * <p>
 * This is what the two headless modes share. The one-shot run walks a schedule over it and the
 * interactive mode takes commands against it, but neither of them knows how to clock a NES or what
 * a frame hash is, and this does not know which of the two is driving.
 * <p>
 * The frame loop is deliberately the same shape as the one in the window's {@code EmulatorRunner},
 * down to using the PPU's frame counter as the only frame-complete signal. What is missing from it
 * is the whole point: no pacing, because there is no display to keep in step with; no
 * {@code AudioOutput}, because opening a sound card would make the run depend on the computer it
 * ran on; and no {@code ScreenComponent}, because nobody is watching.
 * <p>
 * Named rather than linked, those three: they live in {@code mynes-desktop}, which this module does
 * not depend on and cannot resolve a link into.
 */
public final class Session {
    /**
     * Where the APU's samples land on their way to being counted, and to the WAV file if one was
     * asked for. A frame is about 735 of them.
     */
    private static final int AUDIO_BUFFER_SAMPLES = 4096;

    /**
     * What one frame looked like.
     *
     * @param number  frames since power on, counting the one just finished.
     * @param hash    a hash of the visible picture, {@link FrameAnalysis#hash(int[])}.
     * @param changed whether that picture differs from the frame before it.
     * @param stop    why the machine stopped part way through, or null if the frame simply
     *                finished. A frame that was cut short is not counted or hashed: there is no
     *                finished picture to hash.
     */
    public record Frame(long number, long hash, boolean changed, Debugger.Stop stop) {
    }

    /**
     * What a run of instructions did.
     *
     * @param instructions how many actually ran, which is fewer than were asked for when something
     *                     stopped it.
     * @param stop         why it stopped, or null if it simply ran out of instructions.
     */
    public record Stepped(long instructions, Debugger.Stop stop) {
    }

    /**
     * What the sound has been doing.
     *
     * @param samples      how many samples the APU has produced.
     * @param peak         the loudest of them, 0 to 1.
     * @param rms          the root mean square of them, 0 to 1, which is closer to how loud it
     *                     actually sounded.
     * @param silentFrames how many frames produced nothing but silence.
     */
    public record AudioStats(long samples, double peak, double rms, long silentFrames) {
    }

    private final NES nes;
    private final int[] palette;
    private final WavWriter wav;

    /**
     * How a screenshot is coloured. Mutable, because the whole use for it here is taking the same
     * frame twice and diffing the two pictures, which wants one machine rather than two runs.
     */
    private VideoFilter filter;

    /**
     * How hard whichever filter is on is applied. Kept here rather than only on the decoder, so
     * that a session that has not built one yet still remembers what it was told.
     */
    private FilterStrength strength;

    /**
     * Whether the tube's glass is curved. Mutable for the reason the filter is.
     */
    private boolean warp;

    /**
     * The composite decoder, built the first time one is asked for and kept after that: it carries
     * a couple of scratch buffers, and a session that never asks should not pay for them.
     */
    private NTSCFilter ntsc;

    /**
     * Where breakpoints and watchpoints live. Constructed here rather than passed in because a
     * session is the only thing that can drive one: it owns the loop that has to run an instruction
     * at a time for a breakpoint to mean anything.
     */
    private final Debugger debugger = new Debugger();

    /**
     * The Game Genie in the cartridge slot, which starts out holding no codes and so costing the
     * machine nothing. Beside the debugger rather than inside the console for the same reason it is:
     * a code belongs to whoever is playing, and a save state carries none of them.
     */
    private final GameGenie genie = new GameGenie();

    /**
     * The last few seconds of the machine, once somebody has asked for them, and null until then.
     * <p>
     * Null at rest rather than an empty ring, the way {@code MMU.genie} is: a one-shot run captures
     * nothing and pays one null check a frame for the privilege, which is what keeps a headless run
     * exactly as fast as it was before any of this existed. Two to three milliseconds a frame is
     * cheap next to a game and ruinous next to a benchmark.
     */
    private @Nullable Rewind rewind;

    /**
     * The movie being written down, once somebody has asked for one, and null until then.
     * <p>
     * Beside {@link #rewind} rather than inside the console for the reason that one is: a machine
     * does not know that anybody is writing down what it does, and a log hanging off a chip would be
     * walked into and shredded by {@code SaveStateCompletenessTests}.
     */
    private @Nullable MovieRecorder recorder;

    /**
     * How many frames this session has gone back over its whole life, which is what the report
     * carries. Cumulative and never reset: the question it answers is whether the run is comparable
     * with another one at all, and a run that rewound and played the same frames again is not.
     */
    private long framesRewound;

    private final short[] samples = new short[AUDIO_BUFFER_SAMPLES];

    /**
     * The sound of the whole run, which is what the report describes.
     */
    private final Accumulator total = new Accumulator();

    /**
     * The sound since somebody last asked, which is what the interactive mode's audio command
     * describes. Two of them rather than one, because "was there any sound at all?" and "did
     * pressing Start make a noise?" are different questions and a run wants both answered.
     */
    private final Accumulator window = new Accumulator();

    private long previousHash;
    private long frameChanges;
    private long lastChangeFrame;

    private int buttons;

    /**
     * @param nes      the machine, already built from a cartridge.
     * @param palette  512 packed ARGB entries, which is what a screenshot is drawn with.
     * @param filter   how to draw a screenshot: through the palette, by decoding the signal, or
     *                 through the palette and onto a tube.
     * @param strength how hard that filter is applied, and nothing at all when neither is.
     * @param warp     whether the tube's glass is curved, and nothing at all unless it is drawing.
     * @param wav      where to write the sound, or null to only count it.
     */
    public Session(
            final NES nes,
            final int[] palette,
            final VideoFilter filter,
            final FilterStrength strength,
            final boolean warp,
            final WavWriter wav) {
        this.nes = nes;
        this.palette = palette;
        this.filter = filter;
        this.strength = strength;
        this.warp = warp;
        this.wav = wav;
        this.previousHash = FrameAnalysis.hash(nes.getPPU().getFrameBuffer());

        debugger.attach(nes);
        genie.attach(nes);
    }

    public NES nes() {
        return nes;
    }

    /**
     * How screenshots are being coloured.
     */
    public VideoFilter filter() {
        return filter;
    }

    public void setFilter(final VideoFilter filter) {
        this.filter = filter;
    }

    /**
     * How hard the filter is being applied. Answered even while the bare palette is drawing, since
     * it is a preference about the filters rather than a fact about the picture.
     */
    public FilterStrength strength() {
        return strength;
    }

    public void setStrength(final FilterStrength strength) {
        this.strength = strength;

        if (ntsc != null) {
            ntsc.setStrength(strength);
        }
    }

    /**
     * Whether the tube's glass is curved. Answered whatever is drawing, for the reason the strength
     * is.
     */
    public boolean warp() {
        return warp;
    }

    public void setWarp(final boolean warp) {
        this.warp = warp;
    }

    private NTSCFilter ntsc() {
        if (ntsc == null) {
            ntsc = new NTSCFilter(strength);
        }

        return ntsc;
    }

    public Debugger debugger() {
        return debugger;
    }

    public GameGenie genie() {
        return genie;
    }

    public long frame() {
        return nes.getPPU().getFrame();
    }

    public int buttons() {
        return buttons;
    }

    /**
     * Holds these buttons down from the next frame until told otherwise.
     */
    public void setButtons(final int mask) {
        buttons = mask;
        nes.getController1().setButtons(mask);
    }

    /**
     * The console's Reset button.
     * <p>
     * The one funnel for both {@code --reset-at} and the REPL's {@code reset}, which is what lets
     * the recorder be told here rather than at each of them. Told <em>before</em> the machine is, so
     * the index it writes down is the frame the reset will be seen in rather than the one before it.
     */
    public void reset() {
        if (recorder != null) {
            recorder.reset();
        }

        nes.reset();
    }

    /**
     * Runs the machine until the PPU finishes a frame, then writes down what happened.
     * <p>
     * One tick is three dots, so this can overshoot the frame boundary by up to two of them -- at
     * most the first pixel of the next frame arrives early, on scanline 0, which is under the
     * overscan crop the hash is taken through.
     */
    public Frame advanceFrame() throws IOException {
        var ppu = nes.getPPU();
        var completed = ppu.getFrame();

        // Asked once a frame rather than once an instruction, which is the whole of why a session
        // with nothing to look for runs exactly as fast as it did before any of this existed.
        if (debugger.isArmed()) {
            return watchedFrame(completed);
        }

        do {
            nes.tick();
        } while (ppu.getFrame() == completed);

        return endOfFrame(null);
    }

    /**
     * The same frame, clocked an instruction at a time so that a breakpoint can stop part way
     * through one.
     * <p>
     * {@link NES#step()} runs to the next instruction boundary rather than to the next dot, so the
     * end of a frame is noticed up to one instruction late -- seven cycles usually, and around five
     * hundred when the step swallows an OAM DMA transfer. That is a few scanlines of the next frame
     * drawn into the buffer before it is hashed, and all of them are inside the eight
     * {@link com.github.dimiro1.mynes.video.FrameRenderer#OVERSCAN_TOP} takes off the top.
     */
    private Frame watchedFrame(final long completed) throws IOException {
        var ppu = nes.getPPU();
        var cpu = nes.getCPU();
        Debugger.Stop stop = null;

        while (ppu.getFrame() == completed && stop == null) {
            var wasPC = cpu.getPC();

            nes.step();

            stop = debugger.afterInstruction(cpu.getPC(), wasPC);
        }

        if (ppu.getFrame() == completed) {
            // Stopped part way through. Nothing is counted and nothing is hashed, because there is
            // no finished picture yet -- and the next call carries on with the same frame.
            return new Frame(completed, previousHash, false, stop);
        }

        return endOfFrame(stop != null ? stop : debugger.afterFrame(cpu.getPC()));
    }

    /**
     * Runs instructions rather than frames, stopping early if the debugger says to.
     * <p>
     * Frames still finish underneath -- the sound is collected and the picture counted whenever the
     * PPU crosses a boundary -- because the APU's ring holds only a few frames of samples and a long
     * step that never drained it would lose the end of them.
     */
    public Stepped stepInstructions(final long count) throws IOException {
        var ppu = nes.getPPU();
        var cpu = nes.getCPU();
        Debugger.Stop stop = null;
        var ran = 0L;

        while (ran < count && stop == null) {
            var completed = ppu.getFrame();
            var wasPC = cpu.getPC();

            nes.step();
            ran++;

            if (ppu.getFrame() != completed) {
                endOfFrame(null);
            }

            stop = debugger.afterInstruction(cpu.getPC(), wasPC);
        }

        return new Stepped(ran, stop);
    }

    /**
     * The bookkeeping a finished frame owes: its sound collected, its picture hashed, and whether
     * it differs from the one before it written down.
     */
    private Frame endOfFrame(final Debugger.Stop stop) throws IOException {
        var ppu = nes.getPPU();

        collectAudio();

        // The one place a frame is known to have finished, which is why the ring is fed from here
        // rather than from the three loops above: a frame that ends inside stepInstructions is as
        // much a frame as one advanceFrame ran, and history that skipped it would count wrong.
        if (rewind != null) {
            rewind.capture(nes);
        }

        // Here for the same reason, and with one more of its own: the buttons field is the mask in
        // force whether the frame finished inside advanceFrame or inside stepInstructions, so a
        // frame somebody stepped their way through is recorded with exactly what was held for it.
        if (recorder != null) {
            recorder.frame(buttons);
        }

        var hash = FrameAnalysis.hash(ppu.getFrameBuffer());
        var changed = hash != previousHash;
        previousHash = hash;

        if (changed) {
            frameChanges++;
            lastChangeFrame = ppu.getFrame();
        }

        return new Frame(ppu.getFrame(), hash, changed, stop);
    }

    /**
     * How many frames differed from the frame before them.
     * <p>
     * This is the signal that answers "is anything happening at all?", and it is the one that
     * catches a game still sitting on its title screen. A program counter that has stopped moving
     * would not: sampled at a frame boundary, almost every game is idling in the same one
     * instruction loop waiting for its next interrupt.
     */
    public long frameChanges() {
        return frameChanges;
    }

    /**
     * How long the picture has been standing still.
     */
    public long framesSinceLastChange() {
        return frame() - lastChangeFrame;
    }

    /**
     * The sound of the whole run so far. Never reset, so a report describes everything that
     * happened whatever anybody asked along the way.
     */
    public AudioStats audioStats() {
        return total.snapshot();
    }

    /**
     * The sound since {@link #markAudio()} was last called, or since power on.
     */
    public AudioStats audioSinceMark() {
        return window.snapshot();
    }

    /**
     * Starts the shorter of the two readings again from here.
     */
    public void markAudio() {
        window.reset();
    }

    /**
     * Looks at the picture as it stands.
     */
    public FrameAnalysis analyse() {
        return FrameAnalysis.of(nes.getPPU().getFrameBuffer());
    }

    /**
     * Writes the picture as it stands to a PNG.
     *
     * @param path         where to write it. Its directory is created if it is not there.
     * @param cropOverscan whether to hide the scanlines a television would.
     * @param scale        how many times to magnify.
     */
    public void screenshot(final Path path, final boolean cropOverscan, final int scale)
            throws IOException {
        var ppu = nes.getPPU();
        var image = switch (filter) {
            case NTSC -> FrameRenderer.render(
                    ppu.getFrameBuffer(), ntsc(), ppu.getFramePhase(), cropOverscan, scale);
            case CRT -> FrameRenderer.render(
                    ppu.getFrameBuffer(), palette, strength, warp, cropOverscan, scale);
            case NONE -> FrameRenderer.render(ppu.getFrameBuffer(), palette, cropOverscan, scale);
        };

        var parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        ImageIO.write(image, "png", path.toFile());
    }

    // ============================================================================ reading memory

    /**
     * Reads the CPU's address space without side effects.
     */
    public int[] readCPU(final int address, final int count) {
        var out = new int[count];

        for (var i = 0; i < count; i++) {
            out[i] = nes.getMemory().peek((address + i) & 0xFFFF);
        }

        return out;
    }

    /**
     * Reads the PPU's address space -- the pattern tables and the nametables -- without side
     * effects, and in particular without telling the mapper what was looked at.
     *
     * @see com.github.dimiro1.mynes.VRAM#peek(int)
     */
    public int[] readPPU(final int address, final int count) {
        var out = new int[count];

        for (var i = 0; i < count; i++) {
            out[i] = nes.getPPU().peekVRAM((address + i) & 0x3FFF);
        }

        return out;
    }

    public int[] readOAM(final int address, final int count) {
        var out = new int[count];

        for (var i = 0; i < count; i++) {
            out[i] = nes.getPPU().peekOAM((address + i) & 0xFF);
        }

        return out;
    }

    public int[] readPalette() {
        var out = new int[32];

        for (var i = 0; i < out.length; i++) {
            out[i] = nes.getPPU().peekPalette(i);
        }

        return out;
    }

    /**
     * Writes the whole machine to a file.
     */
    public void saveState(final Path path) throws IOException {
        SaveState.write(nes, path);
    }

    /**
     * Puts a machine back from a file.
     * <p>
     * Here rather than in {@link Headless} and {@link Repl} separately for one reason that is easy to
     * miss: {@link #previousHash} is what {@link #frameChanges} is counted against, and after a load
     * it describes a picture this machine no longer has. Left alone, the next frame would be reported
     * as a change that never happened and every {@code frameChanges} in the report would be one out.
     * Reseeding it belongs where it cannot be forgotten.
     *
     * @throws com.github.dimiro1.mynes.state.SaveStateException if the file will not load, in which
     *                                                           case the machine is untouched.
     */
    public void loadState(final Path path) throws IOException {
        SaveState.read(nes, path);

        // A machine nobody played their way to, so a recording in progress has to start again from
        // here rather than carry on describing a timeline that no longer leads anywhere.
        if (recorder != null) {
            recorder.jumped(nes);
        }

        previousHash = FrameAnalysis.hash(nes.getPPU().getFrameBuffer());
    }

    // ==================================================================================== rewind

    /**
     * Starts keeping the last {@code capacityFrames} frames, so the machine can be run backwards
     * through them.
     * <p>
     * Captures at once, which is what puts the machine as it stands at the floor of the history
     * rather than one frame above it. Explicit rather than always on because a headless run is
     * usually a measurement, and a measurement should not quietly cost two milliseconds a frame.
     *
     * @throws UsageException if it is already armed, since a second call would silently throw the
     *                        history away.
     */
    public void armRewind(final int capacityFrames) {
        if (rewind != null) {
            throw new UsageException(
                    "rewind is already on, holding " + rewind.capacity() + " frames. Turn it off"
                            + " first if the point is to start again with a different size.");
        }

        try {
            rewind = new Rewind(capacityFrames);
        } catch (IllegalArgumentException e) {
            throw new UsageException(e.getMessage());
        }

        rewind.capture(nes);
    }

    /**
     * Stops keeping history and drops what there was. Idempotent: turning off something that is
     * already off is what somebody meant either way.
     */
    public void disarmRewind() {
        rewind = null;
    }

    /**
     * Puts the machine back where it was {@code frames} frames ago.
     * <p>
     * {@link #previousHash} is reseeded here for the same reason {@link #loadState} reseeds it, and
     * it is worth saying twice because the two are easy to fix one at a time: it describes a picture
     * the machine no longer has, so the next frame would be counted as a change that never happened
     * and every {@code frameChanges} in the report would be one out.
     *
     * @return how many frames it actually moved, which is fewer than asked for when the history ran
     * out.
     * @throws UsageException if nothing has been kept, since a rewind that answered "0 frames" would
     *                        look the same as one that had simply run out.
     */
    public int rewind(final int frames) {
        if (rewind == null) {
            throw new UsageException(
                    "rewind is off, so there is no history to go back through. Turn it on with"
                            + " \"rewind on\" and run some frames first.");
        }

        var moved = rewind.rewind(nes, frames);

        // States and frames are the same number here, since a headless ring keeps one per frame --
        // which is not true of the window's, and is why MovieRecorder.rewound takes frames.
        if (recorder != null) {
            recorder.rewound(nes, moved);
        }

        framesRewound += moved;
        previousHash = FrameAnalysis.hash(nes.getPPU().getFrameBuffer());

        return moved;
    }

    /**
     * Whether history is being kept at all.
     */
    public boolean rewinding() {
        return rewind != null;
    }

    /**
     * How many frames of history are being kept once it is full, or 0 when it is off.
     */
    public int rewindCapacity() {
        return rewind == null ? 0 : rewind.capacity();
    }

    /**
     * How far back it could go right now, which climbs as a run goes on and stops at the capacity.
     */
    public int rewindable() {
        return rewind == null ? 0 : rewind.rewindable();
    }

    /**
     * How many frames this session has gone back altogether.
     */
    public long framesRewound() {
        return framesRewound;
    }

    // ==================================================================================== movies

    /**
     * Starts writing down what is pressed, so the run can be played again from a file.
     * <p>
     * The codes in the cartridge slot are pinned here, at the start, which is why both front ends
     * refuse to change them while a recording is running: a movie whose header names one set and
     * whose frames were played against another cannot be replayed and would not say so.
     *
     * @param fromPowerOn whether to record a movie that carries no state at all. Only honest on a
     *                    machine that has not run and whose cartridge RAM has not been filled from
     *                    a battery file, since a movie has no way to carry either; anything else
     *                    puts the machine as it stands into the file instead.
     * @throws UsageException if it is already on, since a second call would silently throw the take
     *                        away.
     */
    public void startRecording(final boolean fromPowerOn) {
        if (recorder != null) {
            throw new UsageException(
                    "a movie is already being recorded, " + recorder.framesRecorded() + " frames"
                            + " long. Stop it first if the point is to start a new one.");
        }

        recorder = fromPowerOn && frame() == 0
                ? MovieRecorder.atPowerOn(nes, genie.codes())
                : MovieRecorder.anchoredAt(nes, genie.codes());
    }

    /**
     * Stops recording and hands over what was recorded.
     *
     * @throws UsageException if nothing was being recorded, since an empty movie and a movie of a
     *                        session nobody recorded look identical from the outside.
     */
    public Movie stopRecording() {
        if (recorder == null) {
            throw new UsageException(
                    "nothing is being recorded, so there is no movie to write. Start one with"
                            + " \"record start\".");
        }

        var movie = recorder.movie();
        recorder = null;

        return movie;
    }

    /**
     * Whether a movie is being written down.
     */
    public boolean recording() {
        return recorder != null;
    }

    /**
     * How many frames are in the movie so far, or 0 when nothing is being recorded.
     */
    public long framesRecorded() {
        return recorder == null ? 0 : recorder.framesRecorded();
    }

    /**
     * Whether the movie being recorded carries a state to start from rather than starting at power
     * on. Can become true part way through a take: a loaded state or a rewind past the start of the
     * recording both re-anchor it.
     */
    public boolean recordingAnchored() {
        return recorder != null && recorder.anchored();
    }

    /**
     * Which frame the movie being recorded starts on, or 0 when nothing is being recorded.
     */
    public long recordingAnchorFrame() {
        return recorder == null ? 0 : recorder.anchorFrame();
    }

    /**
     * Puts the machine where a movie starts, so its buttons can be played back into it.
     * <p>
     * Here rather than in {@link Headless} for the reason {@link #loadState} is here: this is a
     * machine jump, and {@link #previousHash} describes a picture the machine no longer has.
     * Reseeding it belongs where it cannot be forgotten.
     *
     * @throws com.github.dimiro1.mynes.state.MovieException if the movie was recorded on another
     *                                                       cartridge or another machine, in which
     *                                                       case this one is untouched.
     */
    public void beginReplay(final Movie movie) {
        movie.applyAnchor(nes);

        previousHash = FrameAnalysis.hash(nes.getPPU().getFrameBuffer());
    }

    /**
     * The bytes of one of the things {@code --dump} can name.
     *
     * @throws UsageException if it names nothing.
     */
    public byte[] dump(final String what) {
        // The cartridge's RAM is taken from the chip rather than read through the bus, because the
        // window at $6000 comes back as zeros on a chip the game has switched off -- and switching
        // it off around anything risky is precisely what a battery board's enable line is for. A
        // board with no RAM fitted dumps nothing at all, which is the honest answer.
        if (what.equals("prgram")) {
            return nes.getBus().getMapper().prgRAM().clone();
        }

        var values = switch (what) {
            case "ram" -> nes.getMemory().getInternalRAM();
            case "oam" -> readOAM(0, 256);
            case "palette" -> readPalette();
            case "nametables" -> readPPU(0x2000, 0x1000);
            case "chr" -> readPPU(0x0000, 0x2000);
            default -> throw new UsageException(
                    "\"" + what + "\" is not something to dump. They are "
                            + String.join(", ", DUMPS) + ".");
        };

        var bytes = new byte[values.length];

        for (var i = 0; i < values.length; i++) {
            bytes[i] = (byte) values[i];
        }

        return bytes;
    }

    /**
     * Everything {@link #dump} understands, in the order a report lists them.
     */
    public static final List<String> DUMPS =
            List.of("ram", "oam", "palette", "nametables", "prgram", "chr");

    // ================================================================================== internals

    private void collectAudio() throws IOException {
        var count = nes.getAPU().drainSamples(samples);

        if (count == 0) {
            total.endFrame(true);
            window.endFrame(true);
            return;
        }

        var silent = true;

        for (var i = 0; i < count; i++) {
            if (samples[i] != 0) {
                silent = false;
            }

            total.add(samples[i]);
            window.add(samples[i]);
        }

        total.endFrame(silent);
        window.endFrame(silent);

        if (wav != null) {
            wav.write(samples, count);
        }
    }

    /**
     * Sound counted as it goes past, so that a run of any length costs the same four fields.
     */
    private static final class Accumulator {
        private long samples;
        private long peak;
        private double sumSquares;
        private long silentFrames;

        void add(final short sample) {
            var magnitude = Math.abs((long) sample);

            if (magnitude > peak) {
                peak = magnitude;
            }

            sumSquares += (double) sample * sample;
            samples++;
        }

        void endFrame(final boolean silent) {
            if (silent) {
                silentFrames++;
            }
        }

        AudioStats snapshot() {
            var rms = samples == 0 ? 0.0 : Math.sqrt(sumSquares / samples) / Short.MAX_VALUE;

            return new AudioStats(
                    samples,
                    round(peak / (double) Short.MAX_VALUE),
                    round(rms),
                    silentFrames);
        }

        void reset() {
            samples = 0;
            peak = 0;
            sumSquares = 0;
            silentFrames = 0;
        }
    }

    /**
     * Six decimal places, which is past the point where more of them would say anything about a
     * sixteen bit sample, and keeps two runs of the same command producing the same report.
     */
    private static double round(final double value) {
        return Math.round(value * 1_000_000.0) / 1_000_000.0;
    }
}
