package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.CPU;
import com.github.dimiro1.mynes.NES;

/**
 * The machine at a frame boundary: the third way anything in the front end reads one, and the one
 * the gauges use.
 * <p>
 * The other two are still right for what they do. The PPU viewers poll on a Swing timer without
 * synchronising, which is fine because what they read is <em>arrays</em> -- an element cannot tear,
 * so the worst case is a tile a frame out of date. The debugger takes a {@link
 * com.github.dimiro1.mynes.ui.debugger.MachineSnapshot} inside the stop callback, which is exact,
 * once, with the machine halted.
 * <p>
 * Neither works for a dashboard. What a dashboard shows is <em>scalars</em> -- $2000, the scroll,
 * the frame number, which voices are sounding -- and a dozen of them read one at a time off a
 * running machine is not a slightly stale picture but a machine that never existed: the scroll from
 * one frame beside the beam position from the next. And a dashboard that only refreshed when the
 * machine stopped would be blank exactly while somebody was watching a game.
 * <p>
 * So it is built here, on the emulation thread, at the boundary where a frame has just finished and
 * nothing is half written -- see {@link EmulatorRunner#setFrameObserver} -- and handed to the event
 * dispatch thread whole. It is a record of primitives and holds no reference to the machine, which
 * is what makes that safe: there is nothing in one to read later.
 * <p>
 * It is cheap on purpose. A couple of dozen field reads, four times a second, against the ninety
 * thousand a frame's worth of emulation is. Nothing is copied and nothing is scanned; the memory
 * view's 64K is the snapshot's business and stays there.
 *
 * @param cpu       the processor, as {@link CPU#getState()} gives it.
 * @param frame     which frame has just finished.
 * @param scanline  where the beam is, which at a boundary is the top of the picture or the last of
 *                  the blanking lines depending on how far the last tick overshot.
 * @param dot       the dot within that line.
 * @param control   $2000 as it was last written.
 * @param mask      $2001 as the rendering hardware sees it, which a write reaches two dots late.
 * @param status    the three flags of $2002, without the open bus and without clearing anything.
 * @param oamAddress where the next $2004 would land.
 * @param v         the PPU's current VRAM address, which is where the beam is reading.
 * @param t         the temporary one, which is what the next frame will start from.
 * @param fineX     the three bits of horizontal scroll $2005 keeps outside {@code t}.
 * @param writeLatch whether the next $2005/$2006 write is the second of a pair.
 * @param apuStatus $4015, without acknowledging the frame counter's interrupt.
 * @param fiveStep  whether the frame counter is running the five step sequence.
 * @param frameIRQInhibited whether $4017 bit 6 is holding its interrupt off.
 * @param pad1      which buttons player one is holding, as the {@code BUTTON_} flags.
 * @param pad2      the same for player two, which nothing puts anything in yet.
 */
public record Readout(
        CPU.State cpu,
        long frame,
        int scanline,
        int dot,
        int control,
        int mask,
        int status,
        int oamAddress,
        int v,
        int t,
        int fineX,
        boolean writeLatch,
        int apuStatus,
        boolean fiveStep,
        boolean frameIRQInhibited,
        int pad1,
        int pad2) {

    /**
     * Reads the machine. Only ever called on the thread that clocks it, at a frame boundary.
     */
    public static Readout of(final NES nes) {
        var ppu = nes.getPPU();
        var apu = nes.getAPU();

        return new Readout(
                nes.getCPU().getState(),
                ppu.getFrame(),
                ppu.getScanline(),
                ppu.getDot(),
                ppu.getControl(),
                ppu.getMask(),
                ppu.peekStatus(),
                ppu.getOAMAddress(),
                ppu.getV(),
                ppu.getT(),
                ppu.getFineX(),
                ppu.isWriteLatchSet(),
                apu.peekStatus(),
                apu.isFiveStepFrameCounter(),
                apu.isFrameIRQInhibited(),
                nes.getController1().getButtons(),
                nes.getController2().getButtons());
    }

    /**
     * Where the picture the game is drawing starts, in pixels into the four nametables.
     * <p>
     * Out of {@code t} rather than {@code v}, which is the same choice the nametable viewer makes:
     * {@code v} is where the beam has got to and moves every eight dots, where {@code t} is what
     * the next frame will start from and is what a game means by "the scroll". Which also means
     * this is <b>as good as the scroll it is asked at</b>: a game that splits the screen mid-frame
     * has written the second half's scroll by the time the frame ends, so what a boundary sees is
     * whichever half was written last.
     */
    public int scrollX() {
        return ((t >> 10) & 1) * 256 + (t & 0x1F) * 8 + fineX;
    }

    public int scrollY() {
        return ((t >> 11) & 1) * 240 + ((t >> 5) & 0x1F) * 8 + ((t >> 12) & 7);
    }

    /**
     * Whether $2001 has either of the two bits that make the chip fetch anything.
     */
    public boolean renderingEnabled() {
        return (mask & 0x18) != 0;
    }

    /**
     * Where the background is taking its tiles from, $0000 or $1000, out of $2000 bit 4.
     * <p>
     * Spelled as the address rather than as the bit for the reason {@code PPU} spells it that way:
     * a caller with a tile number wants somewhere to add it to.
     */
    public int backgroundPatternTable() {
        return (control & 0x10) != 0 ? 0x1000 : 0x0000;
    }

    /**
     * The same for 8x8 sprites, out of $2000 bit 3-- meaningless while {@link #spriteHeight()} is
     * 16, since a tall sprite picks its table with the low bit of its tile number.
     */
    public int spritePatternTable() {
        return (control & 0x08) != 0 ? 0x1000 : 0x0000;
    }

    public int spriteHeight() {
        return (control & 0x20) != 0 ? 16 : 8;
    }

    /**
     * Which nametable the frame starts in, $2000 to $2C00, out of the two bits of $2000 that are
     * also bits 10 and 11 of {@code t}.
     */
    public int nametable() {
        return 0x2000 + (control & 3) * 0x400;
    }

    /**
     * The address of the byte on top of the stack, or $0200 when there is nothing on it.
     * <p>
     * The pointer names the next free slot rather than the last used one, so the top is one above
     * it -- and an empty stack's top would be $0200, which is the first address that is not stack.
     */
    public int stackTop() {
        return 0x0100 + cpu.sp() + 1;
    }

    /**
     * The processor flags the way nestest's log spells them: set ones in capitals, clear ones not,
     * which reads at a glance where eight ones and zeros do not.
     */
    public String flags() {
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
