package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPU;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * How much of its frame the program is using, and how much of the machine's memory.
 * <p>
 * Two questions that turn out to be one seam. Both are answered by watching the bytes the processor
 * stores -- where they go says which memory the game is really using, and <em>when</em> they stop
 * says the game has finished its work and is waiting for the next frame. So one hook feeds both,
 * and it is a hook {@link Debugger} already has down whenever anybody is watching the machine at
 * all: this costs a game being measured nothing it was not already paying.
 *
 * <h2>A 6502 has no idle</h2>
 *
 * There is no halt instruction on this processor and nothing here ever executes fewer than one
 * instruction per cycle it is given, so "how busy is the CPU" cannot be asked the way it is asked of
 * a desktop. What a game actually does with the time it has left over is spin, waiting for the
 * VBlank flag or for the byte its NMI handler sets. So the measurement is <b>the longest stretch of
 * the frame in which the program did nothing new</b>, and everything else in the frame is work.
 * <p>
 * <b>"Nothing new" is two things, and the second one is not an embellishment.</b> Storing nothing at
 * all is the obvious half -- {@code LDA $2002 / BPL} -- and is what Super Mario Bros., Super Mario
 * Bros. 3 and Metroid do. The other half is storing <em>the same byte of the machine's own memory
 * from the same instruction</em>, over and over, which is what a wait loop that also advances a
 * counter or a random seed looks like: Contra sits at $0009 every 249 cycles, Mega Man 5 at $0090
 * every 93, Castlevania the same. Measured on silence alone all three come out at ninety-nine per
 * cent busy while sitting on a menu; measured this way they come out at 35, 60 and 61, which is what
 * they are. So a write ends the stretch only when it differs from the write before it.
 * <p>
 * Three things fall out of that, and all three are wanted. A <b>fill loop</b> -- one {@code STA}
 * running down an array -- writes a different address every time and so counts as the work it is. A
 * write to <b>anything that is not memory</b> always ends the stretch, because $2000-$401F and the
 * mapper are the machine being <em>told</em> something and there is no telling it the same thing
 * twice by accident -- which is the same split the memory half of this class makes, doing a second
 * job. And a run of writes from the same instruction to <b>several</b> addresses in rotation is
 * work, which is right for the sound driver Battletoads runs at $873A and wrong for a wait loop
 * that turns over two bytes instead of one. That last one is the way this can still be fooled.
 * <p>
 * <b>Which way it is wrong is worth knowing.</b> Only the longest stretch is counted, so a frame
 * that waits twice reads as busier than it is; a wait loop this cannot see through reads as busier
 * still; and a long stretch of arithmetic that spills nothing to memory reads as a wait it is not,
 * which on a processor with three registers does not go on for long. Two of the three overstate the
 * work, which is the safer direction for a gauge somebody is reading to decide whether a game has
 * room left.
 * <p>
 * <b>Cycles a transfer stole are neither work nor waiting.</b> A sprite DMA holds the processor off
 * the bus for 513 cycles and a DMC sample fetch for four, and during those the program simply is not
 * running. Counting them as a wait would report a game doing more work as doing less, so they come
 * out of the frame first and are reported on their own. That is what
 * {@link CPU#getRunCycles()} is for.
 * <p>
 * <b>A stretch is measured inside one frame.</b> It is closed at every boundary rather than carried
 * across, which keeps the answer between nothing and the whole frame and so keeps the percentage
 * honest. Nothing is lost by it in practice: what a game waits for is its NMI, and the NMI is inside
 * the frame.
 *
 * <h2>Which memory, and what "used" means</h2>
 *
 * <b>Written, rather than not zero.</b> Zero is a perfectly good thing for a variable to hold, so
 * counting non-zero bytes would report a game's flags and counters as unused memory for as long as
 * they were false. What is kept instead is one bit per byte, set when the program stores there.
 * <p>
 * Two of them, because there are two questions. <b>Ever</b> is every byte written since somebody
 * last asked to start again, which is what the memory is used <em>for</em>; <b>live</b> is what has
 * been written since the last reading, a quarter of a second, which is where the game is working
 * <em>now</em>. {@link #forget()} is why the first one needs a way to be started again: nearly
 * every cartridge clears all 2KB at power on, so a map taken from the moment the machine started is
 * a map of that clear and of nothing else.
 * <p>
 * <b>The three areas of the console's own RAM are split where the hardware splits them</b>, and no
 * further. The zero page is an addressing mode and the stack page is where the stack pointer lives;
 * both are facts about the processor. That $0200 holds the sprite buffer a DMA copies from is a
 * <em>convention</em> -- the transfer copies whatever page $4014 names -- so it is not made into an
 * area of its own. It does not need to be: a game that keeps one shows it as a solid 256 bytes at
 * the left of the third strip, which is the reading rather than a label about it.
 * <p>
 * <b>Cartridge RAM is watched at the window rather than at the chip.</b> The hook sees an address
 * on the bus, so a board that banks more than 8KB into $6000-$7FFF -- which of the boards here is
 * only MMC1's larger ones -- shows the window with every bank drawn on top of the others. For the
 * same reason a write to a window whose RAM has been switched off is still a write: what is being
 * watched is what the processor put on the bus, not what the board did with it. A game writing into
 * disabled RAM is a bug, and one this shows rather than hides.
 * <p>
 * Everything else the processor writes to -- $2000-$401F, and the mapper registers above $8000 --
 * is deliberately not here. Those are the machine being <em>told</em> something rather than memory
 * being used, and where they went is the Events tab's question. Which is exactly the split
 * {@link Debugger} already makes on the other side of the same hook, in reverse: it records
 * everything but the two RAMs, and this records only them.
 *
 * <h2>Who owns one</h2>
 *
 * The thread clocking the machine, and nothing else touches it. {@link #wrote} is called from
 * inside a bus write and {@link #frameEnded} at the boundary; the record {@link #snapshot} hands
 * back is built fresh and shared with nothing, which is what makes it safe to read on another
 * thread afterwards. Nothing here allocates while the machine is running.
 */
public final class Usage {
    /**
     * How many frames of busy figures are kept: two seconds on NTSC.
     * <p>
     * The same window {@code Readout.Pads} keeps and for the same reason -- long enough for a
     * pattern to be a pattern, short enough that a game which stopped struggling ten seconds ago is
     * not still being blamed for it. The two are read together: a run of frames at full load here
     * and a run of unread pads there are the same event seen from two sides.
     */
    public static final int WINDOW = 120;

    /**
     * How many cells one area's map is drawn as.
     * <p>
     * A fixed number rather than one byte per cell, so that four areas of four different sizes come
     * out as four strips of one width and can be read against each other. It divides all four
     * exactly: two bytes to a cell on the two small pages, twelve on the rest of the work RAM and
     * sixty four on the cartridge's window.
     */
    public static final int CELLS = 128;

    /**
     * A range of the processor's address map that the program can write to and read back, which is
     * what makes it memory rather than a register.
     */
    public enum Area {
        /**
         * $0000-$00FF, which the 6502 reaches with a one byte address -- so it is where a game
         * keeps everything it touches often, and running out of it is a real thing to run out of.
         */
        ZERO_PAGE(0x0000, 0x0100, 0),

        /**
         * $0100-$01FF, where the stack pointer lives. What is drawn here is the high water mark by
         * another name: the stack fills downwards from the top, so the far end of this strip is how
         * deep it has ever got, and a strip that reaches the near end is a stack about to run into
         * the zero page.
         */
        STACK(0x0100, 0x0100, 0x0100),

        /**
         * $0200-$07FF, the rest of the console's two kilobytes. No hardware meaning at all: what a
         * game keeps here is the game's own business, which is why it is one area rather than
         * several named after conventions.
         */
        WORK_RAM(0x0200, 0x0600, 0x0200),

        /**
         * $6000-$7FFF, whatever the cartridge has put in the window -- the battery-backed save on a
         * board that has one, scratch memory on a board that does not, and nothing at all on the
         * boards with no RAM chip fitted.
         */
        CARTRIDGE_RAM(0x6000, 0x2000, 0x0800);

        private final int start;
        private final int size;

        /**
         * Where this area begins in the bitmaps, which pack the two RAMs together with the hole
         * between them left out.
         */
        private final int bit;

        Area(final int start, final int size, final int bit) {
            this.start = start;
            this.size = size;
            this.bit = bit;
        }

        /**
         * Where it begins on the processor's bus.
         */
        public int start() {
            return start;
        }

        public int size() {
            return size;
        }

        /**
         * How many bytes one cell of the map covers.
         */
        public int perCell() {
            return size / CELLS;
        }
    }

    /**
     * How many bits the two bitmaps hold: the console's 2KB and the cartridge's 8KB window, with
     * the $2000-$5FFF hole between them left out rather than reserved.
     */
    private static final int BITS = 0x0800 + 0x2000;

    /**
     * One bit per byte, set where the program has stored since {@link #forget()}.
     */
    private final long[] ever = new long[BITS / 64];

    /**
     * The same, since the last {@link #snapshot}: a quarter of a second, which is where the game is
     * working now rather than where it has ever worked.
     */
    private final long[] live = new long[BITS / 64];

    /**
     * How busy each of the last {@link #WINDOW} frames was, 0 to 1. A ring, oldest wherever
     * {@link #at} points once it has filled.
     */
    private final double[] busy = new double[WINDOW];

    private int at;
    private int filled;

    /**
     * Which frame {@link #frameEnded} was last called for, checked the way {@code PadPolling}
     * checks it: anything but one more than this means frames went by uncounted -- a panel that was
     * shut for a while, or a rewind -- and there is no difference to take across the gap. Both
     * counters can move discontinuously through a state being loaded, which is the same case.
     * <p>
     * Starts at a number no frame can be one more than, so the first frame counted is a baseline
     * rather than a measurement.
     */
    private long lastFrame = Long.MIN_VALUE;

    /**
     * The executed cycle count at the last frame boundary, which is what this frame's is measured
     * against.
     */
    private long runAtBoundary;

    /**
     * The same for the stolen ones.
     */
    private long stalledAtBoundary;

    /**
     * The executed cycle count at the last write that was not a repeat of the one before it, which
     * is where the stretch now open began.
     */
    private long runAtWrite;

    /**
     * The last write's instruction and address, or -1 before there has been one. What the next
     * write is compared against to decide whether the program has done anything new -- see the
     * class comment, which is where the whole of that argument is.
     */
    private int lastPc = -1;
    private int lastAddress = -1;

    /**
     * The longest stretch of executed cycles with nothing new in it, so far this frame.
     */
    private long wait;

    private int writes;

    /**
     * What the frame that just finished came to, held so that a reading taken between frames
     * describes a whole one.
     */
    private long frameCycles;
    private long frameStolen;
    private long frameWait;
    private int frameWrites;

    // =============================================================== what the machine tells it

    /**
     * The program has stored a byte. Called from the write hook, on the thread clocking the
     * machine, before the byte lands.
     *
     * @param address   where it is going, $0000-$FFFF, unmirrored.
     * @param pc        which instruction put it there, as {@link CPU#getPC()} answers during the
     *                  write. It is a point inside the instruction rather than its first byte,
     *                  which is all this needs of it: the same store always reaches the same one,
     *                  so two writes agreeing here came from the same place.
     * @param runCycles the processor's executed cycle count, from
     *                  {@link CPU#getRunCycles()}.
     */
    public void wrote(final int address, final int pc, final long runCycles) {
        writes++;

        var bit = bitFor(address);

        // Anything that is not memory is always something new -- the machine being told something
        // rather than the program turning over -- so those close the stretch whatever came before.
        if (bit < 0 || pc != lastPc || address != lastAddress) {
            var open = runCycles - runAtWrite;

            if (open > wait) {
                wait = open;
            }

            runAtWrite = runCycles;
        }

        lastPc = pc;
        lastAddress = address;

        if (bit < 0) {
            return;
        }

        ever[bit >> 6] |= 1L << bit;
        live[bit >> 6] |= 1L << bit;
    }

    /**
     * A frame has finished. Called on the emulation thread, at the boundary, with the machine
     * standing still.
     *
     * @param frame     which frame this is, counted the way the loop counts them.
     * @param runCycles the processor's executed cycle count.
     * @param stalled   how many cycles a transfer has held it off the bus, in total.
     */
    public void frameEnded(final long frame, final long runCycles, final long stalled) {
        if (frame != lastFrame + 1) {
            restart(frame, runCycles, stalled);
            return;
        }

        lastFrame = frame;

        // The open run is closed here rather than carried into the next frame, which is what keeps
        // the wait between nothing and the whole frame -- see the class comment.
        var open = runCycles - runAtWrite;

        if (open > wait) {
            wait = open;
        }

        var ran = runCycles - runAtBoundary;

        frameStolen = stalled - stalledAtBoundary;
        frameCycles = ran + frameStolen;
        frameWait = Math.min(wait, ran);
        frameWrites = writes;

        busy[at] = ran <= 0 ? 0 : (double) (ran - frameWait) / ran;
        at = (at + 1) % WINDOW;
        filled = Math.min(filled + 1, WINDOW);

        runAtBoundary = runCycles;
        runAtWrite = runCycles;
        stalledAtBoundary = stalled;
        wait = 0;
        writes = 0;

        // Forgotten with the stretch it belonged to, so that the first write of the new frame is
        // always something new rather than a repeat of one in the frame before it.
        lastPc = -1;
        lastAddress = -1;
    }

    // ======================================================================== what a gauge asks

    /**
     * The last finished frame and the maps, as a reading crossing to another thread carries them.
     * Built fresh each time and shared with nothing.
     * <p>
     * <b>Reading empties the live maps</b>, which is the meters' arrangement and for the meters'
     * reason: what "written recently" means is "since somebody last looked", and two callers
     * looking would otherwise quietly empty each other. So there is one caller, and it is the frame
     * observer.
     *
     * @param cartridgeRAM how much RAM the board actually fitted, which decides whether there is a
     *                     cartridge RAM area to report at all.
     */
    public Snapshot snapshot(final int cartridgeRAM) {
        var areas = new ArrayList<Segment>(Area.values().length);

        for (var area : Area.values()) {
            if (area == Area.CARTRIDGE_RAM && cartridgeRAM == 0) {
                continue;
            }

            areas.add(segment(area));
        }

        Arrays.fill(live, 0);

        return new Snapshot(
                frameCycles, frameStolen, frameWait, frameWrites, window(), List.copyOf(areas));
    }

    /**
     * Frames were run that were not the game running, so there is nothing to measure across them.
     * <p>
     * The counterpart of {@link Debugger#unwatched}, and needed for the same caller: redrawing a
     * stopped picture with a layer switched off means rendering two frames the game never asked
     * for. The hooks are off while they run, so nothing is counted -- but the processor's counters
     * move, and putting the machine back moves only one of the two, since how many cycles a
     * transfer stole is a gauge rather than state. Either way the next frame has no previous frame
     * to be a difference from, which is the case the frame numbers already describe.
     */
    public void unmeasured() {
        lastFrame = Long.MIN_VALUE;
    }

    /**
     * Forgets which bytes have ever been written, and nothing else.
     * <p>
     * There has to be a way to ask for this, because the first thing nearly every cartridge does is
     * clear all 2KB of work RAM -- so a map that began at power on is a map of that loop. Started
     * again once the game is playing, it is a map of the game.
     */
    public void forget() {
        Arrays.fill(ever, 0);
        Arrays.fill(live, 0);
    }

    // ================================================================================ internals

    /**
     * Where an address lands in the bitmaps, or -1 for the two thirds of the map that are registers
     * and ROM rather than memory. The console's RAM is mirrored four times over $0000-$1FFF, and
     * the mirrors are the same bytes.
     */
    private static int bitFor(final int address) {
        if (address < 0x2000) {
            return address & 0x07FF;
        }

        if (address >= 0x6000 && address < 0x8000) {
            return 0x0800 + (address - 0x6000);
        }

        return -1;
    }

    private Segment segment(final Area area) {
        var everMap = new int[CELLS];
        var liveMap = new int[CELLS];
        var everBytes = 0;
        var liveBytes = 0;
        var perCell = area.perCell();

        for (var cell = 0; cell < CELLS; cell++) {
            var from = area.bit + cell * perCell;

            for (var offset = 0; offset < perCell; offset++) {
                var bit = from + offset;

                if ((ever[bit >> 6] & (1L << bit)) != 0) {
                    everMap[cell]++;
                    everBytes++;
                }

                if ((live[bit >> 6] & (1L << bit)) != 0) {
                    liveMap[cell]++;
                    liveBytes++;
                }
            }
        }

        return new Segment(area, everBytes, liveBytes, everMap, liveMap);
    }

    /**
     * The busy figures, oldest first so that a strip drawn from them reads left to right the way
     * time does.
     */
    private double[] window() {
        var out = new double[filled];

        for (var i = 0; i < filled; i++) {
            out[i] = busy[(at - filled + i + 2 * WINDOW) % WINDOW];
        }

        return out;
    }

    /**
     * Rebases on this frame and starts the window again. What frames going by uncounted deserves:
     * measuring across the gap would report however long the panel was shut as one frame's work.
     */
    private void restart(final long frame, final long runCycles, final long stalled) {
        lastFrame = frame;
        runAtBoundary = runCycles;
        runAtWrite = runCycles;
        stalledAtBoundary = stalled;

        wait = 0;
        writes = 0;
        lastPc = -1;
        lastAddress = -1;
        frameCycles = 0;
        frameStolen = 0;
        frameWait = 0;
        frameWrites = 0;

        at = 0;
        filled = 0;
    }

    // ================================================================================== the answer

    /**
     * One area of memory, as far as it has been used.
     *
     * @param area the range this is about.
     * @param ever how many of its bytes have been written since {@link Usage#forget()}.
     * @param live how many were written in the quarter second before this reading.
     * @param everMap  {@link Usage#CELLS} cells, each holding how many of its bytes are in
     *                 {@code ever}. Handed over rather than shared.
     * @param liveMap  the same for {@code live}, which is a subset of it.
     */
    public record Segment(Area area, int ever, int live, int[] everMap, int[] liveMap) {
        /**
         * The share of the area that has ever been written, 0 to 1.
         */
        public double everFraction() {
            return (double) ever / area.size();
        }
    }

    /**
     * The frame that has just finished, and where the memory stands.
     *
     * @param cycles  how many CPU cycles the frame took, which is a fact about the console until
     *                somebody overclocks it.
     * @param stolen  how many of those a transfer held the processor off the bus for.
     * @param longestWait the longest run of executed cycles in the frame with no write in it.
     * @param writes  how many bytes the program stored during it.
     * @param busy    one figure per recent frame, oldest first, 0 to 1. Handed over rather than
     *                shared, and empty where nothing was counting.
     * @param memory  one entry per area, with the cartridge's left out on a board with no RAM.
     */
    public record Snapshot(
            long cycles,
            long stolen,
            long longestWait,
            int writes,
            double[] busy,
            List<Segment> memory) {

        /**
         * What a reading taken anywhere but the emulation loop knows: nothing. A frame measured
         * against no previous frame is not a measurement of anything, and the same is true of a map
         * built by nobody.
         */
        public static final Snapshot NONE = new Snapshot(0, 0, 0, 0, new double[0], List.of());

        /**
         * Whether anybody was counting. Told apart from a game that used none of its frame,
         * because those two are not the same answer.
         */
        public boolean measured() {
            return busy.length > 0;
        }

        /**
         * How much of the last frame's executed cycles went on work rather than on waiting, 0 to 1.
         */
        public double load() {
            return busy.length == 0 ? 0 : busy[busy.length - 1];
        }

        /**
         * How many cycles the program actually got: the frame, less whatever a transfer took.
         */
        public long ran() {
            return cycles - stolen;
        }
    }
}
