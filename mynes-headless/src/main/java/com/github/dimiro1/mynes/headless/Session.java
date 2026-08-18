package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.state.SaveState;
import com.github.dimiro1.mynes.video.FrameAnalysis;
import com.github.dimiro1.mynes.video.FrameRenderer;

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
     * Where breakpoints and watchpoints live. Constructed here rather than passed in because a
     * session is the only thing that can drive one: it owns the loop that has to run an instruction
     * at a time for a breakpoint to mean anything.
     */
    private final Debugger debugger = new Debugger();

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
     * @param nes     the machine, already built from a cartridge.
     * @param palette 512 packed ARGB entries, which is what a screenshot is drawn with.
     * @param wav     where to write the sound, or null to only count it.
     */
    public Session(final NES nes, final int[] palette, final WavWriter wav) {
        this.nes = nes;
        this.palette = palette;
        this.wav = wav;
        this.previousHash = FrameAnalysis.hash(nes.getPPU().getFrameBuffer());

        debugger.attach(nes);
    }

    public NES nes() {
        return nes;
    }

    public Debugger debugger() {
        return debugger;
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
     */
    public void reset() {
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
        var image = FrameRenderer.render(
                nes.getPPU().getFrameBuffer(), palette, cropOverscan, scale);

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
