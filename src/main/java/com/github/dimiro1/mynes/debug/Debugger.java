package com.github.dimiro1.mynes.debug;

import com.github.dimiro1.mynes.MMU;
import com.github.dimiro1.mynes.NES;

import java.util.Collections;
import java.util.Set;
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
     * Why a machine stopped.
     */
    public enum Reason {
        /**
         * The instruction about to run is one somebody marked.
         */
        BREAKPOINT,

        /**
         * The instruction that just ran wrote to an address somebody marked.
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
     * @param reason    what stopped it.
     * @param pc        where the CPU is standing: the instruction it has <em>not</em> run yet.
     * @param address   the address a watched write landed on, or -1.
     * @param value     the byte written there, or -1.
     * @param writtenBy the instruction that wrote it, or -1. Not the same as {@code pc}, which by
     *                  then has moved on to the next one -- and this is the whole of what a
     *                  watchpoint is for, since "what wrote to $0770" is the question being asked.
     */
    public record Stop(Reason reason, int pc, int address, int value, int writtenBy) {
    }

    /**
     * How far back the disassembly view can look.
     * <p>
     * A power of two, so the ring wraps with a mask rather than a division.
     */
    private static final int TRAIL = 256;

    /**
     * Somewhere to stop, looked up once per instruction and once per write.
     * <p>
     * Flat arrays rather than the sets below, which are the same information in the shape a listing
     * wants. {@code Set<Integer>.contains} boxes its argument, and at a million and a half
     * instructions a second that is a million and a half allocations a second -- paid by exactly the
     * person who is trying to watch the machine closely. 64KB each is the cheaper end of that trade.
     */
    private final boolean[] breakAt = new boolean[0x10000];
    private final boolean[] watchAt = new boolean[0x10000];

    private final Set<Integer> breakpoints = new TreeSet<>();
    private final Set<Integer> watchpoints = new TreeSet<>();

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
     * The machine being watched, kept so that the write hook can be put down and picked up again as
     * watchpoints come and go. Null until {@link #attach}.
     */
    private MMU memory;

    private Stepping stepping = Stepping.NONE;
    private boolean haltAsked;

    /**
     * What a watched write left behind, for {@link #afterInstruction} to report once the
     * instruction doing the writing has finished. -1 when there is nothing pending.
     */
    private int hitAddress = -1;
    private int hitValue = -1;

    // ============================================================================== being attached

    /**
     * Watches this machine.
     * <p>
     * Call with the machine stopped -- at power on, or from the thread that clocks it. The write
     * hook is only actually installed once there is a watchpoint to justify it, so attaching to a
     * machine nobody is watching costs it nothing at all.
     */
    public void attach(final NES nes) {
        memory = nes.getMemory();

        if (!watchpoints.isEmpty()) {
            memory.setWriteListener(this::onWrite);
        }
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

        // Before the breakpoint check, because the write has already happened and saying so is more
        // use than saying which instruction happens to be next.
        if (hitAddress >= 0) {
            var stop = new Stop(Reason.WATCHPOINT, pc, hitAddress, hitValue, wasPC);

            hitAddress = -1;
            hitValue = -1;
            stepping = Stepping.NONE;

            return stop;
        }

        if (breakAt[pc]) {
            stepping = Stepping.NONE;

            return new Stop(Reason.BREAKPOINT, pc, -1, -1, -1);
        }

        if (stepping == Stepping.INSTRUCTION) {
            stepping = Stepping.NONE;

            return new Stop(Reason.STEP, pc, -1, -1, wasPC);
        }

        if (haltAsked) {
            haltAsked = false;

            return new Stop(Reason.ASKED, pc, -1, -1, -1);
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

            return new Stop(Reason.FRAME, pc, -1, -1, -1);
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
        if (watchAt[address] && hitAddress < 0) {
            hitAddress = address;
            hitValue = value;
        }
    }

    // ============================================================================== being told

    /**
     * Lets the machine go, forgetting any step or break that had been asked for.
     */
    public void run() {
        stepping = Stepping.NONE;
        haltAsked = false;
        hitAddress = -1;
        hitValue = -1;
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
        breakpoints.add(pc & 0xFFFF);
        breakAt[pc & 0xFFFF] = true;
    }

    public void removeBreakpoint(final int pc) {
        breakpoints.remove(pc & 0xFFFF);
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
     * <p>
     * The hook this needs is put down here rather than when the machine is attached, so that a
     * machine with no watchpoints on it never pays for the call.
     */
    public void addWatchpoint(final int address) {
        watchpoints.add(address & 0xFFFF);
        watchAt[address & 0xFFFF] = true;

        if (memory != null) {
            memory.setWriteListener(this::onWrite);
        }
    }

    public void removeWatchpoint(final int address) {
        watchpoints.remove(address & 0xFFFF);
        watchAt[address & 0xFFFF] = false;

        if (memory != null && watchpoints.isEmpty()) {
            memory.setWriteListener(null);
        }
    }

    public void toggleWatchpoint(final int address) {
        if (watchpoints.contains(address & 0xFFFF)) {
            removeWatchpoint(address);

            return;
        }

        addWatchpoint(address);
    }

    /**
     * Forgets every breakpoint and watchpoint. What a new cartridge deserves.
     */
    public void clear() {
        breakpoints.forEach(pc -> breakAt[pc] = false);
        watchpoints.forEach(address -> watchAt[address] = false);
        breakpoints.clear();
        watchpoints.clear();

        run();

        if (memory != null) {
            memory.setWriteListener(null);
        }
    }

    // ============================================================================== being read

    public Set<Integer> breakpoints() {
        return Collections.unmodifiableSet(breakpoints);
    }

    public Set<Integer> watchpoints() {
        return Collections.unmodifiableSet(watchpoints);
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
}
