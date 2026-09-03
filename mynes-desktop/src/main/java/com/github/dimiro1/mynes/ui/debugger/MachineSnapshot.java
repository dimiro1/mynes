package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.CPU;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;

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
 * @param v                      the PPU's current VRAM address, which is where the beam is reading.
 * @param t                      the temporary one, which is what the next frame will start from.
 * @param fineX                  the fine horizontal scroll, the three bits $2005 keeps outside t.
 * @param writeLatch             whether the next $2005/$2006 write is the second of a pair.
 * @param backgroundPatternTable $0000 or $1000, by $2000 bit 4.
 * @param spritePatternTable     $0000 or $1000, by $2000 bit 3 -- ignored for tall sprites.
 * @param spriteHeight           8 or 16.
 */
record MachineSnapshot(
        CPU.State cpu,
        long frame,
        int scanline,
        int dot,
        boolean renderingEnabled,
        int v,
        int t,
        int fineX,
        boolean writeLatch,
        int backgroundPatternTable,
        int spritePatternTable,
        int spriteHeight,
        int[] bus,
        int[] trail) {

    /**
     * Where cartridge RAM sits in the CPU's address space.
     */
    private static final int CART_RAM = 0x6000;
    private static final int CART_RAM_SIZE = 0x2000;

    /**
     * Where the stack lives. The 6502 has one page of it and no way to move it.
     */
    private static final int STACK_PAGE = 0x0100;

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
        var ppu = nes.getPPU();
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

        return new MachineSnapshot(
                nes.getCPU().getState(),
                ppu.getFrame(),
                ppu.getScanline(),
                ppu.getDot(),
                ppu.isRenderingEnabled(),
                ppu.getV(),
                ppu.getT(),
                ppu.getFineX(),
                ppu.isWriteLatchSet(),
                ppu.getBackgroundPatternTable(),
                ppu.getSpritePatternTable(),
                ppu.getSpriteHeight(),
                bus,
                debugger.trail());
    }

    int read(final int address) {
        return bus[address & 0xFFFF];
    }

    /**
     * The address of the byte on top of the stack, or $0200 when there is nothing on it.
     * <p>
     * The pointer names the next free slot rather than the last used one, so the top is one above
     * it -- and an empty stack's top would be $0200, which is the first address that is not stack.
     */
    int stackTop() {
        return STACK_PAGE + cpu.sp() + 1;
    }

    /**
     * What is on the stack, top first, which is the order it will come off in.
     */
    int[] stack() {
        var depth = 0xFF - cpu.sp();
        var out = new int[depth];

        for (var i = 0; i < depth; i++) {
            out[i] = read(stackTop() + i);
        }

        return out;
    }

    /**
     * The processor flags the way nestest's log spells them: set ones in capitals, clear ones not,
     * which reads at a glance where eight ones and zeros do not.
     */
    String flags() {
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
