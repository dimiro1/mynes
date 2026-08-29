package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.CPU;
import com.github.dimiro1.mynes.MMU;
import com.github.dimiro1.mynes.NES;

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

        wireBusHooks();
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

        wireBusHooks();
    }

    public void removeWatchpoint(final int address) {
        watchpoints.remove(address & 0xFFFF);
        watchReadAt[address & 0xFFFF] = false;
        watchWriteAt[address & 0xFFFF] = false;

        wireBusHooks();
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

    /**
     * Forgets every breakpoint and watchpoint. What a new cartridge deserves.
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
        wireBusHooks();
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
     * Puts the bus hooks down or picks them up, according to whether anything is still watching.
     * <p>
     * Recomputed from the watchpoints rather than counted, so that a machine attached to after a
     * point was set gets its hooks and one whose last point has gone loses them, without either
     * caller having to remember which case it is in.
     */
    private void wireBusHooks() {
        if (memory == null) {
            return;
        }

        var reads = false;
        var writes = false;

        for (var access : watchpoints.values()) {
            reads |= access.reads();
            writes |= access.writes();
        }

        memory.setReadListener(reads ? this::onRead : null);
        memory.setWriteListener(writes ? this::onWrite : null);
    }

    private void forgetHit() {
        hitAddress = -1;
        hitValue = -1;
        hitAccess = null;
    }
}
