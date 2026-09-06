package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.CPU;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.mappers.Mirroring;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * The machine at a frame boundary: the third way anything in the front end reads one, and the one
 * the gauges use.
 * <p>
 * The other two are still right for what they do. The PPU viewers poll on a Swing timer without
 * synchronising, which is fine because what they read is <em>arrays</em> -- an element cannot tear,
 * so the worst case is a tile a frame out of date. The debugger takes a {@code MachineSnapshot}
 * inside the stop callback, which is exact, once, with the machine halted -- spelled rather than
 * linked because that record is package private to the debugger and this cannot see it.
 * <p>
 * Neither works for a dashboard. What a dashboard shows is <em>scalars</em> -- $2000, the scroll,
 * the frame number, which voices are sounding -- and a dozen of them read one at a time off a
 * running machine is not a slightly stale picture but a machine that never existed: the scroll from
 * one frame beside the beam position from the next. And a dashboard that only refreshed when the
 * machine stopped would be blank exactly while somebody was watching a game.
 * <p>
 * So it is built here, on the emulation thread, at the boundary where a frame has just finished and
 * nothing is half written -- see {@link EmulatorRunner#setFrameObserver} -- and handed to the event
 * dispatch thread whole. It is a record of primitives and holds no reference to the machine, which
 * is what makes that safe: there is nothing in one to read later.
 * <p>
 * It is cheap on purpose. A couple of dozen field reads, four times a second, against the ninety
 * thousand a frame's worth of emulation is. Nothing is copied and nothing is scanned; the memory
 * view's 64K is the snapshot's business and stays there.
 *
 * @param cpu       the processor, as {@link CPU#getState()} gives it.
 * @param frame     which frame has just finished.
 * @param scanline  where the beam is, which at a boundary is the top of the picture or the last of
 *                  the blanking lines depending on how far the last tick overshot.
 * @param dot       the dot within that line.
 * @param control   $2000 as it was last written.
 * @param mask      $2001 as the rendering hardware sees it, which a write reaches two dots late.
 * @param status    the three flags of $2002, without the open bus and without clearing anything.
 * @param oamAddress where the next $2004 would land.
 * @param v         the PPU's current VRAM address, which is where the beam is reading.
 * @param t         the temporary one, which is what the next frame will start from.
 * @param fineX     the three bits of horizontal scroll $2005 keeps outside {@code t}.
 * @param writeLatch whether the next $2005/$2006 write is the second of a pair.
 * @param apuStatus $4015, without acknowledging the frame counter's interrupt.
 * @param fiveStep  whether the frame counter is running the five step sequence.
 * @param frameIRQInhibited whether $4017 bit 6 is holding its interrupt off.
 * @param pad1      which buttons player one is holding, as the {@code BUTTON_} flags.
 * @param pad2      the same for player two, which nothing puts anything in yet.
 * @param voices    what each of the APU's five is doing, in {@link APUChannel} order.
 * @param peaks     the loudest each of them has been since the last readout, which is a quarter of
 *                  a second: 0 to 15, or 0 to 127 for the DMC.
 * @param scope     a decimated slice of the last frame's mixed output, or empty where there is no
 *                  sound card to have drained one. Handed over rather than shared -- whoever built
 *                  it must not write to it again.
 * @param board     what the cartridge is showing the console, which on a banked board changes as
 *                  often as anything else here.
 * @param traces    the same slice of the same frame for each voice on its own, in
 *                  {@link APUChannel} order -- what each put <em>into</em> the mixer, where
 *                  {@code scope} is what came out of it. Empty where the scope is.
 * @param pads      how often the game has been looking at the controllers, which is the one thing
 *                  here that is counted rather than read: it takes two frames to see, so only the
 *                  loop that ran them can answer. {@link Pads#NONE} where nothing was counting.
 * @param events    everything the machine did to its hardware during the frame that just finished,
 *                  which is the other thing only the loop that ran it can answer -- it is a
 *                  <em>history</em> rather than a state, and by the time a frame is over there is
 *                  nothing left in the machine to say when any of it happened.
 *                  {@link Events#NONE} where nothing was recording.
 */
public record Readout(
        CPU.State cpu,
        long frame,
        int scanline,
        int dot,
        int control,
        int mask,
        int status,
        int oamAddress,
        int v,
        int t,
        int fineX,
        boolean writeLatch,
        int apuStatus,
        boolean fiveStep,
        boolean frameIRQInhibited,
        int pad1,
        int pad2,
        List<APU.VoiceState> voices,
        int[] peaks,
        short[] scope,
        Board board,
        List<short[]> traces,
        Pads pads,
        Events events) {

    /**
     * What the cartridge is doing, which is a question about the board rather than about the game.
     * <p>
     * Together rather than as five components of the record above, because they are answered
     * together and because four of the five are the same answer on eleven of the twelve boards
     * here: only MMC1 and MMC3 can switch their RAM off, and only MMC3 counts scanlines.
     *
     * @param banks       which bank each window of the two address spaces is showing.
     * @param mirroring   how the cartridge has wired the console's two kilobytes of nametable.
     * @param ramBytes    how much cartridge RAM the board fitted, which is not always what the
     *                    header claimed.
     * @param ramEnabled  whether that RAM is answering at all.
     * @param ramWritable whether a write to it lands.
     * @param irq         the scanline counter, or null on a board that has none.
     */
    public record Board(
            Mapper.Banks banks,
            Mirroring mirroring,
            int ramBytes,
            boolean ramEnabled,
            boolean ramWritable,
            @Nullable Mapper.ScanlineIRQ irq) {
    }

    /**
     * How often the game has looked at the pads, frame by frame.
     * <p>
     * The only part of a readout that is not a reading. Everything else here is a field of the
     * machine as it stands; this is a <em>difference</em> between the frame that just finished and
     * the one before it, so it can only be answered by whatever ran both -- see
     * {@link PadPolling}, which is where the arithmetic is and why it stops when nobody is looking.
     * <p>
     * <b>{@code polled} is the gauge worth having.</b> A game reads the pad once per frame, in the
     * NMI or at the top of its main loop, so a frame that went by without one is a frame whose
     * work did not finish in time -- which is exactly the stutter {@code --hack overclock} exists
     * to undo, and is invisible in a picture that is simply showing the last frame again.
     *
     * @param polls1   how many times pad one was latched in the frame that just finished, which on
     *                 a game keeping up is 1. The same number for both pads: one write to $4016
     *                 latches both ports.
     * @param bits1    how many bits were clocked out of pad one in that frame, which is 8 per poll.
     * @param polls2   the same for pad two.
     * @param bits2    the same for pad two -- 0 on every game with no two player mode, which is
     *                 what tells the two ports apart.
     * @param polled   one entry per recent frame, oldest first, true where the game latched the
     *                 pad. Handed over rather than shared. Shorter than the window until enough
     *                 frames have gone by, and empty where nothing was counting.
     * @param lagFrames how many of those frames went by without a poll.
     */
    public record Pads(
            int polls1,
            int bits1,
            int polls2,
            int bits2,
            boolean[] polled,
            int lagFrames) {

        /**
         * How many frames {@code polled} holds once it has filled: two seconds on NTSC.
         * <p>
         * Long enough for a pattern to be a pattern rather than a coincidence, and short enough
         * that a game which stopped lagging ten seconds ago is not still being blamed for it. Here
         * rather than in {@link PadPolling}, which fills it, because whatever draws it needs a
         * width before the first readout has arrived to say how wide it is.
         */
        public static final int WINDOW = 120;

        /**
         * What a readout taken anywhere but the emulation loop knows about polling: nothing. A
         * frame counted against no previous frame is not a measurement of anything.
         */
        public static final Pads NONE = new Pads(0, 0, 0, 0, new boolean[0], 0);

        /**
         * How many frames the window above actually holds, which is what {@code lagFrames} is out
         * of. Zero means nobody was counting rather than a game that never lagged.
         */
        public int frames() {
            return polled.length;
        }
    }

    /**
     * One frame of what the machine did to its hardware, in the order it did it.
     * <p>
     * The only part of a readout that is a <em>history</em>. Everything else here is the machine as
     * it stands, and could in principle be asked for at any moment; this could not be asked for at
     * all, because by the time the frame is over there is nothing in the machine to say which
     * scanline a write to $2005 landed on -- and that is the whole question.
     *
     * @param events  in the order they happened, oldest first. Handed over rather than shared.
     * @param dropped how many more there were than the log could hold, which is nearly always zero
     *                and is not zero exactly when a game is doing the thing worth looking at.
     */
    public record Events(List<Debugger.Event> events, int dropped) {

        /**
         * What a readout taken anywhere but the emulation loop knows about the frame: nothing.
         */
        public static final Events NONE = new Events(List.of(), 0);

        /**
         * How many of a kind there are, for the line that says what the frame was made of.
         */
        public int count(final Debugger.EventKind kind) {
            var found = 0;

            for (var event : events) {
                if (event.kind() == kind) {
                    found++;
                }
            }

            return found;
        }
    }

    /**
     * What a readout taken anywhere but the emulation loop has for a scope: nothing. The samples
     * belong to the thread feeding the sound card, and a machine stopped at a breakpoint is not
     * feeding one.
     */
    public static final short[] NO_SCOPE = new short[0];

    /**
     * The same for the five voices, for a readout taken where there is no frame of sound to slice.
     */
    public static final List<short[]> NO_TRACES = List.of();

    /**
     * Reads the machine. Only ever called on the thread that clocks it, at a frame boundary.
     */
    public static Readout of(final NES nes) {
        return of(nes, NO_SCOPE, NO_TRACES, Pads.NONE, Events.NONE);
    }

    /**
     * The same, with the four things a machine cannot be asked for: a slice of what the sound card
     * was given, a slice of what each voice put into it, how often the game has been reading the
     * pads, and everything it did to its hardware during the frame. All four are the emulation
     * loop's own bookkeeping rather than the machine's.
     */
    public static Readout of(
            final NES nes,
            final short[] scope,
            final List<short[]> traces,
            final Pads pads,
            final Events events) {
        var ppu = nes.getPPU();
        var apu = nes.getAPU();
        var voices = new ArrayList<APU.VoiceState>(APUChannel.values().length);
        var peaks = new int[APUChannel.values().length];

        for (var channel : APUChannel.values()) {
            voices.add(apu.voice(channel));
        }

        apu.peaks(peaks);

        return new Readout(
                nes.getCPU().getState(),
                ppu.getFrame(),
                ppu.getScanline(),
                ppu.getDot(),
                ppu.getControl(),
                ppu.getMask(),
                ppu.peekStatus(),
                ppu.getOAMAddress(),
                ppu.getV(),
                ppu.getT(),
                ppu.getFineX(),
                ppu.isWriteLatchSet(),
                apu.peekStatus(),
                apu.isFiveStepFrameCounter(),
                apu.isFrameIRQInhibited(),
                nes.getController1().getButtons(),
                nes.getController2().getButtons(),
                List.copyOf(voices),
                peaks,
                scope,
                boardOf(nes),
                traces,
                pads,
                events);
    }

    private static Board boardOf(final NES nes) {
        var mapper = nes.getBus().getMapper();

        return new Board(
                mapper.banks(),
                mapper.mirroring(),
                mapper.prgRAM().length,
                mapper.prgRAMEnabled(),
                mapper.prgRAMWritable(),
                mapper.irq());
    }

    /**
     * One voice's own waveform over the last frame, or empty where nothing drained one.
     */
    public short[] trace(final APUChannel channel) {
        return traces.isEmpty() ? NO_SCOPE : traces.get(channel.ordinal());
    }

    /**
     * What one voice is doing, by name rather than by position.
     */
    public APU.VoiceState voice(final APUChannel channel) {
        return voices.get(channel.ordinal());
    }

    /**
     * How loud that voice has been since the last readout, as a fraction of the loudest it could
     * be: 15 for the four that come off an envelope or a sequencer, 127 for the DMC's level.
     */
    public double peak(final APUChannel channel) {
        var top = channel == APUChannel.DMC ? 127.0 : 15.0;

        return Math.min(1, peaks[channel.ordinal()] / top);
    }

    /**
     * Where the picture the game is drawing starts, in pixels into the four nametables.
     * <p>
     * Out of {@code t} rather than {@code v}, which is the same choice the nametable viewer makes:
     * {@code v} is where the beam has got to and moves every eight dots, where {@code t} is what
     * the next frame will start from and is what a game means by "the scroll". Which also means
     * this is <b>as good as the scroll it is asked at</b>: a game that splits the screen mid-frame
     * has written the second half's scroll by the time the frame ends, so what a boundary sees is
     * whichever half was written last.
     */
    public int scrollX() {
        return ((t >> 10) & 1) * 256 + (t & 0x1F) * 8 + fineX;
    }

    public int scrollY() {
        return ((t >> 11) & 1) * 240 + ((t >> 5) & 0x1F) * 8 + ((t >> 12) & 7);
    }

    /**
     * Whether $2001 has either of the two bits that make the chip fetch anything.
     */
    public boolean renderingEnabled() {
        return (mask & 0x18) != 0;
    }

    /**
     * Where the background is taking its tiles from, $0000 or $1000, out of $2000 bit 4.
     * <p>
     * Spelled as the address rather than as the bit for the reason {@code PPU} spells it that way:
     * a caller with a tile number wants somewhere to add it to.
     */
    public int backgroundPatternTable() {
        return (control & 0x10) != 0 ? 0x1000 : 0x0000;
    }

    /**
     * The same for 8x8 sprites, out of $2000 bit 3-- meaningless while {@link #spriteHeight()} is
     * 16, since a tall sprite picks its table with the low bit of its tile number.
     */
    public int spritePatternTable() {
        return (control & 0x08) != 0 ? 0x1000 : 0x0000;
    }

    public int spriteHeight() {
        return (control & 0x20) != 0 ? 16 : 8;
    }

    /**
     * Which nametable the frame starts in, $2000 to $2C00, out of the two bits of $2000 that are
     * also bits 10 and 11 of {@code t}.
     */
    public int nametable() {
        return 0x2000 + (control & 3) * 0x400;
    }

    /**
     * The address of the byte on top of the stack, or $0200 when there is nothing on it.
     * <p>
     * The pointer names the next free slot rather than the last used one, so the top is one above
     * it -- and an empty stack's top would be $0200, which is the first address that is not stack.
     */
    public int stackTop() {
        return 0x0100 + cpu.sp() + 1;
    }

    /**
     * The processor flags the way nestest's log spells them: set ones in capitals, clear ones not,
     * which reads at a glance where eight ones and zeros do not.
     */
    public String flags() {
        var names = "NV-BDIZC";
        var out = new StringBuilder(8);

        for (var bit = 0; bit < 8; bit++) {
            var set = (cpu.p() & (0x80 >> bit)) != 0;
            var name = names.charAt(bit);

            out.append(set ? Character.toUpperCase(name) : Character.toLowerCase(name));
        }

        return out.toString();
    }
}
