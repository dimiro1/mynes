package com.github.dimiro1.mynes;

public class NES {
    private final BUS bus;

    /**
     * Kept rather than only read from, because things done to a running machine need to know which
     * cartridge is in it: a save state refuses to load into the wrong one, and a battery file is
     * named after it.
     */
    private final Cart cart;

    public NES(final Cart cart) {
        Controller controller1 = new StandardController();
        Controller controller2 = new StandardController();
        this.cart = cart;
        bus = new BUS(cart.mapper(), controller1, controller2);
        bus.initialize();
    }

    public Cart getCart() {
        return cart;
    }

    public CPU getCPU() {
        return bus.getCPU();
    }

    public PPU getPPU() {
        return bus.getPPU();
    }

    public APU getAPU() {
        return bus.getAPU();
    }

    public MMU getMemory() {
        return bus.getMMU();
    }

    public Controller getController1() {
        return bus.getController1();
    }

    public Controller getController2() {
        return bus.getController2();
    }

    public BUS getBus() {
        return bus;
    }

    /**
     * The console's reset button.
     * <p>
     * The CPU is sent through its reset vector and the PPU's control registers are cleared, while
     * every kind of memory -- work RAM, VRAM, OAM, the palettes -- keeps what it held. That
     * survival is the point of the button: games lean on it, from "hold Reset while switching
     * off" save rituals to warm boot detection.
     */
    public void reset() {
        bus.triggerRST();
    }

    /**
     * Advances the whole machine by one CPU cycle.
     * <p>
     * On NTSC the PPU clock is exactly three times the CPU clock. The three dots happen before
     * the CPU cycle rather than after it, so a flag the PPU raises on one of them is already
     * visible to a register read in that cycle.
     * <p>
     * The odd looking part is the /NMI sample sitting one dot in. It belongs to the CPU cycle
     * that ran at the end of the <em>previous</em> call: the 6502 reads /NMI a little after it
     * finishes a cycle's bus access, roughly one dot later at this clock ratio, and that one dot
     * is the whole reason the PPU's NMI suppression window is two dots wide rather than three.
     * Reading $2002 in the cycle whose work happened at dot <i>d</i> hides an NMI raised at dots
     * <i>d</i> or <i>d-1</i>, but not one raised at <i>d-2</i>, because that one had already been
     * sampled.
     * <p>
     * The APU runs at the CPU's own clock and goes immediately before it, for the same reason the
     * dots do: a $4015 read has to see the interrupt flag the frame counter raised in the cycle
     * doing the reading. It is clocked here rather than from the CPU because it keeps running
     * through an OAM DMA transfer, which the CPU spends held off the bus.
     *
     * @see CPU#sampleNMI()
     */
    public void tick() {
        var ppu = bus.getPPU();
        var cpu = bus.getCPU();
        var apu = bus.getAPU();

        ppu.tick();
        cpu.sampleNMI();
        ppu.tick();
        ppu.tick();

        apu.tick();
        cpu.tick();
    }

    /**
     * Advances the machine until the CPU is between instructions.
     * <p>
     * Driven from here rather than from {@link CPU#step()} so that the PPU keeps running: a step
     * that only clocked the CPU would leave the PPU frozen, and an OAM DMA transfer would hold
     * the picture still for five hundred cycles.
     */
    public void step() {
        do {
            tick();
        } while (!bus.getCPU().isAtInstructionBoundary());
    }
}
