package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.PPU;

import static org.junit.jupiter.api.Assertions.fail;

/**
 * A PPU wired to a stub cartridge and a bus that only watches the /NMI line, plus the handful of
 * things every PPU test needs to say.
 * <p>
 * Everything goes through the public register interface rather than through accessors, because
 * that is the only way a game can reach the PPU and so the only thing worth pinning down.
 */
abstract class PPUFixture {
    // Register numbers as the CPU sees them at $2000-$2007.
    static final int PPUCTRL = 0;
    static final int PPUMASK = 1;
    static final int PPUSTATUS = 2;
    static final int OAMADDR = 3;
    static final int OAMDATA = 4;
    static final int PPUSCROLL = 5;
    static final int PPUADDR = 6;
    static final int PPUDATA = 7;

    static final int DOTS_PER_FRAME = 341 * 262;

    protected StubMapper mapper;
    protected RecordingPPUBus bus;
    protected PPU ppu;

    protected void createPPU() {
        mapper = new StubMapper();
        bus = new RecordingPPUBus();
        ppu = new PPU(bus, mapper);
    }

    /**
     * Points the VRAM address register at {@code address}, resetting the shared write latch first
     * so the pair of $2006 writes cannot be knocked out of step by whatever ran before.
     */
    protected void setVRAMAddress(final int address) {
        ppu.read(PPUSTATUS);
        ppu.write(PPUADDR, (address >> 8) & 0x3F);
        ppu.write(PPUADDR, address & 0xFF);
    }

    protected void writeVRAM(final int address, final int data) {
        setVRAMAddress(address);
        ppu.write(PPUDATA, data);
    }

    /**
     * Reads one byte of VRAM through $2007, absorbing the buffered read for the caller. Not for
     * palette addresses, which are not buffered and would come back one entry along.
     */
    protected int readVRAM(final int address) {
        setVRAMAddress(address);
        ppu.read(PPUDATA);
        setVRAMAddress(address);
        return ppu.read(PPUDATA);
    }

    /**
     * Runs the PPU until the beam reaches a given dot, failing rather than looping forever if it
     * somehow never gets there.
     */
    protected void runTo(final int scanline, final int dot) {
        for (var i = 0; i <= 2 * DOTS_PER_FRAME; i++) {
            if (ppu.getScanline() == scanline && ppu.getDot() == dot) {
                return;
            }

            ppu.tick();
        }

        fail(String.format("the beam never reached (%d,%d)", scanline, dot));
    }

    protected void run(final int dots) {
        for (var i = 0; i < dots; i++) {
            ppu.tick();
        }
    }

    /**
     * Writes a run of identical bytes into VRAM, letting the address register step along by
     * itself the way a game filling a nametable would.
     */
    protected void fillVRAM(final int address, final int count, final int data) {
        setVRAMAddress(address);

        for (var i = 0; i < count; i++) {
            ppu.write(PPUDATA, data);
        }
    }

    /**
     * Renders whole frames and hands back the last one.
     * <p>
     * Two at least, normally: the vertical scroll position is only copied out of the staging
     * register on the pre-render line, so the first frame after setting it up is drawn from
     * whatever the address register happened to hold.
     */
    protected int[] renderFrames(final int frames) {
        runTo(0, 0);
        advanceFrames(frames);

        return ppu.getFrameBuffer();
    }

    protected int pixelAt(final int x, final int y) {
        return ppu.getFrameBuffer()[y * 256 + x];
    }

    protected void advanceFrames(final int frames) {
        var target = ppu.getFrame() + frames;

        while (ppu.getFrame() < target) {
            ppu.tick();
        }
    }

    /**
     * @return how many dots the next whole frame takes, measured from one line 0 dot 0 to the
     * next.
     */
    protected int measureFrame() {
        var dots = 0;

        do {
            ppu.tick();
            dots++;
        } while (!(ppu.getScanline() == 0 && ppu.getDot() == 0));

        return dots;
    }
}
