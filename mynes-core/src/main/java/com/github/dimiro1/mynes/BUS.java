package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.state.StateIO;

/**
 * The system bus that connects all NES components.
 * <p>
 * The Bus acts as the central communication hub between the CPU, PPU, and memory.
 * It handles:
 * <ul>
 *   <li>Memory access routing through the MMU</li>
 *   <li>Interrupt signaling from PPU/Mapper to CPU</li>
 *   <li>Component lifecycle coordination</li>
 * </ul>
 * <p>
 */
public class BUS implements CPUBus, PPUBus {
    private CPU cpu;
    private PPU ppu;
    private APU apu;
    private MMU mmu;
    private final Mapper mapper;
    private final Controller controller1;
    private final Controller controller2;

    /**
     * Which console this is, for the two chips whose timing depends on it.
     */
    private final Region region;

    // The three devices that can pull /IRQ low, each holding its own end of the wire. The CPU sees
    // one line, so what it is told is the OR of these; see updateIRQLine().
    private boolean mapperIRQ;
    private boolean apuFrameIRQ;
    private boolean dmcIRQ;

    /**
     * Creates a new Bus with the specified components.
     *
     * @param mapper      the cartridge mapper
     * @param controller1 the first controller
     * @param controller2 the second controller
     */
    public BUS(final Mapper mapper, final Controller controller1, final Controller controller2) {
        this(mapper, controller1, controller2, Region.NTSC);
    }

    /**
     * The same, on a console of a particular kind.
     *
     * @param mapper      the cartridge mapper
     * @param controller1 the first controller
     * @param controller2 the second controller
     * @param region      which console this is
     */
    public BUS(
            final Mapper mapper,
            final Controller controller1,
            final Controller controller2,
            final Region region) {
        this.mapper = mapper;
        this.controller1 = controller1;
        this.controller2 = controller2;
        this.region = region;
    }

    /**
     * Initializes all components connected to the bus.
     * This must be called after construction to wire up all dependencies.
     */
    public void initialize() {
        this.ppu = new PPU(this, mapper, region);
        this.apu = new APU(this::setAPUFrameIRQ, this::setDMCIRQ, region);
        this.mmu = new MMU(ppu, apu, mapper, controller1, controller2);
        this.cpu = new CPU(this);

        // Last, because there is nothing to interrupt until the CPU exists.
        mapper.setIRQHandler(this::setMapperIRQ);
    }

    /**
     * Reads a byte from memory at the specified address.
     *
     * @param address the memory address to read from
     * @return the byte value at that address
     */
    @Override
    public int read(final int address) {
        return mmu.read(address);
    }

    /**
     * Writes a byte to memory at the specified address.
     *
     * @param address the memory address to write to
     * @param data    the byte value to write
     */
    @Override
    public void write(final int address, final int data) {
        mmu.write(address, data);
    }

    /**
     * Reads a byte without the side effects a real read would have.
     *
     * @param address the memory address to read from
     * @return the byte value at that address
     */
    @Override
    public int peek(final int address) {
        return mmu.peek(address);
    }

    /**
     * Drives the /NMI line the PPU shares with the CPU.
     * <p>
     * Null guarded because {@link #initialize()} builds the PPU first, and the PPU settles the
     * line as part of its own construction; there is no CPU to tell yet, and a CPU that has just
     * been built sees the line released anyway.
     *
     * @param level true while the line is asserted.
     */
    @Override
    public void setNMILine(final boolean level) {
        if (cpu != null) {
            cpu.setNMILine(level);
        }
    }

    /**
     * Drives the cartridge's end of the shared Interrupt Request (IRQ) line.
     *
     * @param asserted true while the mapper is pulling the line low.
     */
    public void setMapperIRQ(final boolean asserted) {
        mapperIRQ = asserted;
        updateIRQLine();
    }

    /**
     * Drives the APU frame counter's end of the shared Interrupt Request (IRQ) line.
     *
     * @param asserted true while the frame interrupt flag is set and not inhibited.
     */
    public void setAPUFrameIRQ(final boolean asserted) {
        apuFrameIRQ = asserted;
        updateIRQLine();
    }

    /**
     * Drives the DMC's end of the shared Interrupt Request (IRQ) line.
     *
     * @param asserted true while the DMC has finished a sample with its interrupt enabled.
     */
    public void setDMCIRQ(final boolean asserted) {
        dmcIRQ = asserted;
        updateIRQLine();
    }

    /**
     * Tells the CPU what the shared /IRQ line reads, which is the OR of everything holding it.
     * <p>
     * /IRQ is one open collector wire with three devices on it here -- the cartridge, the APU's
     * frame counter and its DMC -- and any one of them pulling it low is enough. The CPU sees a
     * single level and cannot tell them apart, so the bus keeps a bit per source and recomputes
     * the level whenever one of them changes. Without that, a mapper releasing its interrupt would
     * also silence an APU one that is still waiting to be acknowledged.
     * <p>
     * Null guarded like {@link #setNMILine(boolean)}: a mapper handed the line by
     * {@link #initialize()} keeps it for as long as the cartridge is in the machine, and a unit
     * test can hold one without ever building a CPU.
     */
    private void updateIRQLine() {
        if (cpu != null) {
            cpu.setIRQLine(mapperIRQ || apuFrameIRQ || dmcIRQ);
        }
    }

    /**
     * The three bits that say who is pulling /IRQ low.
     * <p>
     * They have to be in the file rather than worked out again on the way in, because none of the
     * three sources remembers whether it is currently asserting. MMC3 is the clearest case: a write
     * to $E000 drops the line without emptying the counter, and one to $E001 arms the counter
     * without raising the line, so no combination of the mapper's own registers says what the wire
     * is doing.
     * <p>
     * The level itself is then recomputed rather than restored, which is the rule everywhere: a
     * wire is a function of the things driving it, and only a latch comes out of the file.
     */
    public void serialize(final StateIO io) {
        mapperIRQ = io.bool(mapperIRQ);
        apuFrameIRQ = io.bool(apuFrameIRQ);
        dmcIRQ = io.bool(dmcIRQ);

        if (!io.saving()) {
            updateIRQLine();
        }
    }

    /**
     * Triggers a Reset (RST) interrupt on the CPU.
     * Used during system initialization or reset button press.
     * <p>
     * The reset line goes to the PPU as well, which re-arms the window it ignores $2000, $2001,
     * $2005 and $2006 in. The two come out of reset together, which is the assumption behind the
     * "around 29658 CPU cycles" that window is normally quoted as.
     */
    public void triggerRST() {
        cpu.requestRST();
        ppu.reset();
        apu.reset();
    }

    public boolean isDMAInProgress() {
        return mmu.isDMAInProgress();
    }

    /**
     * Decides what one CPU cycle is for, and spends it if a transfer can use it.
     *
     * @param cpuCycle the current CPU cycle counter, whose parity is the get/put phase
     * @return what the CPU should do with this cycle
     */
    @Override
    public DMACycle beginDMACycle(final long cpuCycle) {
        return mmu.beginDMACycle(cpuCycle);
    }

    /**
     * Reports what a halt cycle turned out to be.
     *
     * @param cpuWrote true if the CPU spent it writing, which delays the halt
     */
    @Override
    public void endHaltCycle(final boolean cpuWrote) {
        mmu.endHaltCycle(cpuWrote);
    }

    public CPU getCPU() {
        return cpu;
    }

    public PPU getPPU() {
        return ppu;
    }

    public APU getAPU() {
        return apu;
    }

    public MMU getMMU() {
        return mmu;
    }

    public Mapper getMapper() {
        return mapper;
    }

    public Controller getController1() {
        return controller1;
    }

    public Controller getController2() {
        return controller2;
    }
}
