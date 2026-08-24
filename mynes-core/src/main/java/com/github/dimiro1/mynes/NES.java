package com.github.dimiro1.mynes;

public class NES {
    private final BUS bus;

    /**
     * Kept rather than only read from, because things done to a running machine need to know which
     * cartridge is in it: a save state refuses to load into the wrong one, and a battery file is
     * named after it.
     */
    private final Cart cart;

    /**
     * Which console this is. Handed to the chips at construction and never changed afterwards: a
     * machine cannot be rewired while it runs, so switching regions means building a new one, which
     * is what a front end's Region menu does.
     */
    private final Region region;

    /**
     * A machine for this cartridge, of whichever kind its header asks for.
     */
    public NES(final Cart cart) {
        this(cart, cart.region());
    }

    /**
     * The same, for a region chosen by hand. Most cartridges do not say which machine they were
     * made for -- see {@link Cart.Timing#UNSTATED} -- so somebody has to be able to.
     */
    public NES(final Cart cart, final Region region) {
        Controller controller1 = new StandardController();
        Controller controller2 = new StandardController();
        this.cart = cart;
        this.region = region;
        bus = new BUS(cart.mapper(), controller1, controller2, region);
        bus.initialize();
    }

    public Cart getCart() {
        return cart;
    }

    public Region getRegion() {
        return region;
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
     * On NTSC the PPU clock is exactly three times the CPU clock, so a cycle is three dots. On PAL
     * it is 3.2 -- sixteen dots to five CPU cycles -- so a cycle is three dots four times out of
     * five and four the fifth time. {@link PPU#beginCPUCycle()} is what counts that out, and the
     * long cycle's extra dot goes on the <em>end</em>, after the /NMI sample, so that everything
     * below is true of both machines. Where in the five it falls is a convention rather than a
     * measurement: nothing documents the 2C07's phase relative to the 2A07, and no test ROM asks.
     * <p>
     * The dots happen before the CPU cycle rather than after it, so a flag the PPU raises on one of
     * them is already visible to a register read in that cycle.
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
     * <p>
     * It is the one chip the {@link Overclock} hack holds still. A cycle the PPU spends on a line it
     * is running again is a cycle the game gets and the sound does not: the APU keeps its parity,
     * which two chips have to agree on, and counts nothing else -- so an overclocked frame is longer
     * for the program and exactly as long as a hardware one for the music. {@link APU#idle()} has
     * the whole of why. Asked once per CPU cycle, which on a machine nobody is overclocking is one
     * boolean read.
     *
     * @see CPU#sampleNMI()
     */
    public void tick() {
        var ppu = bus.getPPU();
        var cpu = bus.getCPU();
        var apu = bus.getAPU();

        var dots = ppu.beginCPUCycle();

        ppu.tick();
        cpu.sampleNMI();

        for (var dot = 1; dot < dots; dot++) {
            ppu.tick();
        }

        if (ppu.isOnExtraLine()) {
            apu.idle();
        } else {
            apu.tick();
        }

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
