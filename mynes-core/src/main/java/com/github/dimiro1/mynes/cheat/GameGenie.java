package com.github.dimiro1.mynes.cheat;

import com.github.dimiro1.mynes.MMU;
import com.github.dimiro1.mynes.NES;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The device between the cartridge and the console.
 * <p>
 * A Game Genie is not a patcher and this is the class where the difference lives. It plugs into the
 * cartridge slot and the cartridge plugs into it, so it sees the CPU's address bus and the byte the
 * cartridge answers with, and when it recognises the address it drives its own byte onto the data
 * pins instead. The cartridge is never modified -- there is nothing to modify, the ROM is a mask in
 * somebody's silicon -- and neither is the image on disk. Which is why {@code cart.sha256} is the
 * same with codes in as without, and why nothing here is anywhere near {@code Cart.load}.
 * <p>
 * That also means a code addresses <em>the CPU bus</em> rather than an offset into a file. $8000 is
 * whichever bank the mapper has switched in at the moment of the read, which is the whole reason
 * {@link GameGenieCode#compare()} exists.
 * <p>
 * Like {@code Debugger}, this holds nothing of the console's and is not reachable from {@link NES},
 * so the reflective walk in {@code SaveStateCompletenessTests} never sees it: a code belongs to
 * whoever is playing rather than to the machine, and a state file that put one back would be
 * restoring the cheat rather than the game. The hook in {@link MMU} is put down when the first code
 * arrives and taken up when the last one goes, so a machine nobody is cheating on holds null there
 * and pays nothing at all for this class existing.
 * <p>
 * Everything here is called on whichever thread is clocking the machine and on no other, the same
 * rule the console itself keeps, which is why there is no synchronisation below.
 * <p>
 * <strong>One code per address.</strong> The real device cannot hold two codes for one address with
 * different compare bytes -- it answers the bus in the time it has and there is no second look -- so
 * {@link #add} replaces whatever was there rather than stacking. Deliberately unlike
 * {@code IPSPatch.applyTo}, where two records may overlap and the later one wins: that is a file
 * format being applied once, and this is a wire.
 * <p>
 * The cartridge held three codes. This holds as many as anybody types, which is what every other
 * emulator does, and the only thing lost by it is the frustration of the original.
 */
public final class GameGenie {

    /**
     * The codes, in the order they went in. Small enough that a list is the whole story: three is what
     * the hardware held and ten is a lot, so {@link #substitute} scans it.
     * <p>
     * Deliberately not the flat 64KB table {@code Debugger} keeps for its breakpoints. That one is
     * there because {@code Set<Integer>.contains} boxes its argument a million and a half times a
     * second; this compares ints against a handful of ints and allocates nothing, and a table indexed
     * by address would trade that for a cache miss on the hottest line in the emulator.
     */
    private final List<GameGenieCode> codes = new ArrayList<>();

    /**
     * The machine being cheated on, kept so that the read hook can be put down and picked up again as
     * codes come and go. Null until {@link #attach}.
     */
    private MMU memory;

    /**
     * Watches this machine's cartridge port.
     * <p>
     * Call with the machine stopped -- at power on, or from the thread that clocks it. The hook is
     * only actually installed once there is a code to justify it, so attaching to a machine nobody is
     * cheating on costs it nothing.
     */
    public void attach(final NES nes) {
        memory = nes.getMemory();

        if (!codes.isEmpty()) {
            memory.setGameGenie(this);
        }
    }

    /**
     * Puts a code in, replacing any code already firing at the same address.
     *
     * @return what was replaced, or null when the address was free.
     */
    public GameGenieCode add(final GameGenieCode code) {
        var replaced = removeAt(code.address());

        codes.add(code);

        if (memory != null) {
            memory.setGameGenie(this);
        }

        return replaced;
    }

    /**
     * Takes a code out.
     *
     * @return whether it was there to take out.
     */
    public boolean remove(final GameGenieCode code) {
        var removed = codes.remove(code);

        if (memory != null && codes.isEmpty()) {
            memory.setGameGenie(null);
        }

        return removed;
    }

    /**
     * Unplugs the lot. What a new cartridge deserves: a code is written for one game and means
     * something else entirely in the next one.
     */
    public void clear() {
        codes.clear();

        if (memory != null) {
            memory.setGameGenie(null);
        }
    }

    public List<GameGenieCode> codes() {
        return Collections.unmodifiableList(codes);
    }

    public boolean isEmpty() {
        return codes.isEmpty();
    }

    /**
     * What the console reads, given what the cartridge answered with.
     * <p>
     * Called from {@link MMU} for every read of $8000-$FFFF once there is a code in -- which is every
     * instruction the CPU fetches, so it does the least it can: no allocation, no logging, and nothing
     * remembered. That last one is not only for speed. {@code MMU.peek} calls this too, and a debugger
     * taking a snapshot peeks all 64K at once, so anything counted here would count 32768 phantom
     * reads every time somebody opened the window.
     *
     * @param address          on the CPU bus, already masked to sixteen bits.
     * @param fromTheCartridge what the mapper answered with.
     */
    public int substitute(final int address, final int fromTheCartridge) {
        for (var i = 0; i < codes.size(); i++) {
            var code = codes.get(i);

            if (code.address() == address) {
                return code.substitute(fromTheCartridge);
            }
        }

        return fromTheCartridge;
    }

    private GameGenieCode removeAt(final int address) {
        for (var i = 0; i < codes.size(); i++) {
            if (codes.get(i).address() == address) {
                return codes.remove(i);
            }
        }

        return null;
    }
}
