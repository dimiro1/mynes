package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.CPU;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.Readout;

/**
 * The machine as it stood when it stopped: everything the window paints, taken in one go.
 * <p>
 * Here rather than in {@code mynes.debug} because it exists to solve a problem the headless side
 * does not have. Swing repaints whenever it likes -- an expose event, a scrollbar drag, a table
 * asking its model for a cell -- and any of those can land after the machine has been let go again.
 * A panel that read the machine from its renderer would be reading a running machine, and what came
 * back would not be a slightly stale picture but a mixture of two moments that never coexisted.
 * <p>
 * So the machine is read exactly once, on the event dispatch thread, from inside the stop callback,
 * with the machine halted and a happens-before edge behind it. Everything painted afterwards is
 * painted from this.
 *
 * <p>
 * The scalars are a {@link Readout}, which is the same record the dashboard is handed four times a
 * second -- the machine's registers are the machine's registers, and two shapes for them would be
 * two places to add the next one to. What a snapshot adds is what only a stopped machine can
 * afford: the whole of the address space, and the trail of where the processor has been.
 *
 * @param machine the registers, taken with everything else.
 * @param bus     64K of the CPU's address space, read through {@code peek}.
 * @param trail   where the processor has been, newest last.
 */
record MachineSnapshot(Readout machine, int[] bus, int[] trail) {

    /**
     * Where cartridge RAM sits in the CPU's address space.
     */
    private static final int CART_RAM = 0x6000;
    private static final int CART_RAM_SIZE = 0x2000;

    /**
     * Reads the whole machine.
     * <p>
     * 64K of {@code peek} is a couple of hundred microseconds and it buys a memory view that needs
     * to touch the machine no further. {@code peek} rather than {@code read} throughout: a debugger
     * that filled its hex view through real reads would clear $2002, clock the controller ports, and
     * on an MMC3 cartridge drive the scanline counter from the debugger rather than from the game.
     */
    static MachineSnapshot of(final NES nes, final Debugger debugger) {
        var memory = nes.getMemory();
        var bus = new int[0x10000];

        for (var address = 0; address < bus.length; address++) {
            bus[address] = memory.peek(address);
        }

        // Cartridge RAM taken from the chip rather than through the bus, which is the one place
        // peek gives the wrong answer: MMC1 and MMC3 read back zero at $6000 when the game has
        // switched the chip off, and switching it off around anything risky is exactly what a
        // battery board's enable line is for. A hex view full of zeros over the save RAM would be
        // the first bug reported against this window.
        var cartRAM = nes.getBus().getMapper().prgRAM();

        for (var i = 0; i < Math.min(cartRAM.length, CART_RAM_SIZE); i++) {
            bus[CART_RAM + i] = cartRAM[i] & 0xFF;
        }

        return new MachineSnapshot(Readout.of(nes), bus, debugger.trail());
    }

    // The registers, so that every panel here goes on reading a snapshot as one thing rather than
    // reaching through it. Nothing else needs to know the scalars came in together with the memory.

    CPU.State cpu() {
        return machine.cpu();
    }

    long frame() {
        return machine.frame();
    }

    int stackTop() {
        return machine.stackTop();
    }

    String flags() {
        return machine.flags();
    }

    int read(final int address) {
        return bus[address & 0xFFFF];
    }

    /**
     * What is on the stack, top first, which is the order it will come off in.
     */
    int[] stack() {
        var depth = 0xFF - cpu().sp();
        var out = new int[depth];

        for (var i = 0; i < depth; i++) {
            out[i] = read(stackTop() + i);
        }

        return out;
    }

}
