package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPU;
import com.github.dimiro1.mynes.MMU;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.PPU;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Where a machine is told to stop, and why it did.
 * <p>
 * This holds no state of the console's, which is deliberate and is what keeps it out of the save
 * state: a breakpoint belongs to whoever is watching, the way the Debug menu's layer switches do,
 * and a state file that put one back would be restoring the debugger rather than the machine.
 * Nothing reachable from {@link NES} refers to this class, so the reflective walk in
 * {@code SaveStateCompletenessTests} never sees it and its tables are never vandalised.
 * <p>
 * Everything here is called on whichever thread is clocking the machine, and on no other -- the same
 * rule the NES itself keeps. The window that drives it posts its changes onto that thread rather
 * than making them itself, which is why there is no synchronisation anywhere below.
 * <p>
 * The one thing worth knowing before using it: <b>the machine is only watched when something has
 * been asked for</b>. {@link #isArmed()} is what a driver asks once a frame to decide whether to
 * clock the machine an instruction at a time or a frame at a time, and a debugger with no
 * breakpoints, no watchpoints and nothing pending answers no.
 */
public final class Debugger {
    /**
     * How far the machine is meant to get before it stops again.
     */
    private enum Stepping {
        NONE, INSTRUCTION, FRAME
    }

    /**
     * Which way round a watchpoint is watching.
     * <p>
     * Two questions rather than one setting with a stronger and a weaker position. "What wrote to
     * $0770" is the question a watchpoint was invented for; "what reads this table" is the other
     * one, and a game with a hundred reads a frame of an address it writes once is exactly the game
     * where being able to ask them separately is the difference between an answer and a wall of
     * output.
     */
    public enum Access {
        READ,
        WRITE,

        /**
         * Both, for when the question is "does anything touch this at all".
         */
        BOTH;

        /**
         * How a command line and a JSON reply spell it.
         */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * The one named by {@code read}, {@code write} or {@code both}, or null for anything else.
         */
        public static Access byId(final String id) {
            for (var access : values()) {
                if (access.id().equalsIgnoreCase(id.trim())) {
                    return access;
                }
            }

            return null;
        }

        boolean reads() {
            return this != WRITE;
        }

        boolean writes() {
            return this != READ;
        }
    }

    /**
     * Why a machine stopped.
     */
    public enum Reason {
        /**
         * The instruction about to run is one somebody marked -- and, if the mark carried a
         * condition, the condition held.
         */
        BREAKPOINT,

        /**
         * The instruction that just ran read or wrote an address somebody marked.
         */
        WATCHPOINT,

        /**
         * One instruction was asked for, and it has been run.
         */
        STEP,

        /**
         * One frame was asked for, and it has been drawn.
         */
        FRAME,

        /**
         * Somebody pressed Break.
         */
        ASKED
    }

    /**
     * Where a machine stopped.
     *
     * @param reason  what stopped it.
     * @param pc      where the CPU is standing: the instruction it has <em>not</em> run yet.
     * @param access  which way a watched access went, or null for every other reason.
     * @param address the address a watched access landed on, or -1.
     * @param value   the byte read or written there, or -1.
     * @param by      the instruction that just ran, or -1. Not the same as {@code pc}, which by
     *                then has moved on to the next one -- and for a watchpoint this is the whole of
     *                what it is for, since "what wrote to $0770" is the question being asked.
     */
    public record Stop(Reason reason, int pc, Access access, int address, int value, int by) {
    }

    /**
     * What kind of thing the machine just did, for whoever is drawing a frame's worth of them.
     * <p>
     * Four groups rather than one address range per register, because what the groups answer are
     * four different questions. The <b>PPU</b> ones are where a raster effect lives: a $2005 or
     * $2006 write part way down the screen is a split, and which line it lands on is the whole of
     * what somebody is looking for. The <b>audio</b> ones are $4000-$401F, which is the sound chip
     * plus the two things that share the window with it -- the transfer at $4014 and the pads at
     * $4016. The <b>cartridge</b> ones are writes above $8000, which do not go to memory at all:
     * every one is a mapper register, so every one is a bank switch or an interrupt being armed.
     * And the two interrupts are where a frame is cut into pieces.
     * <p>
     * Reads and writes are separate constants rather than a flag beside the kind, because that is
     * how they are asked for: recording reads means putting a hook on the line every instruction
     * fetch comes past, so it is a decision somebody makes rather than a filter applied afterwards.
     * A read below $2000 or above $401F is never one of these -- that is either work RAM or the
     * program being fetched, and neither is a thing the machine <em>did</em>.
     */
    public enum EventKind {
        PPU_READ("PPU read"),
        PPU_WRITE("PPU write"),
        AUDIO_READ("audio read"),
        AUDIO_WRITE("audio write"),
        CARTRIDGE_WRITE("mapper write"),
        NMI("NMI"),
        IRQ("IRQ");

        private final String label;

        EventKind(final String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /**
         * Whether this is the machine being asked something rather than being told something. The
         * two interrupts are neither, and answer false.
         */
        public boolean isRead() {
            return this == PPU_READ || this == AUDIO_READ;
        }
    }

    /**
     * One of them, in the shape something that has collected a frame's worth wants to read.
     * <p>
     * Not what {@link EventSink} is handed, which is the same six values as primitives: this is the
     * materialised form, made once by whoever is about to show them rather than thousands of times
     * a second by the machine making them.
     *
     * @param kind     which of the seven this is.
     * @param address  the address touched, or the vector for an interrupt.
     * @param value    the byte read or written, or -1 for an interrupt.
     * @param scanline where the beam was.
     * @param dot      the dot within that line, good to within two.
     * @param pc       where the program counter stood, which is past the instruction that made the
     *                 access. See {@link EventSink#onEvent}.
     */
    public record Event(
            EventKind kind, int address, int value, int scanline, int dot, int pc) {
    }

    /**
     * Told about each of those as it happens, on the thread clocking the machine.
     * <p>
     * Primitives rather than a record, which is the same trade {@link
     * com.github.dimiro1.mynes.MemoryWriteListener} makes: a game with a busy music driver and a
     * raster split makes a few hundred of these a frame, and a vblank wait with reads switched on
     * makes thousands, none of which anybody wants allocated. Whatever is collecting them decides
     * what shape to keep them in.
     */
    @FunctionalInterface
    public interface EventSink {
        /**
         * @param kind     which of the seven this is.
         * @param address  the address touched, or the vector for an interrupt.
         * @param value    the byte read or written, or -1 for an interrupt.
         * @param scanline where the beam was, which is the point of recording any of this.
         * @param dot      the dot within that line. Good to within two dots and no better: the
         *                 machine is clocked a CPU cycle at a time and the PPU runs three dots to
         *                 one, so this is the last of the three the cycle covered.
         * @param pc       where the program counter stood. <b>Past</b> the instruction that made
         *                 the access, by however long that instruction was, since the operand bytes
         *                 have already been fetched -- so it is an orientation rather than an
         *                 answer. {@code watch} is what answers exactly, and reports {@code
         *                 writtenBy}. For an interrupt it is the address being returned to.
         */
        void onEvent(EventKind kind, int address, int value, int scanline, int dot, int pc);
    }

    /**
     * How far back the disassembly view can look.
     * <p>
     * A power of two, so the ring wraps with a mask rather than a division.
     */
    private static final int TRAIL = 256;

    /**
     * Somewhere to stop, looked up once per instruction and once per bus access.
     * <p>
     * Flat arrays rather than the collections below, which are the same information in the shape a
     * listing wants. {@code Set<Integer>.contains} boxes its argument, and at a million and a half
     * instructions a second that is a million and a half allocations a second -- paid by exactly the
     * person who is trying to watch the machine closely. 64KB each is the cheaper end of that trade,
     * and the reads and the writes are two arrays rather than one of pairs for the same reason: the
     * hot path is a single array load and a branch.
     */
    private final boolean[] breakAt = new boolean[0x10000];
    private final boolean[] watchReadAt = new boolean[0x10000];
    private final boolean[] watchWriteAt = new boolean[0x10000];

    private final Set<Integer> breakpoints = new TreeSet<>();

    /**
     * The conditional breakpoints only, which is nearly always none of them.
     * <p>
     * Beside {@link #breakpoints} rather than replacing it, so that everything which only wants to
     * know where the points are -- the gutter in the disassembly, the listing, the load-bearing
     * {@code breakAt} array above -- carries on asking one question and getting one answer. A map
     * lookup happens only once {@code breakAt} has already said yes, which is rare enough that
     * boxing the key there costs nothing anybody can measure.
     */
    private final Map<Integer, Condition> conditions = new TreeMap<>();

    private final Map<Integer, Access> watchpoints = new TreeMap<>();

    /**
     * The last {@link #TRAIL} instructions to have run, oldest first once it has wrapped.
     * <p>
     * A disassembly view needs lines above the current one, and they cannot be worked out by
     * disassembling backwards: on a variable length instruction set there is no way to tell where
     * the previous instruction started, only where one could have. These are the addresses that
     * really ran.
     */
    private final int[] trail = new int[TRAIL];
    private int trailNext;
    private int trailCount;

    /**
     * The machine being watched, kept so that the bus hooks can be put down and picked up again as
     * watchpoints come and go, and so that a condition has registers and memory to read. Null until
     * {@link #attach}.
     */
    private MMU memory;
    private CPU cpu;
    private PPU ppu;

    /**
     * Whoever is collecting a frame's worth of what the machine did, or null when nobody is.
     * <p>
     * Deliberately <b>not</b> part of {@link #isArmed()}. A breakpoint has to be checked between
     * instructions and so costs the driver its whole fast loop; this rides on hooks the bus already
     * has, so a machine being watched this way runs at full speed. Somebody who wants to see where
     * in the frame a game writes $2005 must not have to slow the game down to find out.
     */
    private EventSink eventSink;

    /**
     * Whether reads are being recorded as well as writes. Off unless asked for, because it is the
     * read hook that costs: every instruction the CPU fetches comes past it.
     */
    private boolean eventReads;

    private Stepping stepping = Stepping.NONE;
    private boolean haltAsked;

    /**
     * What a watched access left behind, for {@link #afterInstruction} to report once the
     * instruction doing it has finished. -1 when there is nothing pending.
     */
    private int hitAddress = -1;
    private int hitValue = -1;
    private Access hitAccess;

    // ============================================================================== being attached

    /**
     * Watches this machine.
     * <p>
     * Call with the machine stopped -- at power on, or from the thread that clocks it. The bus
     * hooks are only actually installed once there is a watchpoint to justify one, so attaching to a
     * machine nobody is watching costs it nothing at all.
     */
    public void attach(final NES nes) {
        memory = nes.getMemory();
        cpu = nes.getCPU();
        ppu = nes.getPPU();

        wireHooks();
    }

    // =================================================================== what the run loop asks

    /**
     * Whether the machine has to be clocked an instruction at a time.
     * <p>
     * Asked once a frame. When this is false the driver runs its ordinary loop and nothing here is
     * called again until the next frame, which is the point: a machine nobody is debugging runs
     * exactly as fast as it did before any of this existed.
     */
    public boolean isArmed() {
        return stepping != Stepping.NONE
                || haltAsked
                || !breakpoints.isEmpty()
                || !watchpoints.isEmpty();
    }

    /**
     * Whether a machine that is stopped should nonetheless be clocked, because a step was asked for.
     */
    public boolean isStepping() {
        return stepping != Stepping.NONE;
    }

    /**
     * Called after each instruction while armed.
     *
     * @param pc     where the CPU is now standing, which is the instruction about to run.
     * @param wasPC  where it was standing before, which is the instruction that just ran.
     * @return why to stop, or null to carry on.
     */
    public Stop afterInstruction(final int pc, final int wasPC) {
        trail[trailNext] = wasPC;
        trailNext = (trailNext + 1) % TRAIL;

        if (trailCount < TRAIL) {
            trailCount++;
        }

        // Before the breakpoint check, because the access has already happened and saying so is
        // more use than saying which instruction happens to be next.
        if (hitAddress >= 0) {
            var stop = new Stop(Reason.WATCHPOINT, pc, hitAccess, hitAddress, hitValue, wasPC);

            forgetHit();
            stepping = Stepping.NONE;

            return stop;
        }

        if (breakAt[pc] && conditionHolds(pc)) {
            stepping = Stepping.NONE;

            return new Stop(Reason.BREAKPOINT, pc, null, -1, -1, -1);
        }

        if (stepping == Stepping.INSTRUCTION) {
            stepping = Stepping.NONE;

            return new Stop(Reason.STEP, pc, null, -1, -1, wasPC);
        }

        if (haltAsked) {
            haltAsked = false;

            return new Stop(Reason.ASKED, pc, null, -1, -1, -1);
        }

        return null;
    }

    /**
     * Called when a watched frame finishes without anything having stopped it.
     *
     * @return why to stop, or null to carry on into the next frame.
     */
    public Stop afterFrame(final int pc) {
        if (stepping == Stepping.FRAME) {
            stepping = Stepping.NONE;

            return new Stop(Reason.FRAME, pc, null, -1, -1, -1);
        }

        return null;
    }

    /**
     * The write hook, called from {@link MMU#write} before the byte lands.
     * <p>
     * Only latches. Stopping here would leave the CPU half way through an instruction, with the
     * store neither done nor undone, and a save state taken from there would be of a machine that
     * never existed. {@link #afterInstruction} reports it a moment later, by which time the value
     * really is in memory and can be looked at.
     */
    public void onWrite(final int address, final int value) {
        if (watchWriteAt[address] && hitAddress < 0) {
            hitAddress = address;
            hitValue = value;
            hitAccess = Access.WRITE;
        }

        if (eventSink != null) {
            recordWrite(address, value);
        }
    }

    /**
     * The read hook, called from {@link MMU#read} once the byte is in hand.
     * <p>
     * Latches like the write hook, and for the same reason. What it latches is the byte the CPU
     * really got, which on half the address map is not what a later look would find: reading $2002
     * clears the flag it just reported, and reading $4016 clocks the shift register along.
     */
    public void onRead(final int address, final int value) {
        if (watchReadAt[address] && hitAddress < 0) {
            hitAddress = address;
            hitValue = value;
            hitAccess = Access.READ;
        }

        // The address test before the null check, unlike the write side, because this is the hook
        // every instruction fetch comes past and nearly all of them are outside the window.
        if (address >= 0x2000 && address < 0x4020 && eventSink != null && eventReads) {
            record(
                    address < 0x4000 ? EventKind.PPU_READ : EventKind.AUDIO_READ,
                    address,
                    value);
        }
    }

    /**
     * The interrupt hook, called from {@link CPU} on the cycle it picks a vector.
     */
    public void onInterrupt(final boolean nmi, final int pc) {
        if (eventSink != null) {
            eventSink.onEvent(
                    nmi ? EventKind.NMI : EventKind.IRQ,
                    nmi ? 0xFFFA : 0xFFFE,
                    -1,
                    ppu.getScanline(),
                    ppu.getDot(),
                    pc);
        }
    }

    // ============================================================================== being told

    /**
     * Lets the machine go, forgetting any step or break that had been asked for.
     */
    public void run() {
        stepping = Stepping.NONE;
        haltAsked = false;
        forgetHit();
    }

    /**
     * Stops the machine at the next instruction boundary.
     */
    public void halt() {
        haltAsked = true;
    }

    public void stepInstruction() {
        stepping = Stepping.INSTRUCTION;
        haltAsked = false;
    }

    public void stepFrame() {
        stepping = Stepping.FRAME;
        haltAsked = false;
    }

    // ============================================================================== the points

    public void addBreakpoint(final int pc) {
        addBreakpoint(pc, null);
    }

    /**
     * Stops the machine before the instruction at this address, on the passes where a condition
     * holds.
     *
     * @param condition what has to be true, or null for every pass. Replaces whatever condition the
     *                  address already carried, since one address is one breakpoint -- setting it
     *                  again is how a condition is changed and how a bare {@code break} takes one
     *                  off.
     */
    public void addBreakpoint(final int pc, final Condition condition) {
        var address = pc & 0xFFFF;

        breakpoints.add(address);
        breakAt[address] = true;

        if (condition == null) {
            conditions.remove(address);
        } else {
            conditions.put(address, condition);
        }
    }

    public void removeBreakpoint(final int pc) {
        breakpoints.remove(pc & 0xFFFF);
        conditions.remove(pc & 0xFFFF);
        breakAt[pc & 0xFFFF] = false;
    }

    /**
     * @return whether there is now a breakpoint there, so that a caller offering one gesture for
     *         both can say which way it went.
     */
    public boolean toggleBreakpoint(final int pc) {
        if (breakpoints.contains(pc & 0xFFFF)) {
            removeBreakpoint(pc);

            return false;
        }

        addBreakpoint(pc);

        return true;
    }

    /**
     * Stops the machine after an instruction writes to this address.
     */
    public void addWatchpoint(final int address) {
        addWatchpoint(address, Access.WRITE);
    }

    /**
     * Stops the machine after an instruction touches this address the named way.
     * <p>
     * The hook this needs is put down here rather than when the machine is attached, so that a
     * machine with no watchpoints on it never pays for the call -- and the read hook is put down
     * only for a read watchpoint, which matters more than the write one does: every instruction the
     * CPU fetches goes past it.
     */
    public void addWatchpoint(final int address, final Access on) {
        watchpoints.put(address & 0xFFFF, on);
        watchReadAt[address & 0xFFFF] = on.reads();
        watchWriteAt[address & 0xFFFF] = on.writes();

        wireHooks();
    }

    public void removeWatchpoint(final int address) {
        watchpoints.remove(address & 0xFFFF);
        watchReadAt[address & 0xFFFF] = false;
        watchWriteAt[address & 0xFFFF] = false;

        wireHooks();
    }

    public void toggleWatchpoint(final int address) {
        toggleWatchpoint(address, Access.WRITE);
    }

    /**
     * Puts a watchpoint down, or picks up whatever was already there.
     * <p>
     * Picks up rather than changes: a second gesture on the same address means "not this one after
     * all", even when it names a different way of watching. Changing which way an existing
     * watchpoint looks is {@link #addWatchpoint(int, Access)}, which is not a toggle.
     */
    public void toggleWatchpoint(final int address, final Access on) {
        if (watchpoints.containsKey(address & 0xFFFF)) {
            removeWatchpoint(address);

            return;
        }

        addWatchpoint(address, on);
    }

    // ============================================================================== the events

    /**
     * Records what the machine does to its hardware, or stops recording.
     * <p>
     * <b>This does not arm the machine.</b> Everything else here that watches costs the driver its
     * fast loop, because a breakpoint has to be looked at between instructions; this rides on hooks
     * the bus already carries, so a game being recorded runs at full speed. That is the whole
     * design: where in the frame a game writes $2005 is a question about a game that is playing
     * normally, and a gauge that changed the timing to answer it would be measuring itself.
     *
     * @param sink  who to tell, or null to stop.
     * @param reads whether to record reads as well as writes. <b>Off unless it is wanted.</b> The
     *              read hook sees every instruction fetch, so this is the one setting here that a
     *              machine can feel -- and what it buys is the $2002 and $4016 polls, which are
     *              worth seeing exactly when the question is why a game is waiting.
     */
    public void setEventSink(final EventSink sink, final boolean reads) {
        eventSink = sink;
        eventReads = reads;

        wireHooks();
    }

    /**
     * Clocks the machine with nothing watching, and puts the watching back afterwards.
     * <p>
     * For a caller that has to run the machine for a reason of its own rather than to play the
     * game -- redrawing the picture with a layer switched off, which cannot be done without
     * rendering a frame, since the switches take part where the pixel is composed and the
     * framebuffer keeps only what came out. Those frames are not the game doing anything, so
     * nothing here should think they were: a write watchpoint that latched during one would report
     * itself on the next real instruction, which is a stop nobody asked for and nothing to explain
     * it.
     * <p>
     * The hooks come off rather than the results being thrown away afterwards, because a pending
     * hit and a real one are the same field, and telling them apart after the fact means guessing.
     */
    public void unwatched(final Runnable work) {
        var reads = memory.readListener();
        var writes = memory.writeListener();
        var interrupts = cpu.interruptListener();

        memory.setReadListener(null);
        memory.setWriteListener(null);
        cpu.setInterruptListener(null);

        try {
            work.run();
        } finally {
            memory.setReadListener(reads);
            memory.setWriteListener(writes);
            cpu.setInterruptListener(interrupts);
        }
    }

    /**
     * Forgets every breakpoint and watchpoint. What a new cartridge deserves.
     * <p>
     * Not the event sink, which belongs to a window that is still open rather than to the cartridge
     * that has just been taken out.
     */
    public void clear() {
        breakpoints.forEach(pc -> breakAt[pc] = false);
        watchpoints.keySet().forEach(address -> {
            watchReadAt[address] = false;
            watchWriteAt[address] = false;
        });

        breakpoints.clear();
        conditions.clear();
        watchpoints.clear();

        run();
        wireHooks();
    }

    // ============================================================================== being read

    public Set<Integer> breakpoints() {
        return Collections.unmodifiableSet(breakpoints);
    }

    /**
     * The conditions, by the address they are on. Only the conditional breakpoints are in here; an
     * address in {@link #breakpoints()} and not in this stops on every pass.
     */
    public Map<Integer, Condition> conditions() {
        return Collections.unmodifiableMap(conditions);
    }

    /**
     * Every watched address, and which way each one is being watched.
     */
    public Map<Integer, Access> watchpoints() {
        return Collections.unmodifiableMap(watchpoints);
    }

    /**
     * The instructions that have run, oldest first.
     * <p>
     * Only the ones that ran while something was armed: the trail is written by
     * {@link #afterInstruction}, which a machine running freely never calls. So this is the history
     * since the debugger last had a reason to watch, which is the history somebody looking at it
     * has any use for anyway.
     */
    public int[] trail() {
        var out = new int[trailCount];
        var from = trailCount < TRAIL ? 0 : trailNext;

        for (var i = 0; i < trailCount; i++) {
            out[i] = trail[(from + i) % TRAIL];
        }

        return out;
    }

    // ================================================================================== internals

    /**
     * Whether the breakpoint at this address has anything to say about this pass.
     * <p>
     * Asked only once {@code breakAt} has said there is a breakpoint here at all, so the map lookup
     * and the {@link CPU#getState()} record it allocates are paid for on a path that runs when
     * somebody's breakpoint has already been reached rather than on every instruction.
     */
    private boolean conditionHolds(final int pc) {
        if (conditions.isEmpty()) {
            return true;
        }

        var condition = conditions.get(pc);

        return condition == null || condition.holds(cpu.getState(), memory::peek);
    }

    /**
     * Puts the three hooks down or picks them up, according to whether anything is still watching.
     * <p>
     * Recomputed from the watchpoints and the sink rather than counted, so that a machine attached
     * to after a point was set gets its hooks and one whose last point has gone loses them, without
     * any caller having to remember which case it is in. Two things want each hook now and neither
     * knows about the other, which is exactly the case counting would get wrong.
     */
    private void wireHooks() {
        if (memory == null) {
            return;
        }

        var reads = eventSink != null && eventReads;
        var writes = eventSink != null;

        for (var access : watchpoints.values()) {
            reads |= access.reads();
            writes |= access.writes();
        }

        memory.setReadListener(reads ? this::onRead : null);
        memory.setWriteListener(writes ? this::onWrite : null);
        cpu.setInterruptListener(eventSink == null ? null : this::onInterrupt);
    }

    /**
     * Which of the three write kinds an address is, or none at all.
     * <p>
     * Work RAM and cartridge RAM are deliberately not among them. A game writes to those thousands
     * of times a frame and none of it is the machine <em>doing</em> anything -- it is the game
     * thinking, which is what a watchpoint and the memory view are for.
     */
    private void recordWrite(final int address, final int value) {
        if (address < 0x2000) {
            return;
        }

        if (address < 0x4000) {
            record(EventKind.PPU_WRITE, address, value);
        } else if (address < 0x4020) {
            record(EventKind.AUDIO_WRITE, address, value);
        } else if (address >= 0x8000) {
            record(EventKind.CARTRIDGE_WRITE, address, value);
        }
    }

    private void record(final EventKind kind, final int address, final int value) {
        eventSink.onEvent(kind, address, value, ppu.getScanline(), ppu.getDot(), cpu.getPC());
    }

    private void forgetHit() {
        hitAddress = -1;
        hitValue = -1;
        hitAccess = null;
    }
}
