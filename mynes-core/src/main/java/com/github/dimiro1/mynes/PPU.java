package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.state.StateIO;

import java.util.Arrays;
import java.util.Objects;

/**
 * PPU implements the picture processing unit: the 2C02 of the NTSC NES, or the 2C07 of the PAL one.
 * <p>
 * The chip is a state machine clocked three times per CPU cycle, and almost everything it is
 * known for -- the VBlank flag races, the sprite overflow bug, the $2007 increment glitch -- is a
 * consequence of what it happens to be doing on a particular dot. So this is written as a dot
 * machine: {@link #tick()} does the work belonging to the current {@code (scanline, dot)} and
 * then moves on, and the register handlers below look at where the beam is rather than at a pile
 * of separately maintained flags.
 * <p>
 * A frame is 341 dots by 262 scanlines, or 312 on PAL:
 * <ul>
 *   <li>0-239: visible, one pixel per dot for the first 256 dots</li>
 *   <li>240: post-render, the PPU idles</li>
 *   <li>241-260, or 241-310: vertical blank; the VBlank flag is set on dot 1 of line 241</li>
 *   <li>261, or 311: pre-render, which behaves like a visible line except that nothing is drawn
 *       and the status flags are cleared on dot 1</li>
 * </ul>
 * The ninety extra lines are all blanking, which is the interesting half of the difference between
 * the two machines: a PAL game has three and a half times as long between pictures to move the
 * world on in.
 * <p>
 * With rendering enabled, an NTSC odd frame drops the last dot of the pre-render line, so a frame
 * is 89342 dots normally and 89341 then. PAL does not, and is 106392 dots every time.
 * <p>
 * An {@link Overclock} lengthens that frame, and is the one thing here that is not the hardware: the
 * beam runs the post-render line or the last line of blanking again, as many times as it is asked
 * to, so a game gets ~113.67 more CPU cycles a line to finish its work in. The line numbers never
 * change, {@link #getScanline()} never names a line the chip does not have, and the picture is drawn
 * dot for dot as it always was -- but the pre-render line arrives later in CPU cycles than it would
 * on a console, which is a difference a program can measure. See {@link Overclock} for what that
 * costs and which half of it to reach for.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_rendering">NESdev: PPU rendering</a>
 * @see <a href="https://www.nesdev.org/wiki/PPU_scrolling">NESdev: PPU scrolling</a>
 */
public class PPU {
    /**
     * The last dot of a scanline. 341 dots numbered 0 to 340.
     */
    private static final int LAST_DOT = 340;

    /**
     * How many alignments of pixel to colour subcarrier the chip cycles through, one per scanline.
     * <p>
     * A scanline is 341 pixels of eight signal samples each and a colour cycle is twelve samples,
     * so a line is 227 and a third cycles and the alignment repeats every third one. Public because
     * a decoder of the signal has to agree with the chip about it; see
     * {@link com.github.dimiro1.mynes.video.NTSCFilter}.
     */
    public static final int COLOUR_PHASES = 3;

    /**
     * The first scanline that is not drawn.
     */
    private static final int POST_RENDER_LINE = 240;

    /**
     * The scanline VBlank starts on.
     */
    private static final int VBLANK_START_LINE = 241;

    /**
     * The dot of {@link #VBLANK_START_LINE} the VBlank flag is set on, and equally the dot of
     * {@link #preRenderLine} the status flags are cleared on.
     * <p>
     * Also the dot a $2002 read has to land on to suppress the flag entirely: see
     * {@link #preventVBlankFlag}.
     */
    private static final int STATUS_DOT = 1;

    /**
     * The dot the sprite output units are put back to counting on, near the end of a scanline.
     * <p>
     * It is the one thing that says "a new line starts now" to hardware that otherwise only counts
     * down, and it is the only reason a sprite is drawn once rather than every time its counter
     * comes round again. See {@link #isLineTheFrameCutsShort}.
     */
    private static final int COUNTER_RESTART_DOT = LAST_DOT - 1;

    /**
     * How many frames an open bus bit holds its charge for.
     * <p>
     * The real decay is around 600 milliseconds, which is about 36 frames, and it varies between
     * consoles and with temperature. This is the value {@code ppu_open_bus.nes} is happy with.
     */
    private static final int OPEN_BUS_DECAY_FRAMES = 36;

    /**
     * How many dots a write to $2001 takes to reach the rendering hardware.
     * <p>
     * The wiki says three to four and calls the delay load bearing -- Battletoads crashes without
     * it -- but two is what blargg's {@code 10-even_odd_timing} accepts, and that ROM measures the
     * delay directly, against the dot an odd frame drops. It is the tighter oracle, so it wins.
     * <p>
     * AccuracyCoin's {@code BG Serial In} wants four or five and is the reason to say so here. It
     * blanks eighteen dots across the background shift registers' reload and only leaves the hole
     * it looks for if that blank covers three reload dots; at two dots of delay it covers two. The
     * two ROMs are measuring the same number against different things -- the frame's dropped dot
     * and the reload cadence -- and they disagree by two dots, which is a real discrepancy rather
     * than a choice to be made. Left where blargg puts it.
     */
    private static final int MASK_WRITE_DELAY_DOTS = 2;

    /**
     * How many dots a second $2006 write takes to reach the VRAM address counter.
     */
    private static final int ADDRESS_WRITE_DELAY_DOTS = 2;

    /**
     * How long a $2007 read takes to become a fetch, and where the two dots of that fetch fall
     * inside the wait.
     * <p>
     * The register is not a door onto memory. A read of it starts a chain of five latches running
     * off the PPU clock, and the access only happens when the pulse reaches the far end: ALE two
     * dots after the CPU let go of the bus, and the read itself two dots after that. AccuracyCoin's
     * {@code $2007 Stress Test} walks a read across all 341 dots of a visible line and checks what
     * the buffer caught on each one, which is what pins both numbers down -- and {@code ALE + Read}
     * needs the gap to be a gap at all, since what it measures is this fetch turning up in the
     * middle of somebody else's access.
     * <p>
     * The counting starts a dot later than the wiki's does, because a CPU cycle's dots are spent
     * before the cycle's own bus access here -- see {@link NES#tick()}. The dot the read ends on
     * has already been run by the time this is armed, so the two and the four become a three and
     * a five.
     */
    private static final int DATA_FETCH_DOTS = 6;
    private static final int DATA_FETCH_ALE = DATA_FETCH_DOTS - 3;
    private static final int DATA_FETCH_READ = DATA_FETCH_DOTS - 5;

    /**
     * The registers the warm-up window covers, one bit per register number: $2000, $2001, $2005
     * and $2006.
     */
    private static final int WARM_UP_IGNORED_REGISTERS = 0b0110_0011;

    /**
     * The width of the picture in pixels, and equally the number of dots of a scanline the beam
     * draws on.
     */
    public static final int SCREEN_WIDTH = 256;

    /**
     * The height of the picture in scanlines. A front end normally shows fewer than this: a real
     * television hides the top and bottom of the picture behind the bezel.
     */
    public static final int SCREEN_HEIGHT = 240;

    // PPUCTRL ($2000) bits. The two nametable bits are not here: they are written straight into
    // the temporary VRAM address, which is where the PPU actually keeps them.
    private static final int CTRL_INCREMENT_32 = 0x04;
    private static final int CTRL_SPRITE_TABLE = 0x08;
    private static final int CTRL_BACKGROUND_TABLE = 0x10;
    private static final int CTRL_TALL_SPRITES = 0x20;
    private static final int CTRL_NMI_ENABLE = 0x80;

    // PPUMASK ($2001) bits.
    private static final int MASK_GREYSCALE = 0x01;
    private static final int MASK_SHOW_BACKGROUND_LEFT = 0x02;
    private static final int MASK_SHOW_SPRITES_LEFT = 0x04;
    private static final int MASK_SHOW_BACKGROUND = 0x08;
    private static final int MASK_SHOW_SPRITES = 0x10;
    private static final int MASK_RENDERING = MASK_SHOW_BACKGROUND | MASK_SHOW_SPRITES;

    // PPUSTATUS ($2002) bits.
    private static final int STATUS_SPRITE_OVERFLOW = 0x20;
    private static final int STATUS_SPRITE_ZERO_HIT = 0x40;
    private static final int STATUS_VBLANK = 0x80;

    private final PPUBus bus;
    private final VRAM vram;

    /**
     * Kept only so that the dots can be passed on. A mapper that watches the address bus has to
     * know how long the bus has been sitting at an address as well as what that address is, and
     * {@link VRAM} only ever hears about the accesses.
     */
    private final Mapper mapper;

    /**
     * Which console this is: how many scanlines a frame has, whether an odd one is a dot shorter,
     * how many master clocks go into a dot, and how long OAM holds its charge.
     */
    private final Region region;

    /**
     * {@link Region#preRenderLine()}, which is asked for on every dot. A field rather than a call
     * because {@link #isRenderingLine()} is the hottest branch in the emulator.
     */
    private final int preRenderLine;

    /**
     * {@link Region#oamDecayDots()}, read on every OAM access for the same reason.
     */
    private final int oamDecayDots;

    // ---------------------------------------------------------------- beam position

    private int scanline = 0;
    private int dot = 0;

    /**
     * Master clocks left over from the last CPU cycle, which is what makes 3.2 dots to a cycle
     * possible at all: see {@link #beginCPUCycle()}. Always nought on NTSC, where twelve master
     * clocks divide by four exactly.
     * <p>
     * Survives a reset, like {@link #clock} and for the same reason: the divider is the clock tree
     * rather than state the /RES line can see.
     */
    private int masterClockRemainder = 0;

    /**
     * Frames completed since power on. Doubles as the clock the open bus decay is measured
     * against.
     */
    private long frame = 0;

    /**
     * Dots since power on, and the clock OAM decay is measured against.
     * <p>
     * {@link #frame} cannot serve for that: a row of OAM loses its charge in about a twelfth of a
     * frame. Like the frame counter it survives a reset, because it is a clock rather than state
     * a reset can see.
     */
    private long clock = 0;

    /**
     * Whether the frame being drawn is an odd one, which is what decides if the last dot of the
     * pre-render line is skipped.
     */
    private boolean oddFrame = false;

    /**
     * Where the colour subcarrier has got to, counted in the thirds of a cycle a scanline drifts
     * by rather than in samples.
     * <p>
     * The chip does not encode colour, it draws a composite waveform out of twelve square waves,
     * and a 341 dot scanline is 227 and a third cycles of them -- so the alignment of pixel to
     * cycle slips a third of a cycle every line and comes back every third one. That slip is what
     * a television turns into dot crawl, and it belongs to the chip rather than to the set, which
     * is why it is counted here and why it is in the save state.
     * <p>
     * Counted per line rather than per frame on purpose. A frame is 262 lines on this machine, 312
     * on the other one and more than either under {@link Overclock}, and a line is a line in all
     * three cases -- so nothing here has to know how long a frame is.
     * <p>
     * A reset leaves it alone, like {@link #frame} and {@link #clock}: on a real console the
     * alignment is settled by where the chip happens to be when the picture starts, which is
     * nothing a program can read and nothing a button can put back.
     */
    private int colourPhase = 0;

    /**
     * {@link #colourPhase} as it stood at the top left of the frame in {@link #frameBuffer}, which
     * is the one a front end needs: the buffer is a whole frame and the counter has moved on 262
     * lines by the time anybody looks at it.
     */
    private int framePhase = 0;

    /**
     * How many extra idle scanlines to give the program per frame, which is a hack and not a
     * machine: see {@link Overclock}.
     * <p>
     * <strong>Never null.</strong> {@code SaveStateCompletenessTests} walks the console field by
     * field and names what it finds after the class it lives in, so a record here contributes
     * {@code Overclock.beforeNmi} and {@code Overclock.afterNmi} to that walk -- and a null would
     * contribute {@code PPU.overclock} instead, which is a different pair of names for its
     * exclusion list to have to know.
     */
    private Overclock overclock = Overclock.NONE;

    /**
     * How many times the current scanline has already been run again, and 0 on a real one.
     * <p>
     * Counted up and compared at each line wrap against whatever {@link #overclock} says
     * <em>now</em>, rather than latched when a frame starts. That is what makes switching the hack
     * off mean off: a machine part way through a repeat simply moves on at the next wrap, and a
     * state taken mid-repeat loads into a machine with no overclock without either of them having
     * to know what the other was set to.
     */
    private int extraLine;

    /**
     * True while the PPU's internal reset signal is still held over $2000, $2001, $2005 and
     * $2006.
     * <p>
     * The chip starts rendering the moment it is powered on or reset, but ignores those four
     * registers -- and does not toggle the write latch the last two share -- until the beam
     * reaches the pre-render line of the next frame, around 29658 CPU cycles later. Everything
     * else works from the first cycle. This is why every game and every test ROM begins by
     * waiting for two VBlanks.
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_power_up_state">NESdev: PPU power up state</a>
     */
    private boolean warmingUp = true;

    // ---------------------------------------------------------------- registers

    private int ctrl;

    /**
     * PPUMASK as the rendering hardware sees it, which is not quite what was last written: see
     * {@link #pendingMask}.
     */
    private int mask;

    /**
     * The value a recent $2001 write is on its way to putting into {@link #mask}.
     * <p>
     * Switching rendering on or off does not happen on the dot of the write. The signal has to
     * make its way through the pipeline first, and until it does the fetch machinery carries on
     * as it was. Two dots is what {@code 10-even_odd_timing.nes} measures, and it is the only
     * test in the suite that can see the difference.
     */
    private int pendingMask;

    /**
     * Dots left before {@link #pendingMask} lands.
     */
    private int maskDelay;

    private boolean vblankFlag;
    private boolean spriteZeroHit;
    private boolean spriteOverflow;

    /**
     * Set when a $2002 read lands on the very dot the VBlank flag would have been set on. The
     * read wins: the flag never goes up for that frame, so the game sees no VBlank at all.
     */
    private boolean preventVBlankFlag;

    /**
     * The current VRAM address, loopy's {@code v}. Fifteen bits, laid out as
     * {@code yyy NN YYYYY XXXXX}: fine Y scroll, nametable select, coarse Y, coarse X. During
     * rendering it is not an address the program set so much as a counter the PPU walks.
     */
    private int v;

    /**
     * The staging copy of {@link #v}, loopy's {@code t}. Writes to $2000, $2005 and $2006 land
     * here, and rendering copies pieces of it into {@code v} at fixed dots.
     */
    private int t;

    /**
     * Dots left before a second $2006 write reaches {@link #v}.
     * <p>
     * The address counter is not loaded on the dot of the write. What the second write does is
     * {@code t <- d}, then a wait of a dot or so, and only then {@code v <- t} -- so a scroll
     * split written mid-scanline lands slightly later than the write did. Nothing the CPU can
     * reach sees the transfer in flight; there are three dots to a CPU cycle, so the load always
     * beats the next access.
     */
    private int addressDelay;

    /**
     * Fine X scroll, loopy's {@code x}. Three bits, and the only part of the scroll position that
     * never goes anywhere near {@code v}.
     */
    private int fineX;

    /**
     * The shared write latch, loopy's {@code w}. $2005 and $2006 both take two writes and share
     * this one flag, which is why interleaving them is such a good way to confuse a game, and why
     * reading $2002 resets it.
     */
    private boolean writeLatch;

    /**
     * The one byte buffer behind $2007. A read of anything but palette RAM returns the previous
     * contents of this and only then refills it, so the first read after setting an address is
     * stale.
     */
    private int readBuffer;

    /**
     * Dots left before the fetch a $2007 read owes, or 0 when it owes none. See
     * {@link #DATA_FETCH_DOTS}.
     */
    private int dataFetch;

    /**
     * The address that fetch is for, taken when the CPU read happened rather than when it runs --
     * {@link #v} has moved on by then, and it is the address the program asked about that the
     * buffer is supposed to come back with.
     */
    private int dataFetchAddress;

    /**
     * Whether that read still owes the counter its increment. See {@link #settleOwedIncrement}.
     */
    private boolean incrementOwed;

    // ---------------------------------------------------------------- the address bus

    /**
     * The fourteen address lines the PPU is driving.
     * <p>
     * Only the top six are pins of their own. The bottom eight share the data pins, which is what
     * {@link #addressLatch} is for.
     */
    private int busAddress;

    /**
     * The octal latch on the board, holding A0-A7 between the two dots of an access.
     * <p>
     * Every PPU access is two dots long. On the first the address goes out whole -- the low eight
     * lines on the shared pins, where ALE latches them into a 74LS373 sitting between the chip and
     * everything on its bus -- and on the second the pins turn round and the byte comes back, while
     * the top six lines stay where the chip is holding them. So the address a read reaches is the
     * top six of {@link #busAddress} <em>as they are on the second dot</em> and the bottom eight as
     * they were on the first, and anything that moves the counter in between is answered with a
     * hybrid of the two. AccuracyCoin's {@code Hybrid Addresses} builds one on purpose out of a
     * $2006 write, and the sprite fetch's first dummy nametable read builds one every scanline,
     * because the horizontal reset lands between its two dots.
     */
    private int addressLatch;

    /**
     * The byte the last read left on the shared pins.
     * <p>
     * Which matters on one kind of dot: one where a $2007 read's fetch lands on the first dot of
     * somebody else's access. The pins are inputs for the read, so the PPU never gets to drive the
     * address low byte onto them, and what the latch takes instead is whatever was still sitting
     * there. See {@link #openAddress}.
     */
    private int busData;

    /**
     * Where in OAM the sprite hardware is pointing.
     * <p>
     * On the PPU rather than inside {@link OAM} or {@link SpriteEvaluation} because there is one of
     * it and three things use it: the CPU sets it through $2003, $2004 reads and writes move it,
     * and sprite evaluation walks it across a scanline and leaves it wherever it finished. Every
     * quirk in this file that begins "a game that left OAMADDR pointing somewhere odd" is a
     * consequence of that sharing, so it is deliberately not hidden inside either of them.
     */
    private int oamAddress;

    private final OAM oam = new OAM();

    /**
     * Palette RAM. Thirty two entries of six bits, inside the PPU rather than on its bus.
     */
    private final int[] palette = new int[32];

    // ---------------------------------------------------------------- background pipeline

    private final Background background = new Background();

    // ---------------------------------------------------------------- sprite pipeline

    /**
     * The eight sprites the PPU has picked out for the next scanline, four bytes each. Filled with
     * $FF at the start of every visible line and then written by the evaluation state machine.
     * <p>
     * Shared memory rather than {@link SpriteEvaluation}'s own, in the same way {@link #oamAddress}
     * is: the evaluation writes it across the first half of a line, the fetch reads it across the
     * second half, and $2004 reads whichever of them is holding it.
     */
    private final int[] secondaryOAM = new int[32];

    private final SpriteEvaluation evaluation = new SpriteEvaluation();

    /**
     * Whether the sprite in the first output unit is the one that can set the sprite 0 hit flag,
     * which is decided a line before it is used -- {@link SpriteEvaluation#foundSpriteZero} is the
     * line being evaluated and this is the line being drawn.
     */
    private boolean spriteZeroOnThisLine;

    /**
     * A row of OAM owed to the next rendering dot, and which row.
     * <p>
     * Switching rendering off part way down the picture leaves the sprite hardware holding an
     * address it never got to use, and the next time rendering starts it spends that address on a
     * copy nobody asked for. See {@link #corruptOAM}.
     */
    private boolean corruptionPending;
    private int corruptionSeed;

    /**
     * The eight sprite output units, loaded during dots 257-320 and drained across the next
     * scanline.
     */
    private final SpriteUnit[] spriteUnits = {
            new SpriteUnit(), new SpriteUnit(), new SpriteUnit(), new SpriteUnit(),
            new SpriteUnit(), new SpriteUnit(), new SpriteUnit(), new SpriteUnit(),
    };

    /**
     * The sprites the eight units above had no room for, which are not hardware and are drawn only
     * when somebody has asked for them.
     */
    private final ExtraSprites extraSprites = new ExtraSprites();

    // ---------------------------------------------------------------- open bus

    private final OpenBus openBus = new OpenBus();

    // ---------------------------------------------------------------- output

    private final int[] frameBuffer = new int[SCREEN_WIDTH * SCREEN_HEIGHT];

    /**
     * Debug switches over the two layers, for a front end to offer. They gate what reaches the
     * framebuffer and nothing else: sprite evaluation, the sprite 0 hit flag and everything else
     * a game can observe carries on exactly as before, so hiding a layer cannot change how the
     * game runs -- only what it looks like.
     */
    private boolean backgroundLayerVisible = true;
    private boolean spriteLayerVisible = true;

    public PPU(final PPUBus bus, final Mapper mapper) {
        this(bus, mapper, Region.NTSC);
    }

    public PPU(final PPUBus bus, final Mapper mapper, final Region region) {
        this.bus = bus;
        this.mapper = mapper;
        this.vram = new VRAM(mapper);
        this.region = region;
        this.preRenderLine = region.preRenderLine();
        this.oamDecayDots = region.oamDecayDots();

        bus.setNMILine(false);
    }

    /**
     * Pulls the PPU's reset line, the way the console's reset button does.
     * <p>
     * The internal reset signal covers the same four registers the warm-up window does, so they
     * go back to zero and the window is armed again; the beam restarts at the top left. What it
     * does not reach is left exactly as it was: {@link #v}, OAMADDR, OAM, palette RAM and the
     * status flags all survive. So do {@link #frame} and {@link #clock}, which are the clocks the
     * open bus and OAM decay are measured against rather than state a reset can see.
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_power_up_state">NESdev: PPU power up state</a>
     */
    public void reset() {
        warmingUp = true;

        ctrl = 0;
        mask = 0;
        pendingMask = 0;
        maskDelay = 0;
        t = 0;
        addressDelay = 0;
        fineX = 0;
        writeLatch = false;
        readBuffer = 0;

        scanline = 0;
        dot = 0;
        oddFrame = false;

        // The beam has been put back to the top left, so whatever repeats it was part way through
        // are over. How many there are to run is the hack's setting and not the button's business,
        // so that is left exactly as it was.
        extraLine = 0;

        // The VBlank flag is untouched, but the NMI enable bit has just gone, so the line has to
        // be settled again.
        updateNMILine();
    }

    /**
     * How many dots the CPU cycle about to run is worth, counting the master clocks it takes.
     * <p>
     * One crystal feeds both chips through dividers of its own: on NTSC the CPU's is twelve and the
     * PPU's four, which is three dots to a cycle exactly and leaves nothing over. On PAL they are
     * sixteen and five, which is 3.2 -- so a cycle is worth three dots four times and four dots the
     * fifth, and what decides which is the master clocks the last cycle could not spend.
     * <p>
     * Counted this way rather than from a table of the pattern because the pattern is not a fact
     * about the machine; the two divisors are, and everything else follows from them.
     */
    int beginCPUCycle() {
        var available = masterClockRemainder + region.cpuDivider();

        masterClockRemainder = available % region.ppuDivider();

        return available / region.ppuDivider();
    }

    /**
     * Advances the PPU by one dot.
     * <p>
     * The work belonging to the current dot happens first and the position moves afterwards, so
     * that a CPU access arriving between two of these sees the state the hardware would have had
     * at that point in the scanline.
     */
    public void tick() {
        // Before the dot's own work, so that a mapper counting how long the address bus has been
        // idle counts this dot as idle if nothing on it touches the bus.
        mapper.ppuTick();

        // Likewise before it: everything below asks which of a $2007 fetch's dots this one is, and
        // the answer has to be the same for all of them.
        if (dataFetch > 0) {
            dataFetch--;
        }

        // Not on a line the overclock is running again. What this clock is measured against is OAM
        // losing its charge, and the charge leaks in the television's time rather than the
        // emulator's -- an extra line takes none of it. Skipping it is also what stops a large
        // setting wiping every sprite in the game once a frame: past 270 lines on NTSC a frame's
        // blanking would otherwise outlast the window in Region.oamDecayDots.
        if (extraLine == 0) {
            clock++;
        }

        settle();

        if (isRenderingLine()) {
            if (scanline == preRenderLine && dot == STATUS_DOT) {
                endVBlank();
            }

            clockSpriteCounters();

            if (isRenderingEnabled()) {
                backgroundTick();
                spriteTick();

                if (dot == 257) {
                    // After the sprite fetch has put its first dummy nametable address out, so the
                    // low byte the board latched is the one the counter was still holding. The
                    // read on the next dot then takes its top six lines from the counter as reset
                    // -- a hybrid address, and the one AccuracyCoin's $2007 Stress Test finds the
                    // buffer holding at this point in the line.
                    copyHorizontalPosition();
                }
            }

            // The picture comes out whether or not anything is being rendered: with rendering off
            // the screen is a flat sheet of the backdrop colour rather than nothing at all.
            if (scanline < POST_RENDER_LINE && dot >= 1 && dot <= SCREEN_WIDTH) {
                renderPixel();
            }

            // After the pixel rather than before it: what a sprite puts out on a dot is what its
            // shift register holds during that dot, and the shift is the clock edge at the end of
            // it. The difference only shows on the first dot of a unit's life -- a unit that was
            // already halted when the scanline began would otherwise throw its first pixel away.
            shiftSpriteUnits();
        } else if (scanline == VBLANK_START_LINE && dot == STATUS_DOT) {
            startVBlank();
        }

        // After the pipeline, because the pipeline gets the bus first and this has to make do with
        // whatever it finds there.
        dataFetchTick();
        settleAddress();

        advance();
    }

    /**
     * The three things decided on an earlier dot that land on this one, before the dot does any
     * work of its own.
     * <p>
     * They are in this order because they feed each other. The mask has to land before the check
     * that reads it, and the fact that it has just landed with rendering switched <em>off</em> is
     * exactly why the corruption cannot also happen on this dot -- it waits for the dot rendering
     * comes back on.
     * <p>
     * The fourth is {@link #settleAddress}, which is not here: it lands at the end of the dot
     * rather than the start, for the reason spelled out there.
     */
    private void settle() {
        if (warmingUp && scanline == preRenderLine) {
            // The internal reset signal is released when the beam first reaches the pre-render
            // line, which on NTSC is 89001 dots from power on, or 29667 CPU cycles -- the wiki's
            // "around 29658", said in the units this class thinks in. On PAL the beam has fifty
            // more lines to cross first, so the window is 106051 dots, and a game that waits for
            // two VBlanks rather than counting cycles does not care either way.
            warmingUp = false;
        }

        if (maskDelay > 0 && --maskDelay == 0) {
            var wasRendering = isRenderingEnabled();
            mask = pendingMask;

            // The seed is taken where the mask actually lands rather than where it was written,
            // which is what makes the corrupted row depend on the delay above.
            if (wasRendering && !isRenderingEnabled() && isRenderingLine()) {
                corruptionSeed = secondaryOAMAddress();
                corruptionPending = true;
            }

            // What keeps OAM alive is the evaluation hardware cycling it, and that hardware starts
            // the moment rendering comes on rather than only at dot 65 -- so switching it on part
            // way through the evaluation refreshes OAM just as the start of a line does. Without
            // this a game that renders for a slice of each frame loses its sprites to decay, which
            // is what AccuracyCoin's $2007 Stress Test does 341 times over: it enables rendering a
            // hundred cycles before the read it is measuring and turns it off again straight after.
            if (!wasRendering && isRenderingEnabled()
                    && scanline < POST_RENDER_LINE && dot > 65 && dot <= 256) {
                oam.refreshEveryRow();
            }
        }

        if (corruptionPending && isRenderingEnabled() && isRenderingLine()) {
            corruptOAM();
        }
    }

    /**
     * The second $2006 write's {@code v <- t}, on the dot it finally lands.
     * <p>
     * At the <em>end</em> of that dot rather than the start of it, which is the whole of why
     * {@link #addressLatch} can end up holding one address's low byte under another's high one.
     * The wiki puts the load "1 to 1.5 dots after the write completes", and a load that lands in
     * the middle of a dot lands after the address has gone out on that dot and before the byte
     * comes back on the next -- so a write timed at the first dot of a nametable fetch leaves the
     * board holding the old low byte and gives the read the new high one. AccuracyCoin's
     * {@code Hybrid Addresses} does exactly that, and reads a tile the nametable does not contain.
     */
    private void settleAddress() {
        if (addressDelay == 0 || --addressDelay > 0) {
            return;
        }

        // A coarse X increment that fell into the gap between the write and this is overwritten
        // rather than kept, which is what the hardware does.
        v = t;

        // The counter drives the address bus, so loading it puts the address out there with
        // no access going with it. That is how a game with the picture switched off clocks an
        // MMC3's scanline counter, and it happens here rather than at the write because until
        // this dot the bus is still holding the old address.
        mapper.ppuAddress(v & 0x3FFF);
    }

    /**
     * Raises the VBlank flag, unless a $2002 read got in first.
     */
    private void startVBlank() {
        if (!preventVBlankFlag) {
            vblankFlag = true;
        }

        preventVBlankFlag = false;
        updateNMILine();
    }

    /**
     * Clears every status flag at the top of the pre-render line.
     */
    private void endVBlank() {
        vblankFlag = false;
        spriteZeroHit = false;
        spriteOverflow = false;
        updateNMILine();
    }

    /**
     * Moves the beam on by one dot, wrapping the scanline and the frame.
     * <p>
     * With rendering enabled an odd frame skips the last dot of the pre-render line. That one
     * missing dot is what keeps the NTSC colour burst in step from frame to frame, and it also
     * means a frame is not a whole number of CPU cycles, so software cannot rely on the beam
     * being in the same place relative to the CPU on every frame.
     * <p>
     * PAL does not do it. Its burst phase is corrected by alternating it every line -- which is
     * what the P in PAL is -- so the 2C07 has nothing left to fix, and every frame of it is the
     * same length.
     * <p>
     * An {@link Overclock} is a line the beam runs again rather than a line number the chip does not
     * have, and this is the whole of where it happens. Repeating 240 or the last vblank line is
     * indistinguishable from a longer idle one: nothing below {@link #tick()} keys on either of them
     * -- rendering and sprite work are gated on {@link #isRenderingLine()}, the odd-frame skip and
     * the warm-up release key on {@link #preRenderLine}, and the VBlank flag on 241 and on the
     * pre-render line. So the extra post-render lines land between the picture and the flag going
     * up, and the extra vblank lines between the end of blanking and the pre-render line, with the
     * flag still up.
     */
    private void advance() {
        if (region.skipsDotOnOddFrames()
                && scanline == preRenderLine && dot == LAST_DOT - 1
                && oddFrame && isRenderingEnabled()) {
            // A line one dot short is eight samples short, and eight fewer samples out of twelve
            // is four more of them modulo the cycle -- so the short line drifts by two thirds
            // where every other line drifts by one. That is the whole of why the artefact pattern
            // repeats every two frames with rendering on and every three with it off.
            endLine(2);
            startFrame();
            return;
        }

        dot++;

        if (dot > LAST_DOT) {
            dot = 0;
            endLine(1);

            if ((scanline == POST_RENDER_LINE && extraLine < overclock.beforeNmi())
                    || (scanline == preRenderLine - 1 && extraLine < overclock.afterNmi())) {
                // Run this line again: the beam stays where it is and the CPU gets the line.
                extraLine++;
                return;
            }

            extraLine = 0;
            scanline++;

            if (scanline > preRenderLine) {
                startFrame();
            }
        }
    }

    private void startFrame() {
        scanline = 0;
        dot = 0;
        frame++;
        oddFrame = !oddFrame;
        framePhase = colourPhase;
    }

    /**
     * Moves the colour subcarrier on by a finished scanline's worth of drift.
     *
     * @param thirds how many thirds of a cycle this line was worth: one for a whole line, two for
     *               the short one an odd frame ends on.
     */
    private void endLine(final int thirds) {
        colourPhase = (colourPhase + thirds) % COLOUR_PHASES;
    }

    // ================================================================ the address bus

    /**
     * The first dot of an access: the address goes out, and the board latches its low byte.
     * <p>
     * The one thing that is not obvious is the branch. A $2007 read whose own fetch lands on this
     * dot has the shared pins turned round as inputs, so the PPU never gets to drive the address
     * low byte onto them and the latch takes the byte the last read left there instead. That is
     * not a guess about analogue behaviour: AccuracyCoin's {@code ALE + Read} arranges for exactly
     * this dot and then reads a bit plane out of the address it produces, which on that ROM's
     * nametable is a row of solid pixels where the tile is empty.
     */
    private void openAddress(final int address) {
        busAddress = address & 0x3FFF;
        addressLatch = dataFetch == DATA_FETCH_READ ? busData : busAddress & 0xFF;

        // The cartridge is wired to the latch, not to the pins, so what it sees is the same
        // half-and-half address a read on the next dot would reach.
        mapper.ppuAddress(latchedAddress());
    }

    /**
     * The second dot: the byte comes back from wherever the two halves of the address point.
     *
     * @param address what the chip is driving on the top six lines now, which is recomputed rather
     *                than remembered -- that is the whole of why a hybrid address is possible.
     */
    private int fetch(final int address) {
        busAddress = address & 0x3FFF;

        return busData = vram.read(latchedAddress());
    }

    /**
     * @return where the address bus is actually pointing: the top six lines as the chip is driving
     * them now, and the bottom eight as the latch is holding them.
     */
    private int latchedAddress() {
        return (busAddress & 0x3F00) | addressLatch;
    }

    /**
     * Whether the fetch pipeline has the bus on this dot, and so whether a $2007 read's own access
     * has to make do with what it finds there.
     * <p>
     * Every dot of a rendering line but the first is half of an access: the odd ones put an address
     * out, the even ones take the byte back. That holds without a gap from dot 1 to dot 340 --
     * through the sprite fetches, which read the nametable twice over where a background fetch
     * would read an attribute, and through the two dummy nametable fetches the line ends with.
     */
    private boolean pipelineHasTheBus() {
        return dot >= 1 && isRenderingLine() && isRenderingEnabled();
    }

    /**
     * The two dots a $2007 read owes the bus, whenever they come round.
     * <p>
     * With the picture off this is an ordinary read of the address the program asked about. With it
     * on, the pipeline is already using every dot, and what the buffer ends up holding is a byte
     * from the picture rather than the one asked for -- which is the whole of AccuracyCoin's
     * {@code $2007 Stress Test}, and the reason a game cannot read VRAM mid-frame.
     */
    private void dataFetchTick() {
        if (dataFetch == DATA_FETCH_ALE) {
            if (!pipelineHasTheBus()) {
                openAddress(dataFetchAddress);
            }

            return;
        }

        if (dataFetch != DATA_FETCH_READ) {
            return;
        }

        if (!pipelineHasTheBus()) {
            readBuffer = fetch(dataFetchAddress);
        } else if ((dot & 1) == 0) {
            // The pipeline took a byte off the bus on this dot, and there is only one bus: the
            // buffer catches the same byte the picture did.
            readBuffer = busData;
        } else {
            // The pipeline put an address out on this dot, so the top six lines are its and the
            // bottom eight are whatever the latch was left holding. See openAddress.
            readBuffer = fetch(busAddress);
        }

        settleOwedIncrement();
    }

    // ================================================================ background pipeline

    /**
     * One dot of the background fetch pipeline, on a visible or pre-render line with rendering
     * enabled.
     * <p>
     * The pipeline is always two tiles ahead of the beam. It spends eight dots per tile fetching
     * four bytes -- nametable, attribute, pattern low, pattern high -- and the pair of shift
     * registers it feeds are drained one pixel at a time behind it. The last sixteen dots of a
     * scanline fetch the first two tiles of the next one, which is why the scroll registers can be
     * changed mid-frame at all: by the time the beam reaches a scanline, its first two tiles have
     * already been read.
     * <p>
     * Four bytes over eight dots is <em>two</em> dots per byte, and the switch below is written as
     * eight cases rather than four because both of them matter. The odd dot puts the address out,
     * the even one takes the byte back, and the address is recomputed on the second of the two: see
     * {@link #addressLatch} for what that is worth.
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_rendering#Cycles_1-256">NESdev: PPU rendering</a>
     */
    private void backgroundTick() {
        var fetching = (dot >= 1 && dot <= 256) || (dot >= 321 && dot <= 336);

        if ((dot >= 2 && dot <= 257) || (dot >= 322 && dot <= 337)) {
            background.shift();
        }

        // Dots 9, 17, ... 257, then 329 and 337. The reload at dots 1 and 321 that this also
        // catches is a repeat of the one at 337, so it changes nothing.
        if ((fetching || dot == 257 || dot == 337) && (dot & 7) == 1) {
            background.reload();
        }

        if (fetching) {
            switch (dot & 7) {
                case 1 -> openAddress(nameTableAddress());
                case 2 -> background.nameTableLatch = fetch(nameTableAddress());
                case 3 -> openAddress(attributeAddress());
                case 4 -> background.attributeLatch =
                        (fetch(attributeAddress()) >> attributeShift()) & 0x03;
                case 5 -> openAddress(patternAddress());
                case 6 -> background.patternLowLatch = fetch(patternAddress());
                case 7 -> openAddress(patternAddress() + 8);
                default -> {
                    background.patternHighLatch = fetch(patternAddress() + 8);
                    incrementCoarseX();
                }
            }
        }

        if (dot == 256) {
            incrementY();
        } else if (scanline == preRenderLine && dot >= 280 && dot <= 304) {
            copyVerticalPosition();
        } else if (dot == 337 || dot == 339) {
            // Two more nametable reads that nothing uses. They exist because the fetch machinery
            // has nothing else to do, and mappers that watch the address bus can see them -- as
            // does a $2007 read whose fetch lands here, which is how AccuracyCoin knows they are
            // nametable reads rather than the attribute reads the same dots would take in the
            // middle of a line.
            openAddress(nameTableAddress());
        } else if (dot == 338 || dot == 340) {
            fetch(nameTableAddress());
        }
    }

    /**
     * @return the nametable address the counter is naming, which the background fetch reads once a
     * tile and the sprite fetch reads twice a slot without wanting either byte.
     */
    private int nameTableAddress() {
        return 0x2000 | (v & 0x0FFF);
    }

    /**
     * @return the address of the attribute byte covering the tile being fetched.
     */
    private int attributeAddress() {
        return 0x23C0 | (v & 0x0C00) | ((v >> 4) & 0x38) | ((v >> 2) & 0x07);
    }

    /**
     * Which two bits of that byte belong to this tile.
     * <p>
     * One attribute byte covers a four tile by four tile block and packs four two bit palette
     * numbers, one per two by two quadrant. Bit 1 of coarse X picks the left or right half and
     * bit 1 of coarse Y the top or bottom, so the pair wanted is at bit
     * {@code (coarseY & 2) << 1 | (coarseX & 2)}.
     */
    private int attributeShift() {
        return ((v >> 4) & 0x04) | (v & 0x02);
    }

    /**
     * @return the address of the low pattern byte for the tile just named, at the row fine Y
     * points at. The high byte is eight further on.
     */
    private int patternAddress() {
        return ((ctrl & CTRL_BACKGROUND_TABLE) != 0 ? 0x1000 : 0x0000)
                + background.nameTableLatch * 16
                + ((v >> 12) & 0x07);
    }

    /**
     * The four bytes of the tile being fetched, and the shift registers they are handed to eight
     * dots later.
     * <p>
     * Nothing here knows where the beam is or what $2001 says: the fetch calls {@link #reload()}
     * at the dot the hardware does, the dot machine calls {@link #shift()} on the dots the hardware
     * does, and {@link #pixel(int)} answers for whichever bit fine X points at. Which dots those
     * are is the PPU's business and stays there.
     */
    private static final class Background {
        /**
         * The four bytes of the tile currently being fetched. They sit here until the eight dot
         * fetch is over and {@link #reload()} hands them to the shift registers.
         */
        private int nameTableLatch;
        private int attributeLatch;
        private int patternLowLatch;
        private int patternHighLatch;

        /**
         * The pattern shift registers, sixteen bits each: the tile on screen in the top half and
         * the one after it in the bottom half. A pixel is whichever bit fine X points at.
         */
        private int patternShiftLow;
        private int patternShiftHigh;

        /**
         * The attribute shift registers, alongside the pattern ones and holding the palette number
         * for the same pixels.
         */
        private int attributeShiftLow;
        private int attributeShiftHigh;

        /**
         * Shifts all four registers along by one pixel.
         * <p>
         * Something has to come in at the far end, and what comes in is wired rather than fetched:
         * a 0 into the low bit plane, a 1 into the high one, and for the attributes the one bit
         * latch that fed the parallel load. It normally makes no difference at all -- the eight
         * bits a reload puts in are the eight the beam reads out, and the serial input never
         * reaches the top half of the register before the next reload overwrites the bottom.
         * <p>
         * It shows when a game arranges for the reload not to happen: switch rendering off just
         * before one and on again just after, and the registers keep shifting with nothing going
         * into them but their serial inputs, so what gets drawn is colour 2 of whichever palette
         * the attribute latch is still holding. AccuracyCoin's {@code BG Serial In} draws a screen
         * of it and then puts a sprite over it to prove the background was not transparent.
         */
        private void shift() {
            patternShiftLow = (patternShiftLow << 1) & 0xFFFF;
            patternShiftHigh = ((patternShiftHigh << 1) | 1) & 0xFFFF;
            attributeShiftLow = ((attributeShiftLow << 1) | (attributeLatch & 1)) & 0xFFFF;
            attributeShiftHigh = ((attributeShiftHigh << 1) | ((attributeLatch >> 1) & 1)) & 0xFFFF;
        }

        /**
         * Drops the tile that has just been fetched into the bottom half of the shift registers,
         * eight dots before the beam needs it.
         * <p>
         * The attribute is two bits for the whole tile rather than one per pixel, so its shift
         * registers are filled with eight copies of each bit. Real hardware keeps a one bit latch
         * and a narrower shifter instead; the picture is the same.
         */
        private void reload() {
            patternShiftLow = (patternShiftLow & 0xFF00) | patternLowLatch;
            patternShiftHigh = (patternShiftHigh & 0xFF00) | patternHighLatch;
            attributeShiftLow =
                    (attributeShiftLow & 0xFF00) | ((attributeLatch & 1) != 0 ? 0xFF : 0x00);
            attributeShiftHigh =
                    (attributeShiftHigh & 0xFF00) | ((attributeLatch & 2) != 0 ? 0xFF : 0x00);
        }

        /**
         * @param fineX which of the sixteen bits in flight is the one on screen now. It is the only
         *              part of the scroll position applied here rather than by the fetch.
         * @return the low four bits of a palette address -- palette number in bits 3-2 and colour
         * within it in bits 1-0 -- or zero if the background is transparent here.
         */
        private int pixel(final int fineX) {
            var bit = 0x8000 >> fineX;

            var colour = ((patternShiftHigh & bit) != 0 ? 2 : 0)
                    | ((patternShiftLow & bit) != 0 ? 1 : 0);

            if (colour == 0) {
                return 0;
            }

            var palette = ((attributeShiftHigh & bit) != 0 ? 2 : 0)
                    | ((attributeShiftLow & bit) != 0 ? 1 : 0);

            return (palette << 2) | colour;
        }

        /**
         * Unlike the other units here this one carries its own list, because the eight fields were
         * already next to each other and in this order in the file format.
         */
        private void serialize(final StateIO io) {
            nameTableLatch = io.u8(nameTableLatch);
            attributeLatch = io.u8(attributeLatch);
            patternLowLatch = io.u8(patternLowLatch);
            patternHighLatch = io.u8(patternHighLatch);
            patternShiftLow = io.u16(patternShiftLow);
            patternShiftHigh = io.u16(patternShiftHigh);
            attributeShiftLow = io.u16(attributeShiftLow);
            attributeShiftHigh = io.u16(attributeShiftHigh);
        }
    }

    /**
     * Puts the horizontal half of the scroll position back at the start of every scanline. Coarse
     * X and the nametable's horizontal bit, nothing else.
     */
    private void copyHorizontalPosition() {
        v = (v & ~0x041F) | (t & 0x041F);
    }

    /**
     * Puts the vertical half of the scroll position back, once per frame, spread over dots 280 to
     * 304 of the pre-render line. Fine Y, coarse Y and the nametable's vertical bit.
     */
    private void copyVerticalPosition() {
        v = (v & ~0x7BE0) | (t & 0x7BE0);
    }

    // ================================================================ sprite pipeline

    /**
     * One dot of the sprite hardware, on a visible or pre-render line with rendering enabled.
     * <p>
     * A scanline's sprites are chosen while the line <em>above</em> it is being drawn, which is
     * why a sprite's Y coordinate is one less than the row it appears on, and why nothing is ever
     * drawn on scanline 0: the pre-render line does no evaluation at all.
     * <ul>
     *   <li>dots 1-64: secondary OAM is wiped to $FF, one byte every two dots</li>
     *   <li>dots 65-256: the evaluation state machine walks OAM, one byte every two dots</li>
     *   <li>dots 257-320: the eight output units are loaded, eight dots each</li>
     * </ul>
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_sprite_evaluation">NESdev: sprite evaluation</a>
     */
    private void spriteTick() {
        if (scanline < POST_RENDER_LINE) {
            if (dot >= 1 && dot <= 64) {
                clearSecondaryOAM();
            } else if (dot >= 65 && dot <= 256) {
                evaluation.tick();
            }
        }

        if (dot == COUNTER_RESTART_DOT && !isLineTheFrameCutsShort()) {
            // The one dot that puts every counter back to counting. A scanline that spends it in
            // forced blank leaves them halted instead, and whatever they are holding is drawn from
            // the first dot rendering comes back on.
            for (var unit : spriteUnits) {
                unit.halted = false;
            }

            extraSprites.release();
        }

        if (dot < 257 || dot > 320) {
            return;
        }

        // OAMADDR is held at zero for the whole fetch phase. A game that writes it during
        // rendering and expects to find it again afterwards will not.
        oamAddress = 0;

        if (dot == 257) {
            // The pre-render line evaluates nothing, so this is still the answer the line above
            // the picture came to -- which is the only reason a stale sprite drawn on scanline 0
            // can set the hit flag.
            spriteZeroOnThisLine = evaluation.foundSpriteZero;
        }

        var slot = (dot - 257) >> 3;
        var unit = spriteUnits[slot];

        switch ((dot - 257) & 7) {
            // Two reads of the nametable address the background fetch left behind. Nothing uses
            // the bytes -- the sprite's tile number came out of secondary OAM, not out of a
            // nametable -- but a mapper watching the address bus can see them, and so does a
            // $2007 read waiting for the next byte off the pipeline.
            case 0 -> openAddress(nameTableAddress());
            case 1 -> fetch(nameTableAddress());

            // The attribute latch and the X counter are loaded during the second of those two
            // reads, one on each of its dots.
            case 2 -> {
                openAddress(nameTableAddress());
                unit.attributes = secondaryOAM[slot * 4 + 2];
            }
            case 3 -> {
                fetch(nameTableAddress());
                unit.counter = secondaryOAM[slot * 4 + 3];
            }

            case 4 -> openAddress(spriteFetchAddress(slot, 0));
            case 5 -> unit.patternLow = fetchSpritePattern(slot, 0);
            case 6 -> openAddress(spriteFetchAddress(slot, 8));
            default -> unit.patternHigh = fetchSpritePattern(slot, 8);
        }

        // The fetch window is over, so the eight units are loaded and the evaluation's answer for
        // this line is final. Whatever it had to leave behind is picked up here, on a dot the
        // hardware spends putting an address out and nothing else.
        if (dot == 320) {
            extraSprites.scan();
        }
    }

    /**
     * One sprite output unit: an X down-counter, an attribute latch and a pair of eight bit shift
     * registers.
     * <p>
     * The counter is loaded from the sprite's X coordinate; it counts down once per visible dot and
     * the unit <em>halts</em> when it reaches zero, which is when its shift registers start putting
     * a pixel out on every dot. There is no list of how many sprites are on the line and no
     * comparison against a coordinate: a slot nobody filled holds the $FF secondary OAM was wiped
     * with, so it counts all the way to the right hand edge and finds a transparent pattern when it
     * gets there.
     */
    private static final class SpriteUnit {
        private int counter;
        private boolean halted;
        private int attributes;
        private int patternLow;
        private int patternHigh;

        /**
         * One dot of the X down-counter, which stops rather than wrapping: reaching zero is what
         * starts the unit drawing, and it goes on drawing until something puts it back to counting.
         */
        private void clockCounter() {
            if (halted) {
                return;
            }

            if (counter == 0) {
                halted = true;
            } else {
                counter--;
            }
        }

        /**
         * One dot of the pattern shift registers, which only move once the unit has halted -- a
         * unit still counting is a sprite the beam has not reached yet.
         */
        private void shift() {
            if (!halted) {
                return;
            }

            patternLow = (patternLow << 1) & 0xFF;
            patternHigh = (patternHigh << 1) & 0xFF;
        }

        /**
         * @return the two bit colour this unit is putting out, zero meaning transparent -- which is
         * also the answer for a unit that has not started drawing yet.
         */
        private int pixel() {
            if (!halted) {
                return 0;
            }

            return ((patternHigh & 0x80) != 0 ? 2 : 0) | ((patternLow & 0x80) != 0 ? 1 : 0);
        }
    }

    /**
     * @return whether this is the pre-render line of a frame that is about to drop its last dot.
     * <p>
     * Two measurements have to be fitted together here, and AccuracyCoin makes both. The restart
     * pulse is on dot 339: {@code Stale Sprite Shift Regs} pins it there by enabling rendering on
     * dot 340 and showing the units stay halted anyway. And the pulse is what an odd frame loses
     * when it shortens the pre-render line: {@code Sprites On Scanline 0} watches a sprite the
     * fetch loaded put its first pixel out at x=0 on every other frame, which only happens to a
     * unit nobody told to start counting. So the shortened line issues no pulse, whichever end of
     * it the missing dot is counted from.
     */
    private boolean isLineTheFrameCutsShort() {
        return region.skipsDotOnOddFrames() && scanline == preRenderLine && oddFrame;
    }

    /**
     * The X down-counters, one dot's worth.
     * <p>
     * These run whether or not rendering is enabled, which is the first half of AccuracyCoin's
     * {@code Stale Sprite Shift Regs}: switching the picture off for a few dots does not move a
     * sprite along the line, it comes back exactly where it would have been. They do not run
     * outside the visible dots, so a unit that halted near the right hand edge is still halted at
     * the start of the next line unless something puts it back to counting.
     */
    private void clockSpriteCounters() {
        if (dot < 1 || dot > SCREEN_WIDTH) {
            return;
        }

        for (var unit : spriteUnits) {
            unit.clockCounter();
        }

        extraSprites.clockCounters();
    }

    /**
     * The pattern shift registers, one dot's worth.
     * <p>
     * The other half of {@code Stale Sprite Shift Regs}: these run only while rendering, so the
     * dots a sprite spends in forced blank are dots it spends not being drawn rather than dots it
     * spends being drawn somewhere else. A unit that halted before rendering was switched off
     * picks up from exactly the pixel it had reached.
     */
    private void shiftSpriteUnits() {
        if (dot < 1 || dot > SCREEN_WIDTH || !isRenderingEnabled()) {
            return;
        }

        for (var unit : spriteUnits) {
            unit.shift();
        }

        extraSprites.shift();
    }

    private void clearSecondaryOAM() {
        // One byte every two dots: the odd dot reads (and always reads $FF), the even one writes.
        if ((dot & 1) == 0) {
            secondaryOAM[(dot >> 1) - 1] = 0xFF;
        }
    }

    /**
     * The sprite evaluation state machine: the hardware that walks OAM across dots 65 to 256 and
     * picks the eight sprites the next scanline will draw.
     * <p>
     * Written out literally rather than as a loop over sixty four sprites, because the interesting
     * behaviour is all in what happens when it runs out of time or out of slots -- and in the
     * overflow scan, which is documented hardware and is documented as being wrong.
     * <p>
     * What it deliberately does <em>not</em> own is the address it walks. {@link PPU#oamAddress} is
     * one register shared with $2003, $2004 and the fetch phase, and every quirk here is a
     * consequence of that sharing, so hiding a private copy of it in this class would be hiding the
     * whole point. It does not own {@link PPU#secondaryOAM} either, for the same reason.
     * <p>
     * Its fields travel in {@link PPU#serialize} rather than in a {@code serialize} of its own: the
     * order of that method is the file format, and it was settled while these were the PPU's.
     */
    private final class SpriteEvaluation {
        /**
         * Where in secondary OAM the next byte goes.
         */
        private int slot;

        /**
         * The byte read on the odd dot, acted on at the even one.
         */
        private int latch;

        /**
         * Which of a sprite's four bytes {@link #latch} is holding.
         */
        private EvaluationStep step = EvaluationStep.Y_POSITION;

        /**
         * The address the evaluation started from, which is wherever OAMADDR pointed when the
         * scanline reached dot 65. Sprite 0 hit is really "the first byte examined", not "sprite
         * number zero", and the two only differ when a game leaves OAMADDR somewhere else.
         */
        private int firstAddressExamined;

        private int spritesFound;

        /**
         * Whether the sprite that landed in the first secondary OAM slot was the first one
         * examined, and so the one that can set the sprite 0 hit flag.
         */
        private boolean foundSpriteZero;

        /**
         * One dot's worth: odd dots read a byte of OAM, even dots decide what to do with it.
         */
        private void tick() {
            if (dot == 65) {
                begin();
            }

            if ((dot & 1) == 1) {
                latch = oam.read(oamAddress);
                return;
            }

            if (step == EvaluationStep.FINISHED) {
                return;
            }

            // Once eight sprites are in hand nothing more is copied, but the hardware carries on
            // reading, and it is what it does with the address between reads that goes wrong.
            var full = spritesFound == 8;

            if (!full) {
                // Written before anyone knows whether the sprite is wanted. The slot is only kept
                // if it turns out to be, so an unwanted Y coordinate is overwritten by the next one.
                secondaryOAM[slot] = latch;
            }

            switch (step) {
                case Y_POSITION -> evaluateYPosition(full);
                case TILE, ATTRIBUTES -> copyByte(full);
                case X_POSITION -> evaluateXPosition(full);
            }
        }

        private void begin() {
            // "The OAM memory is refreshed once per scanline while rendering is enabled" -- and
            // this runs at dot 65 of a visible line with rendering on, which is exactly that
            // condition. So OAM only ever decays for a game that leaves rendering off for more
            // than a millisecond.
            oam.refreshEveryRow();

            // Wherever OAMADDR happens to be pointing. Evaluation walks it on from there and leaves
            // it wherever it finished, which is why $2004 read during dots 65 to 256 follows the
            // evaluation around rather than answering with whatever the CPU last asked for.
            firstAddressExamined = oamAddress;
            slot = 0;
            step = EvaluationStep.Y_POSITION;
            spritesFound = 0;
            foundSpriteZero = false;
        }

        /**
         * Decides whether the sprite this byte is the Y coordinate of belongs on the next scanline.
         */
        private void evaluateYPosition(final boolean full) {
            if (!isInRange(latch)) {
                skipSprite(full, 4);
                return;
            }

            if (full) {
                // No room for it, which is the only thing the overflow flag actually reports.
                spriteOverflow = true;
            } else {
                if (slot == 0) {
                    foundSpriteZero = oamAddress == firstAddressExamined;
                }

                slot++;
            }

            step = EvaluationStep.TILE;
            advance(1);
        }

        /**
         * The tile number and the attribute byte, which are copied without being looked at.
         */
        private void copyByte(final boolean full) {
            if (!full) {
                slot++;
            }

            step = step == EvaluationStep.TILE
                    ? EvaluationStep.ATTRIBUTES
                    : EvaluationStep.X_POSITION;

            advance(1);
        }

        /**
         * The X coordinate, which is copied -- and then put through the same in-range test the Y
         * coordinate was, for no reason anybody has ever found a use for.
         * <p>
         * An X that fails it moves the address on by one and then re-aligns, exactly as a rejected
         * Y does but a byte rather than four. With aligned OAM the two are the same thing, because
         * one more byte is where the next sprite starts anyway; it is only visible when a game has
         * left OAMADDR pointing into the middle of a sprite.
         */
        private void evaluateXPosition(final boolean full) {
            var inRange = isInRange(latch);

            if (!full) {
                slot++;
                spritesFound++;
            }

            step = EvaluationStep.Y_POSITION;

            if (inRange) {
                advance(1);
            } else {
                skipSprite(full, 1);
            }
        }

        /**
         * Moves past a sprite the scanline does not want.
         *
         * @param full whether secondary OAM has no room left, which is what stops the address being
         *             pulled back into alignment -- and makes it slip one byte further every time.
         * @param by   how far past the byte just rejected the next sprite starts, when the address
         *             is still being kept aligned. Four past a rejected Y coordinate, one past a
         *             rejected X, which is the same place either way for an address that was
         *             aligned already.
         */
        private void skipSprite(final boolean full, final int by) {
            if (!full) {
                advance(by);
                oamAddress &= 0xFC;

                return;
            }

            // With nowhere to put anything the two halves of the address stop agreeing. The sprite
            // number steps on, and so does the byte number -- but the byte number has no carry into
            // the sprite number and simply wraps, so every rejected sprite leaves the address a
            // byte further into the next one. What the hardware then tests as a Y coordinate is a
            // tile number, and after that an attribute byte, and after that an X: the sprite
            // overflow flag is answering a question about the wrong bytes, and games depend on
            // which ones.
            var nextSprite = (oamAddress & 0xFC) + 4;
            var nextByte = (oamAddress + 1) & 3;

            advance(nextSprite + nextByte - oamAddress);
        }

        /**
         * Moves OAMADDR on, ending the evaluation if it runs off the end of OAM.
         * <p>
         * Whatever is left of the scanline after that is spent reading the last address over and
         * over and throwing away what comes back.
         */
        private void advance(final int by) {
            var next = oamAddress + by;

            if (next > 0xFF) {
                step = EvaluationStep.FINISHED;
            }

            oamAddress = next & 0xFF;
        }

        /**
         * Where in secondary OAM this leaves the sprite hardware pointing, which is rounded up to a
         * multiple of four -- the four bytes of a sprite go across as a unit, so only a boundary is
         * ever caught -- and reads zero once there is no room left.
         */
        private int secondaryOAMAddress() {
            return spritesFound == 8 ? 0 : (slot + 3) & 0x1C;
        }

        /**
         * @return whether a sprite with this Y coordinate covers the scanline being evaluated.
         * Not the same test {@link PPU#fetchSpritePattern} makes a moment later: that one asks it
         * of the low eight bits of the scanline counter, and the difference is how a sprite reaches
         * scanline 0.
         */
        private boolean isInRange(final int y) {
            var row = scanline - y;
            return row >= 0 && row < spriteHeight();
        }
    }

    /**
     * Where in secondary OAM the sprite hardware is pointing, which depends only on where the beam
     * is.
     * <p>
     * The clear walks it up one place every other dot. The evaluation moves it as it copies, but a
     * seed taken from it then comes out rounded up to a multiple of four -- the four bytes of a
     * sprite go across as a unit, so only a boundary is ever caught -- and reads zero once
     * secondary OAM is full. The fetch resets it at dot 257 and then steps it three times in the
     * first half of each sprite's eight dots and once at the end of them.
     *
     * @return an address into the thirty two bytes of secondary OAM.
     */
    private int secondaryOAMAddress() {
        if (dot >= 1 && dot <= 64) {
            return (dot - 1) >> 1;
        }

        if (dot >= 65 && dot <= 256) {
            return evaluation.secondaryOAMAddress();
        }

        if (dot >= 257 && dot <= 320) {
            var offset = dot - 257;

            return ((offset >> 3) << 2) | Math.min(offset & 7, 3);
        }

        return 0;
    }

    /**
     * Copies the first row of OAM over the row the interrupted sprite hardware was pointing at.
     * <p>
     * Rendering switched off part way down the picture strands the sprite hardware mid-address.
     * Nothing happens while it stays off -- OAM reads back exactly as it was -- but the first dot
     * of rendering after that, on the pre-render line or a visible one, spends the stranded
     * address on a copy: eight bytes from row 0 over the eight at the seed, and the same one byte
     * move in secondary OAM. A game that switches rendering off in the middle of a line and back
     * on later loses a sprite to it, which is why the advice is to do it in blanking.
     * <p>
     * Seeding on row 0 costs nothing, because row 0 is what gets copied -- so this can never
     * disturb an ordinary sprite zero hit.
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_registers#OAMADDR_precautions">NESdev: OAMADDR
     * precautions</a>
     */
    private void corruptOAM() {
        corruptionPending = false;

        if (corruptionSeed == 0) {
            return;
        }

        var row = corruptionSeed * 8;
        oam.refreshRow(corruptionSeed);

        for (var i = 0; i < 8; i++) {
            oam.bytes[row + i] = oam.read(i);
        }

        secondaryOAM[corruptionSeed] = secondaryOAM[0];
    }

    private int spriteHeight() {
        return (ctrl & CTRL_TALL_SPRITES) != 0 ? 16 : 8;
    }

    /**
     * Fetches one bit plane of one sprite output unit from the slot of secondary OAM that feeds it.
     * <p>
     * The fetch asks the same "is this sprite on this line" question the evaluation asked, and asks
     * it of the low eight bits of the scanline counter rather than of the whole thing. A slot that
     * fails it still costs the read -- an unused one holds the $FF secondary OAM was wiped with, so
     * it reads row 0 of tile $FF, which is where the dummy fetches a mapper sees come from -- but
     * the byte is dropped and the shift register is loaded transparent instead.
     * <p>
     * Which is how a sprite reaches scanline 0. The pre-render line is line 261, and 261 in eight
     * bits is 5, so the fetch that runs at the bottom of it will happily load whatever is left in
     * secondary OAM from the line above the picture if that sprite covers <em>line 5</em>. Nothing
     * evaluated it and nothing put it there on purpose; it is simply still there.
     *
     * @param unit  which of the eight, 0 to 7.
     * @param plane 0 for the low bit plane, 8 for the high one.
     * @see <a href="https://forums.nesdev.org/viewtopic.php?t=26291">NESdev forums: sprites on scanline 0</a>
     */
    private int fetchSpritePattern(final int unit, final int plane) {
        var base = unit * 4;
        var attributes = secondaryOAM[base + 2];
        var row = (scanline & 0xFF) - secondaryOAM[base];

        var data = fetch(spriteFetchAddress(unit, plane));

        if (row < 0 || row >= spriteHeight()) {
            return 0;
        }

        // A horizontally flipped sprite is loaded into the shift register back to front rather
        // than shifted the other way.
        return (attributes & 0x40) != 0 ? reverseBits(data) : data;
    }

    /**
     * The address one of a unit's two pattern bytes is fetched from.
     * <p>
     * Answers for a slot the evaluation never filled as well, because the hardware spends the fetch
     * either way: the $FF secondary OAM was wiped with names tile $FF at a row nowhere near this
     * scanline, and the byte that comes back is thrown away rather than not read. Which is why this
     * is a separate answer from {@link #fetchSpritePattern} -- the address goes out on one dot and
     * the decision about the byte is made on the next.
     */
    private int spriteFetchAddress(final int unit, final int plane) {
        var base = unit * 4;
        var y = secondaryOAM[base];
        var tile = secondaryOAM[base + 1];
        var attributes = secondaryOAM[base + 2];

        var height = spriteHeight();
        var row = (scanline & 0xFF) - y;

        if (row < 0 || row >= height) {
            row = 0;
        }

        return spritePatternAddress(tile, attributes, row, height) + plane;
    }

    /**
     * Where the low bit plane of one row of one sprite lives. The high one is eight further on.
     * <p>
     * Address arithmetic and nothing else, which is why {@link ExtraSprites} can share it: no bus
     * cycle happens here, so the caller decides whether the cartridge is told about the address.
     *
     * @param tile       the tile number out of OAM.
     * @param attributes the attribute byte, of which only the vertical flip bit matters here.
     * @param row        which row of the sprite, 0 to {@code height - 1}, before any flip.
     * @param height     8 or 16.
     */
    private int spritePatternAddress(
            final int tile, final int attributes, final int row, final int height) {
        var line = (attributes & 0x80) != 0 ? height - 1 - row : row;

        if (height == 16) {
            // A tall sprite ignores $2000's table bit: the tile number's low bit picks the table
            // and the rest of it picks a pair of tiles, the second being the bottom half.
            var address = ((tile & 1) << 12) | ((tile & 0xFE) << 4);

            return address + (line >= 8 ? 16 + (line & 7) : line);
        }

        return ((ctrl & CTRL_SPRITE_TABLE) != 0 ? 0x1000 : 0x0000) | (tile << 4) | line;
    }

    /**
     * Turns a pattern byte back to front, which is how a horizontally flipped sprite is drawn:
     * the hardware loads the shift register the other way round rather than shifting the other
     * way.
     */
    private static int reverseBits(final int value) {
        return Integer.reverse(value) >>> 24;
    }

    /**
     * The sprites the hardware ran out of output units for, drawn anyway.
     * <p>
     * This is not a chip. The 2C02 has eight sprite output units and a scanline that wants a ninth
     * gets the overflow flag instead, which is why so many games flicker their sprites -- rotating
     * which of them is dropped, so that all of them are visible half the time. Switching this on
     * puts the dropped ones on screen as well, and the flicker stops.
     * <p>
     * The reason a game cannot tell is that nothing here touches anything a game can reach. The
     * evaluation, secondary OAM, the eight real units and every bus cycle they make are left
     * exactly as they were, and this runs afterwards on the results. The overflow flag still rises,
     * $2004 still answers with whatever the sprite hardware is holding, and sprite 0 hit is still
     * sprite 0's. OAM is read through {@link OAM#peek} and the patterns through {@link VRAM#peek},
     * so no row of OAM is refreshed that would have decayed and MMC3's counter never sees an
     * address that would not have been there. What changes is the picture and nothing else.
     * <p>
     * Inner rather than static because it is all borrowed: the beam position, $2000, OAM, the PPU
     * bus, and the evaluation's own answer for the line.
     */
    private final class ExtraSprites {
        /**
         * Whether anybody has asked for this. Default off, and not part of the machine -- it
         * belongs to whoever is watching, like the two layer switches.
         */
        private boolean enabled;

        /**
         * Sixty four sprites in OAM, less the eight the hardware has units of its own for, which is
         * as many as can ever be left over on one scanline.
         */
        private final SpriteUnit[] units = new SpriteUnit[56];

        /**
         * How many of them the line being drawn is using. Everything below loops to here rather
         * than over the array, so a machine with the hack switched off spends one comparison a dot
         * on it.
         */
        private int count;

        private ExtraSprites() {
            Arrays.setAll(units, i -> new SpriteUnit());
        }

        /**
         * Picks up whatever the evaluation had to leave behind, once per scanline at dot 320.
         * <p>
         * Only when eight sprites were found: fewer means nothing was dropped, and the flag the
         * hardware raises when it drops one is exactly the condition being undone here. The
         * pre-render line evaluates nothing, so it has nothing to leave behind either -- the stale
         * secondary OAM that lets a sprite reach scanline 0 is the real units' business and stays
         * theirs.
         */
        private void scan() {
            count = 0;

            if (!enabled || scanline == preRenderLine || evaluation.spritesFound < 8) {
                return;
            }

            var height = spriteHeight();

            // The first eight matches are already in the real units, and the walk starts wherever
            // OAMADDR pointed when the evaluation did, so a game that moved it does not have
            // sprites resurrected from in front of where the hardware began looking. Four bytes at
            // a time, which is an approximation only for a game that left OAMADDR misaligned: the
            // hardware would have read those bytes out of step, and this reads sprites.
            var skip = 8;

            for (var address = evaluation.firstAddressExamined & 0xFC;
                 address < 0x100;
                 address += 4) {
                var row = scanline - oam.peek(address);

                if (row < 0 || row >= height) {
                    continue;
                }

                if (skip > 0) {
                    skip--;
                    continue;
                }

                var tile = oam.peek(address + 1);
                var attributes = oam.peek(address + 2);
                var unit = units[count++];

                unit.counter = oam.peek(address + 3);
                unit.attributes = attributes;
                unit.patternLow = pattern(tile, attributes, row, height, 0);
                unit.patternHigh = pattern(tile, attributes, row, height, 8);
            }
        }

        /**
         * One bit plane of one sprite, read past the cartridge rather than through it.
         *
         * @see VRAM#peek(int)
         */
        private int pattern(
                final int tile,
                final int attributes,
                final int row,
                final int height,
                final int plane) {
            var data = vram.peek(spritePatternAddress(tile, attributes, row, height) + plane);

            return (attributes & 0x40) != 0 ? reverseBits(data) : data;
        }

        private void clockCounters() {
            for (var i = 0; i < count; i++) {
                units[i].clockCounter();
            }
        }

        private void shift() {
            for (var i = 0; i < count; i++) {
                units[i].shift();
            }
        }

        /**
         * Puts them back to counting, on the same dot the real units are put back to counting on.
         */
        private void release() {
            for (var i = 0; i < count; i++) {
                units[i].halted = false;
            }
        }

        /**
         * @return the first of these putting out an opaque pixel, or null. Only asked once all
         * eight real units have come out transparent, which is what keeps the answer in OAM order:
         * every sprite here is later in OAM than every sprite there.
         */
        private SpriteUnit firstOpaque() {
            for (var i = 0; i < count; i++) {
                if (units[i].pixel() != 0) {
                    return units[i];
                }
            }

            return null;
        }

        /**
         * The units travel, and {@link #enabled} does not.
         * <p>
         * Not because a save state cares what the picture looked like, but because a state can be
         * taken half way down a scanline -- a REPL breakpoint, or the debugger's step -- and
         * resuming from one has to draw the rest of that line the same way running straight through
         * would have. {@link #count} rather than {@code enabled} is what everything above reads, so
         * a state loaded into a machine with the hack switched off still finishes the line it was
         * in the middle of and then quietly stops finding any.
         */
        private void serialize(final StateIO io) {
            count = Math.min(io.u8(count), units.length);

            // One field across all of them at a time, which is how the eight real units are
            // written a few lines above this.
            for (var unit : units) {
                unit.counter = io.u8(unit.counter);
            }

            for (var unit : units) {
                unit.attributes = io.u8(unit.attributes);
            }

            for (var unit : units) {
                unit.patternLow = io.u8(unit.patternLow);
            }

            for (var unit : units) {
                unit.patternHigh = io.u8(unit.patternHigh);
            }

            for (var unit : units) {
                unit.halted = io.bool(unit.halted);
            }
        }
    }

    // ================================================================ pixel output

    /**
     * Works out which colour one pixel is and writes it into the framebuffer -- the index, not the
     * colour itself. See {@link #getFrameBuffer()}.
     */
    private void renderPixel() {
        var x = dot - 1;
        int entry;

        if (!isRenderingEnabled()) {
            // Nothing is being rendered, so the screen shows the backdrop -- read from wherever
            // the VRAM address happens to point when it is inside palette RAM rather than from
            // $3F00. That is the "background palette hack" games use to flash the screen a colour
            // without writing the palette.
            entry = readPalette((v & 0x3F00) == 0x3F00 ? v : 0x3F00);
        } else {
            entry = readPalette(0x3F00 | multiplex(x, backgroundPixel(x)));
        }

        frameBuffer[scanline * SCREEN_WIDTH + x] = toPixel(entry);
    }

    /**
     * Decides between the background pixel and whichever sprite is over it, and sets the sprite 0
     * hit flag if the two of them are what does it.
     * <p>
     * Sprites are considered in slot order, which is the order they appear in OAM, and the first
     * opaque one wins -- a lower numbered sprite covers a higher numbered one even when the
     * higher one is in front of the background. Then the winner's priority bit decides whether it
     * is drawn over the background or behind it.
     *
     * @param x          the pixel's position along the scanline.
     * @param background the background's palette offset, or zero if it is transparent here.
     * @return the low five bits of the palette address to draw, zero meaning the backdrop.
     */
    private int multiplex(final int x, final int background) {
        SpriteUnit winner = null;
        var isSpriteZero = false;
        var colour = 0;

        if ((mask & MASK_SHOW_SPRITES) != 0 && (x >= 8 || (mask & MASK_SHOW_SPRITES_LEFT) != 0)) {
            for (var i = 0; i < spriteUnits.length && winner == null; i++) {
                if (spriteUnits[i].pixel() != 0) {
                    winner = spriteUnits[i];
                    isSpriteZero = i == 0;
                }
            }

            // Only once the hardware's own eight have all come out transparent, which is what
            // keeps first-opaque-wins meaning the same thing: every extra sprite is later in OAM
            // than every real one, so a real unit would have won anyway.
            if (winner == null) {
                winner = extraSprites.firstOpaque();
            }

            if (winner != null) {
                colour = winner.pixel();
            }
        }

        // From here down the debug layer switches take part, but only in what is returned: the
        // sprite search above has already run, and the hit flag below still uses the real
        // background pixel, so a hidden layer stays invisible to the game itself.
        var drawnBackground = backgroundLayerVisible ? background : 0;

        if (winner == null) {
            return drawnBackground;
        }

        // The hit is about two opaque pixels meeting, not about which of them is drawn, so a
        // sprite hidden behind the background still sets it. The last pixel of the line never
        // does, for reasons lost with the hardware.
        if (isSpriteZero && spriteZeroOnThisLine && background != 0 && x != SCREEN_WIDTH - 1) {
            spriteZeroHit = true;
        }

        var attributes = winner.attributes;

        if (background != 0 && (attributes & 0x20) != 0) {
            return drawnBackground;
        }

        if (!spriteLayerVisible) {
            return drawnBackground;
        }

        return 0x10 | ((attributes & 0x03) << 2) | colour;
    }

    /**
     * @param x the pixel's position along the scanline.
     * @return the low four bits of a palette address -- palette number in bits 3-2 and colour
     * within it in bits 1-0 -- or zero if the background is transparent here.
     */
    private int backgroundPixel(final int x) {
        if ((mask & MASK_SHOW_BACKGROUND) == 0) {
            return 0;
        }

        if (x < 8 && (mask & MASK_SHOW_BACKGROUND_LEFT) == 0) {
            return 0;
        }

        return background.pixel(fineX);
    }

    /**
     * Turns a palette entry into the value the framebuffer carries, applying the two things $2001
     * can do to a colour on its way out of the chip: drop the hue, and set the emphasis bits.
     * <p>
     * Both belong here rather than in the front end, because the hardware really does force the
     * index down and really does put those three bits on the wire. What the resulting signal looks
     * like on a television is somebody else's problem -- see
     * {@code com.github.dimiro1.mynes.palette.NESPalette}.
     *
     * @return {@code emphasis << 6 | entry}, 0 to 511.
     */
    private int toPixel(final int entry) {
        return ((mask & 0xE0) << 1) | (entry & greyscaleMask());
    }

    // ================================================================ CPU facing registers

    /**
     * Reads one of the eight registers mapped into the CPU address space at $2000-$2007.
     *
     * @param register the register number, 0 to 7.
     * @return the byte the CPU sees, open bus bits and all.
     */
    public int read(final int register) {
        return switch (register & 7) {
            case 2 -> readStatus();
            case 4 -> readOAMData();
            case 7 -> readData();
            // $2000, $2001, $2003, $2005 and $2006 are write only. The PPU does not drive the
            // data bus at all, so the CPU reads back the decaying charge left on it, and reading
            // does not refresh it.
            default -> openBus.read();
        };
    }

    /**
     * Writes one of the eight registers mapped into the CPU address space at $2000-$2007.
     *
     * @param register the register number, 0 to 7.
     * @param data     the byte to write.
     */
    public void write(final int register, final int data) {
        var index = register & 7;
        var value = data & 0xFF;

        // Every write puts the whole byte on the PPU's data pins, including a write to the
        // read-only status register and including one the warm-up window is about to drop: the
        // byte is on the pins whether or not the PPU acts on it.
        openBus.drive(value, 0xFF);

        if (warmingUp && (WARM_UP_IGNORED_REGISTERS & (1 << index)) != 0) {
            // Dropped here rather than inside the handlers, which is also what keeps the write
            // latch $2005 and $2006 share from toggling.
            return;
        }

        switch (index) {
            case 0 -> writeCtrl(value);
            case 1 -> {
                pendingMask = value;
                maskDelay = MASK_WRITE_DELAY_DOTS;
            }
            case 3 -> oamAddress = value;
            case 4 -> writeOAMData(value);
            case 5 -> writeScroll(value);
            case 6 -> writeAddress(value);
            case 7 -> writeData(value);
            default -> { /* $2002 is read only */ }
        }
    }

    /**
     * Reads a register without any of the side effects a real read would have.
     * <p>
     * For tests and debuggers: reading $2002 for real clears the VBlank flag and the write latch,
     * and reading $2007 moves the address on, so nothing that merely wants to look at the PPU can
     * go through {@link #read(int)}.
     *
     * @param register the register number, 0 to 7.
     * @return the byte a read would have returned.
     */
    public int peek(final int register) {
        // openBus.value rather than openBus.read(), because a read is what applies the decay and a
        // debugger must not be the thing that clears a bit.
        return switch (register & 7) {
            case 2 -> status() | (openBus.value & 0x1F);
            case 4 -> oam.peek(oamAddress);
            case 7 -> (v & 0x3FFF) >= 0x3F00
                    ? (openBus.value & 0xC0) | (readPalette(v) & greyscaleMask())
                    : readBuffer;
            default -> openBus.value;
        };
    }

    private void writeCtrl(final int value) {
        ctrl = value;

        // The two nametable bits are bits 10 and 11 of the temporary address.
        t = (t & 0xF3FF) | ((value & 0x03) << 10);

        // Enabling NMI while the VBlank flag is already up asserts the line straight away, and a
        // game that toggles the bit off and on again during VBlank gets a second interrupt.
        updateNMILine();
    }

    /**
     * Reads $2002.
     * <p>
     * Three things happen besides handing over the flags: the VBlank flag is cleared, the
     * $2005/$2006 write latch is reset, and -- if the read lands on the exact dot the flag was
     * about to be set on -- the flag is stopped from being set at all.
     */
    private int readStatus() {
        var value = statusAsRead() | (openBus.read() & 0x1F);

        if (scanline == VBLANK_START_LINE && dot == STATUS_DOT) {
            preventVBlankFlag = true;
        }

        vblankFlag = false;
        writeLatch = false;
        updateNMILine();

        openBus.drive(value, 0xE0);
        return value;
    }

    private int status() {
        return (vblankFlag ? STATUS_VBLANK : 0)
                | (spriteZeroHit ? STATUS_SPRITE_ZERO_HIT : 0)
                | (spriteOverflow ? STATUS_SPRITE_OVERFLOW : 0);
    }

    /**
     * The same three flags, but sampled the way a CPU read of $2002 samples them -- which is not
     * all at the same instant.
     * <p>
     * A read happens while M2 is high, and the PPU latches the VBlank flag as M2 rises. The sprite
     * flags are not latched, so what the CPU carries away is whatever they are when M2 falls again
     * at the end of the read. On the NTSC 2A03 that high phase is a 15/24 duty cycle -- one and
     * seven-eighths PPU cycles -- and on the PAL 2A07 it is 19/32, or 1.9 of the 3.2 dots in a
     * cycle. Either way M2 falls part way through the <em>next</em> dot, so the sprite bits come
     * from one dot later than the VBlank bit.
     * <p>
     * All three are cleared together on dot 1 of the pre-render line, so the only thing that dot
     * of separation shows is a read straddling it: VBlank comes back still set, the sprite flags
     * already cleared. That is the whole of AccuracyCoin's {@code $2002 flag timing} test, and it
     * is why the answer is not to move the sprite flags' clear a dot earlier -- the clear is on
     * dot 1 like the wiki says, and it is the read that is late.
     * <p>
     * The look-ahead is only over the clear. Setting a sprite flag a dot ahead of time would mean
     * rendering the dot to find out, and the hardware's own answer there is a hair either side of
     * a dot boundary depending on how the two clocks powered up -- which is why the ROM accepts
     * two answers for the sample that lands on it.
     *
     * @see <a href="https://www.nesdev.org/wiki/CPU_pinout">CPU pinout: M2</a>
     */
    private int statusAsRead() {
        // dot is the one M2 falls in: the cycle's own dots have already been run.
        var spriteFlagsClearing = scanline == preRenderLine && dot == STATUS_DOT;

        return (vblankFlag ? STATUS_VBLANK : 0)
                | (spriteZeroHit && !spriteFlagsClearing ? STATUS_SPRITE_ZERO_HIT : 0)
                | (spriteOverflow && !spriteFlagsClearing ? STATUS_SPRITE_OVERFLOW : 0);
    }

    /**
     * Reads $2004, which is a plain window onto OAM at the current address and does not move it.
     * <p>
     * The one exception is the first 64 dots of a visible scanline with rendering on: the sprite
     * evaluation hardware is busy filling secondary OAM with $FF, and that is what a read sees.
     */
    private int readOAMData() {
        var value = openOAMBus();

        openBus.drive(value, 0xFF);
        return value;
    }

    /**
     * What $2004 answers with.
     * <p>
     * Outside rendering it is a plain read of OAM, and the address does not move -- that much is
     * the register as documented. During rendering it is not a read of OAM at all: the register is
     * wired to whatever the sprite hardware is doing with memory on that dot, and the CPU sees the
     * traffic rather than a value it asked for. Micro Machines reads it for exactly that.
     * <p>
     * So the answer depends only on where the beam is. Dots 1 to 64 are the clear, which is
     * implemented as a read that is forced to return $FF. Dots 65 to 256 are the evaluation,
     * walking primary OAM from wherever OAMADDR was left. Dots 257 onwards are the fetch, which
     * reads <em>secondary</em> OAM -- four bytes of a sprite, and then its X coordinate four times
     * more while the pattern fetches happen. A game with nothing on the line reads $FF throughout
     * that, because the clear put $FF there and evaluation found nothing to overwrite it with.
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_sprite_evaluation">NESdev: sprite evaluation</a>
     */
    private int openOAMBus() {
        if (!isRenderingEnabled() || !isRenderingLine()) {
            return oam.read(oamAddress);
        }

        if (dot >= 1 && dot <= 64) {
            return 0xFF;
        }

        // The fetch, and then the tail of the line where the background pipeline is being primed
        // and the only thing still reading secondary OAM reads the first byte of it over and over.
        // Both are just "wherever the counter has got to", which is why this asks rather than works
        // it out again: the window $2004 reads through and the seed the corruption is taken from
        // have to be the same counter, or one of them is describing hardware that does not exist.
        if (dot >= 257) {
            return secondaryOAM[secondaryOAMAddress()];
        }

        return oam.read(oamAddress);
    }

    /**
     * Writes $2004.
     * <p>
     * During rendering the sprite evaluation hardware owns OAM, so the byte is dropped -- but the
     * address still moves, and by four rather than one, because what the write actually clocks is
     * the sprite counter rather than the byte counter.
     * <p>
     * It also loses the byte counter on the way. NESdev has the increment bumping the high six
     * bits and leaves the low two an open question; AccuracyCoin's Address $2004 behavior test 10
     * answers it, and the answer is that they are cleared. Only a game that had left OAMADDR
     * pointing into the middle of a sprite could tell the difference.
     */
    private void writeOAMData(final int value) {
        if (isRenderingEnabled() && isRenderingLine()) {
            oamAddress = (oamAddress + 4) & 0xFC;
            return;
        }

        // Bits 2 to 4 of a sprite's attribute byte do not exist: there are no RAM cells behind
        // them, so they read back as zero no matter what was written. Masking here rather than on
        // the read path means OAM DMA, which funnels through this same method, is covered too.
        oam.write(oamAddress, (oamAddress & 3) == 2 ? value & 0xE3 : value);
        oamAddress = (oamAddress + 1) & 0xFF;
    }

    /**
     * Writes $2005, the scroll register. Two writes: X then Y.
     */
    private void writeScroll(final int value) {
        if (!writeLatch) {
            fineX = value & 0x07;
            t = (t & 0x7FE0) | (value >> 3);
            writeLatch = true;
            return;
        }

        t = (t & 0x0C1F) | ((value & 0x07) << 12) | ((value & 0xF8) << 2);
        writeLatch = false;
    }

    /**
     * Writes $2006, the VRAM address register. Two writes: high byte then low.
     * <p>
     * The high byte only carries six bits, so the first write clears bit 14 of the temporary
     * address as a side effect. The second write copies the whole thing into {@link #v}, a dot or
     * two later: see {@link #addressDelay}. The copy is also what reaches the cartridge, which is
     * how a game with rendering switched off clocks an MMC3's scanline counter -- two $2006
     * writes are enough to make A12 rise, a couple of dots after the second one.
     */
    private void writeAddress(final int value) {
        if (!writeLatch) {
            t = (t & 0x00FF) | ((value & 0x3F) << 8);
            writeLatch = true;
            return;
        }

        // Before the load is scheduled rather than after, so that an increment a $2007 read owed
        // lands on the counter this write is about to replace and is lost with it -- which is the
        // same thing that happens to a coarse X increment caught in the gap. Nothing a program can
        // write gets in this window; a test writing registers with no dots in between does.
        settleOwedIncrement();

        t = (t & 0x7F00) | value;
        addressDelay = ADDRESS_WRITE_DELAY_DOTS;
        writeLatch = false;
    }

    /**
     * Reads $2007.
     * <p>
     * Everything but palette RAM comes back one read late, through {@link #readBuffer}: the PPU
     * needs a bus cycle to fetch the byte, so it hands over the previous one and starts the fetch
     * for the next. Palette RAM is inside the chip and needs no bus cycle, so it comes back
     * immediately -- but the fetch still happens, from the nametable that lies under the palette
     * in the address space, so the buffer is left holding that instead. The palette address itself
     * goes on the bus for it: only thirteen of the fourteen lines reach the nametable RAM, but A12
     * is high and a mapper watching the bus sees that, so reading $3F05 and reading $2F05 fetch the
     * same byte and differ only in what the cartridge saw go past.
     * <p>
     * <b>Starts</b> the fetch rather than doing it. It happens a few dots later, and this is where
     * the address it will use is taken, because {@link #v} has moved on by then. See
     * {@link #DATA_FETCH_DOTS}.
     */
    private int readData() {
        var address = v & 0x3FFF;
        int value;

        if (address >= 0x3F00) {
            value = (openBus.read() & 0xC0) | (readPalette(address) & greyscaleMask());
            openBus.drive(value, 0x3F);
        } else {
            value = readBuffer;
            openBus.drive(value, 0xFF);
        }

        settleOwedIncrement();

        dataFetchAddress = address;
        dataFetch = DATA_FETCH_DOTS;
        incrementOwed = true;

        return value;
    }

    private void writeData(final int value) {
        settleOwedIncrement();

        var address = v & 0x3FFF;

        if (address >= 0x3F00) {
            // Unlike a palette read, which still fetches the nametable byte underneath, this
            // never reaches the bus in this model, so a mapper watching the address lines does
            // not see it. Only something clocking A12 through palette writes would notice.
            writePalette(address, value);
        } else {
            vram.write(address, value);
        }

        incrementAddress();
    }

    /**
     * Takes the increment a $2007 read owed but has not had its dot for yet.
     * <p>
     * The wait exists so that the fetch happens before the counter moves, and nothing a program can
     * do gets between the two -- except another access to the same register, which two things
     * manage. A transfer that halts the CPU on a read of $2007 makes it re-issue the read every
     * cycle it is held off the bus, and every one of those moves the counter on, which is what
     * {@code DMA + $2007 Read} counts. And {@code STA $2000,Y} with Y=7 issues a dummy read of
     * $2007 and then writes to it a cycle later, which blargg's {@code test_ppu_read_buffer}
     * insists lands one address further on than the read did.
     * <p>
     * So the increment is taken here rather than lost. It is early by a dot or two, but only ever
     * by less than the access that asked for it took.
     */
    private void settleOwedIncrement() {
        if (!incrementOwed) {
            return;
        }

        incrementOwed = false;
        incrementAddress();
    }

    /**
     * Moves the VRAM address on after a $2007 access.
     * <p>
     * Normally by one or by 32, whichever $2000 asked for. But during rendering {@link #v} is not
     * an address at all, it is the counter the fetch pipeline is walking, and the two increment
     * circuits the pipeline uses fire instead: coarse X moves on and Y moves on, together. The
     * result is neither of the two increments the program asked for, which is why writing $2007
     * mid-frame is a well known way to scramble the scroll position.
     * <p>
     * Whichever way it moved, the new address is left sitting on the PPU bus, because outside
     * rendering there is nothing else driving it. A game with the picture switched off can
     * therefore make A12 rise by reading $2007 with the address one short of $1000, and blargg's
     * {@code 3-A12_clocking} checks that it can.
     */
    private void incrementAddress() {
        if (isRenderingEnabled() && isRenderingLine()) {
            incrementCoarseX();
            incrementY();
        } else {
            v = (v + ((ctrl & CTRL_INCREMENT_32) != 0 ? 32 : 1)) & 0x7FFF;
        }

        mapper.ppuAddress(v & 0x3FFF);
    }

    // ================================================================ object attribute memory

    /**
     * The 256 bytes of sprite memory, and the charge holding them there.
     * <p>
     * OAM is DRAM with no refresh circuit of its own, so the only thing that keeps it alive is
     * being read or written -- which is why every access here goes through {@link #read} or
     * {@link #write} rather than at the array, and why the clock those two consult is the PPU's
     * own {@link PPU#clock} rather than anything this class could keep for itself.
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_OAM">NESdev: PPU OAM</a>
     */
    private final class OAM {
        private final int[] bytes = new int[256];

        /**
         * The dot each eight byte row was last refreshed on. Per row rather than per byte because
         * that is how the DRAM is wired: touching any byte of a row refreshes all eight of them.
         */
        private final long[] refreshedOn = new long[32];

        /**
         * Reads a byte, refreshing the row it lives in.
         */
        private int read(final int address) {
            refreshRow(address >> 3);
            return bytes[address];
        }

        /**
         * Writes a byte, refreshing the row it lives in.
         */
        private void write(final int address, final int value) {
            refreshRow(address >> 3);
            bytes[address] = value;
        }

        /**
         * Reads a byte without refreshing anything, for debug UIs.
         * <p>
         * A debugger that kept OAM alive by looking at it would hide the decay it was there to
         * watch, which is the whole reason this is not {@link #read}.
         */
        private int peek(final int address) {
            return bytes[address & 0xFF];
        }

        /**
         * Lets a row decay if it has gone too long untouched, and then starts its clock again.
         * <p>
         * Sprite evaluation refreshes every row once per scanline, but only while rendering is
         * enabled; a row left alone for longer than {@link Region#oamDecayDots()} loses its charge
         * and reads back as zero. Zeroing the array here rather than masking on the way out is what
         * keeps sprite evaluation, $2004 and OAM DMA all seeing the same OAM.
         */
        private void refreshRow(final int row) {
            if (clock - refreshedOn[row] >= oamDecayDots) {
                Arrays.fill(bytes, row * 8, row * 8 + 8, 0);
            }

            refreshedOn[row] = clock;
        }

        /**
         * The whole chip at once, which is what a scanline of rendering amounts to.
         */
        private void refreshEveryRow() {
            for (var row = 0; row < refreshedOn.length; row++) {
                refreshRow(row);
            }
        }
    }

    // ================================================================ palette RAM

    /**
     * Folds a palette address onto the thirty two bytes that back it.
     * <p>
     * $3F00-$3FFF is the same 32 bytes over and over, and within those, the first entry of each
     * sprite palette is the same cell as the first entry of the matching background palette:
     * $3F10 is $3F00, $3F14 is $3F04, and so on. Those four cells are the backdrop colour, which
     * is why writing $3F10 changes the screen background.
     */
    private static int paletteIndex(final int address) {
        var index = address & 0x1F;
        return (index & 0x13) == 0x10 ? index & 0x0F : index;
    }

    private int readPalette(final int address) {
        return palette[paletteIndex(address)];
    }

    private void writePalette(final int address, final int value) {
        // The cells are six bits wide; the top two bits are simply not stored.
        palette[paletteIndex(address)] = value & 0x3F;
    }

    /**
     * @return the mask a palette entry passes through on its way out, which drops the hue when
     * the greyscale bit is set.
     */
    private int greyscaleMask() {
        return (mask & MASK_GREYSCALE) != 0 ? 0x30 : 0x3F;
    }

    // ================================================================ open bus

    /**
     * The PPU's own open bus: a row of eight tiny capacitors on the data pins.
     * <p>
     * Reading a write-only register, or a bit of a register the PPU does not drive, comes back as
     * whatever is still charged here. Kept apart from {@link OAM}'s decay despite the family
     * resemblance -- this one is per bit and per frame and leaves what it has alone, that one is
     * per row and per dot and zeroes eight bytes at a time.
     */
    private final class OpenBus {
        private int value;

        /**
         * The frame each bit was last refreshed on. Per bit rather than per byte because different
         * reads drive different bits.
         */
        private final long[] refreshedOn = new long[8];

        /**
         * Reads the latch, letting any bit that has gone too long without a refresh decay to zero
         * first.
         */
        private int read() {
            for (var bit = 0; bit < 8; bit++) {
                if (frame - refreshedOn[bit] >= OPEN_BUS_DECAY_FRAMES) {
                    value &= ~(1 << bit);
                }
            }

            return value;
        }

        /**
         * Drives some of the data pins, which both sets those bits and starts their decay over.
         *
         * @param data the byte being driven.
         * @param mask which bits of it the PPU actually drives; the rest keep their old charge.
         */
        private void drive(final int data, final int mask) {
            value = (value & ~mask) | (data & mask);

            for (var bit = 0; bit < 8; bit++) {
                if ((mask & (1 << bit)) != 0) {
                    refreshedOn[bit] = frame;
                }
            }
        }
    }

    // ================================================================ scroll counters

    /**
     * Moves the coarse X part of {@link #v} on by one tile, flipping to the horizontally adjacent
     * nametable when it runs off the end of the current one.
     */
    private void incrementCoarseX() {
        if ((v & 0x001F) == 31) {
            v &= ~0x001F;
            v ^= 0x0400;
            return;
        }

        v++;
    }

    /**
     * Moves {@link #v} on by one scanline.
     * <p>
     * Fine Y counts to seven and carries into coarse Y, which wraps at 29 rather than at 31,
     * because a nametable is 30 tiles tall and the last two rows of the 32 are the attribute
     * table. Setting coarse Y past 29 by hand is legal and does not flip the nametable when it
     * wraps -- it just reads attribute bytes as if they were tiles.
     */
    private void incrementY() {
        if ((v & 0x7000) != 0x7000) {
            v += 0x1000;
            return;
        }

        v &= ~0x7000;
        var coarseY = (v & 0x03E0) >> 5;

        if (coarseY == 29) {
            coarseY = 0;
            v ^= 0x0800;
        } else if (coarseY == 31) {
            coarseY = 0;
        } else {
            coarseY++;
        }

        v = (v & ~0x03E0) | (coarseY << 5);
    }

    // ================================================================ predicates

    /**
     * @return true when either the background or the sprites are switched on, which is what the
     * PPU means by "rendering" -- the fetch pipeline, the scroll counters and the sprite
     * evaluation hardware all run together or not at all.
     */
    public boolean isRenderingEnabled() {
        return (mask & MASK_RENDERING) != 0;
    }

    /**
     * @return true on the scanlines the fetch pipeline runs on: the visible ones and the
     * pre-render line, but not the post-render line or VBlank.
     */
    private boolean isRenderingLine() {
        return scanline < POST_RENDER_LINE || scanline == preRenderLine;
    }

    private void updateNMILine() {
        bus.setNMILine(vblankFlag && (ctrl & CTRL_NMI_ENABLE) != 0);
    }

    // ================================================================ inspection

    /**
     * Reads or writes the chip, the nametable RAM behind it, and everything in flight.
     * <p>
     * The long tail here is what makes a save state land mid-frame rather than only between frames.
     * A frame boundary is not a quiet moment for this chip: the background shift registers are
     * holding two tiles, the sprite evaluation state machine is partway through a scan, and eight
     * sprite output units are loaded for the line about to be drawn. All of it is on the list.
     * <p>
     * Three things deliberately are not:
     * <ul>
     *   <li><strong>The framebuffer</strong>, which travels as its own optional chunk. Every visible
     *       pixel is rewritten every frame whether or not rendering is on, so it is not needed for
     *       correctness -- only so that loading a state shows the picture it was taken on rather
     *       than the one that was already on screen.</li>
     *   <li><strong>The /NMI output</strong>, which is not a field at all: {@link #updateNMILine()}
     *       computes it from {@link #vblankFlag} and {@link #ctrl}, so it is recomputed on the way
     *       in rather than restored. The CPU's own latches are a different matter and are in its
     *       chunk.</li>
     *   <li><strong>The two layer switches</strong>, and {@link ExtraSprites#enabled} and
     *       {@link #overclock} beside them, which belong to whoever is watching rather than to the
     *       machine. Restoring them would hide a layer while the Debug menu still said it was
     *       showing. {@link #extraLine} <em>is</em> on the list, being where the beam is rather
     *       than what anybody asked for.</li>
     * </ul>
     * The two decay tables come with {@link #clock} and {@link #frame}, which is what they are
     * measured against -- a table restored without its clock would decay at the wrong time.
     * <p>
     * It stays one flat list even where the fields now belong to a sub-unit, because the order of
     * this method is the file format and it was settled before there were sub-units to group them
     * into. {@link Background} is the exception, and only because its eight fields already sat
     * together in exactly the order it writes them; {@link ExtraSprites} is the other, and only
     * because it arrived after the end of the list and so had nowhere else to go.
     *
     * @see com.github.dimiro1.mynes.state.SaveState
     */
    public void serialize(final StateIO io) {
        scanline = io.u16(scanline);
        dot = io.u16(dot);
        frame = io.u64(frame);
        clock = io.u64(clock);
        oddFrame = io.bool(oddFrame);
        warmingUp = io.bool(warmingUp);
        colourPhase = io.u8(colourPhase);
        framePhase = io.u8(framePhase);

        ctrl = io.u8(ctrl);
        mask = io.u8(mask);
        pendingMask = io.u8(pendingMask);
        maskDelay = io.u8(maskDelay);
        vblankFlag = io.bool(vblankFlag);
        spriteZeroHit = io.bool(spriteZeroHit);
        spriteOverflow = io.bool(spriteOverflow);
        preventVBlankFlag = io.bool(preventVBlankFlag);

        v = io.u16(v);
        t = io.u16(t);
        addressDelay = io.u8(addressDelay);
        fineX = io.u8(fineX);
        writeLatch = io.bool(writeLatch);
        readBuffer = io.u8(readBuffer);

        oamAddress = io.u8(oamAddress);
        io.bytes(oam.bytes);
        io.longs(oam.refreshedOn);
        io.bytes(palette);

        background.serialize(io);

        io.bytes(secondaryOAM);
        // A hole where the evaluation kept its own copy of the OAM address, and another where it
        // kept a byte index, before the two became the one address the hardware actually has.
        io.skip(2);

        evaluation.slot = io.u8(evaluation.slot);
        evaluation.latch = io.u8(evaluation.latch);
        evaluation.step = io.enumeration(evaluation.step, EvaluationStep.class);

        // And a second, where the overflow scan kept its own count of the bytes still to read.
        io.skip(1);

        evaluation.firstAddressExamined = io.u8(evaluation.firstAddressExamined);
        corruptionPending = io.bool(corruptionPending);
        corruptionSeed = io.u8(corruptionSeed);
        evaluation.spritesFound = io.u8(evaluation.spritesFound);
        evaluation.foundSpriteZero = io.bool(evaluation.foundSpriteZero);
        spriteZeroOnThisLine = io.bool(spriteZeroOnThisLine);

        // Where the count of sprites on the line used to be. The output units answer that for
        // themselves now: an empty slot is one whose counter never reaches a pattern worth drawing.
        io.skip(1);

        // One field across all eight units at a time, rather than one unit at a time. The order of
        // this method is the file format, and these were five parallel arrays when it was settled.
        for (var unit : spriteUnits) {
            unit.counter = io.u8(unit.counter);
        }

        for (var unit : spriteUnits) {
            unit.attributes = io.u8(unit.attributes);
        }

        for (var unit : spriteUnits) {
            unit.patternLow = io.u8(unit.patternLow);
        }

        for (var unit : spriteUnits) {
            unit.patternHigh = io.u8(unit.patternHigh);
        }

        for (var unit : spriteUnits) {
            unit.halted = io.bool(unit.halted);
        }

        openBus.value = io.u8(openBus.value);
        io.longs(openBus.refreshedOn);

        // The nametables, which live on the other side of the bus this chip owns. In here rather
        // than in a chunk of their own because nothing else can reach them.
        vram.serialize(io);

        // Appended rather than put up with the beam position it belongs to, because the order of
        // this method is the file format. A field inserted in the middle would be read out of a
        // state written before it existed as whatever byte happened to be at that offset;
        // appended, it is simply missing from an older file, and StateIO hands back what the
        // machine already had -- which for a state written before there was a PAL machine to write
        // one is right.
        masterClockRemainder = io.u8(masterClockRemainder);

        // Last for the same reason, and it is the whole of the sprite limit hack that travels:
        // whether anybody asked for it does not, any more than the layer switches do.
        extraSprites.serialize(io);

        // And appended after it for the same reason again. This is the whole of the overclock that
        // travels: how many repeats of this line have run, which is a beam position and belongs
        // with the rest of one. How many there are meant to be is the Hacks menu's, so a state
        // taken mid-repeat loads into a machine with the hack off and simply moves on at the next
        // line wrap.
        extraLine = io.u16(extraLine);

        // Appended for the same reason once more: where the address bus and the $2007 fetch it
        // owes have got to, which is state a state taken between the two dots of an access needs
        // if it is to give back the same picture. A file written before any of it existed loads
        // with the bus idle, which is what a machine that has just been handed one looks like.
        dataFetch = io.u8(dataFetch);
        dataFetchAddress = io.u16(dataFetchAddress);
        incrementOwed = io.bool(incrementOwed);
        busAddress = io.u16(busAddress);
        addressLatch = io.u8(addressLatch);
        busData = io.u8(busData);

        if (!io.saving()) {
            updateNMILine();
        }
    }

    /**
     * The finished picture, 256 by 240 pixels.
     * <p>
     * Each entry is {@code emphasis << 6 | palette entry}, 0 to 511 -- an index into somebody's
     * palette, not a colour. The chip has no palette table: it generates an NTSC signal from the
     * six bit index, and turning that into RGB means picking a measurement of what the signal
     * looked like on a television, which is a property of the television and so the front end's
     * business.
     * <p>
     * Handed out directly rather than copied: this is the hook a front end draws from, and it is
     * overwritten in place as the beam moves, so a caller that wants a stable frame has to take
     * its copy at the end of one.
     *
     * @return the framebuffer.
     */
    public int[] getFrameBuffer() {
        return frameBuffer;
    }

    /**
     * @return how many frames have been completed since power on.
     */
    public long getFrame() {
        return frame;
    }

    /**
     * Where {@link #getFrameBuffer()}'s frame sits in the three step cycle the colour subcarrier
     * drifts through, which is what a composite decoder needs and a palette does not.
     *
     * @return 0, 1 or 2.
     * @see com.github.dimiro1.mynes.video.NTSCFilter
     */
    public int getFramePhase() {
        return framePhase;
    }

    /**
     * @return the scanline the beam is on, 0 to 261.
     */
    public int getScanline() {
        return scanline;
    }

    /**
     * @return the dot the beam is on, 0 to 340.
     */
    public int getDot() {
        return dot;
    }

    /**
     * @return the current VRAM address, loopy's {@code v}.
     */
    public int getV() {
        return v;
    }

    /**
     * @return the staging VRAM address, loopy's {@code t}.
     */
    public int getT() {
        return t;
    }

    /**
     * @return the fine X scroll, loopy's {@code x}.
     */
    public int getFineX() {
        return fineX;
    }

    /**
     * @return the shared $2005/$2006 write latch, loopy's {@code w}.
     */
    public boolean isWriteLatchSet() {
        return writeLatch;
    }

    /**
     * Where the background is taking its tiles from, $0000 or $1000, out of $2000 bit 4.
     * <p>
     * Spelled as the address rather than as the bit because that is what anybody asking wants: a
     * viewer drawing a nametable has a tile number and needs somewhere to add it to. Three of these
     * rather than one {@code getCtrl}, for the same reason -- a caller that had to remember which
     * bit meant what would be a second place for the layout of that register to be written down.
     */
    public int getBackgroundPatternTable() {
        return (ctrl & CTRL_BACKGROUND_TABLE) != 0 ? 0x1000 : 0x0000;
    }

    /**
     * Where 8x8 sprites are taking their tiles from, $0000 or $1000, out of $2000 bit 3.
     * <p>
     * Meaningless while {@link #getSpriteHeight()} is 16: a tall sprite picks its table with the low
     * bit of the tile number instead, and ignores this entirely.
     */
    public int getSpritePatternTable() {
        return (ctrl & CTRL_SPRITE_TABLE) != 0 ? 0x1000 : 0x0000;
    }

    /**
     * @return how tall a sprite is this frame, 8 or 16.
     */
    public int getSpriteHeight() {
        return spriteHeight();
    }

    /**
     * Shows or hides the background layer in the picture. A debug switch for a front end; the
     * game cannot tell it has been thrown, because it changes nothing but the pixels drawn.
     */
    public void setBackgroundLayerVisible(final boolean visible) {
        backgroundLayerVisible = visible;
    }

    public boolean isBackgroundLayerVisible() {
        return backgroundLayerVisible;
    }

    /**
     * Shows or hides the sprite layer in the picture. Hiding it reveals the background pixels the
     * sprites were covering; sprite evaluation and the sprite 0 hit flag are untouched.
     */
    public void setSpriteLayerVisible(final boolean visible) {
        spriteLayerVisible = visible;
    }

    public boolean isSpriteLayerVisible() {
        return spriteLayerVisible;
    }

    /**
     * Draws the sprites the real chip would have dropped, so that a scanline holding more than
     * eight of them stops flickering. Off at power on, and the game cannot tell it has been thrown:
     * see {@link ExtraSprites}.
     */
    public void setUnlimitedSprites(final boolean unlimited) {
        extraSprites.enabled = unlimited;
    }

    public boolean isUnlimitedSprites() {
        return extraSprites.enabled;
    }

    /**
     * Gives the program extra idle scanlines a frame, so that a main loop which overruns its frame
     * stops dropping one. Off at power on, and unlike the sprite limit above the game <em>can</em>
     * tell: see {@link Overclock}.
     *
     * @param overclock how many lines, and which side of the NMI. Never null; {@link Overclock#NONE}
     *                  is how to say none.
     */
    public void setOverclock(final Overclock overclock) {
        this.overclock = Objects.requireNonNull(
                overclock, "there is no overclock at all; Overclock.NONE is how to say none");
    }

    public Overclock getOverclock() {
        return overclock;
    }

    /**
     * Whether the beam is on a line it is running again rather than one the console would have run.
     * <p>
     * {@link NES#tick()} asks once per CPU cycle, so that the APU can stand still through the extra
     * lines and keep making a hardware frame's worth of sound.
     */
    public boolean isOnExtraLine() {
        return extraLine != 0;
    }

    /**
     * Reads a palette RAM cell without side effects, for debug UIs. Mirroring is folded the same
     * way a real access folds it, so both a full $3F00 style address and a bare 0 to 31 index
     * name the same cells.
     *
     * @param address a palette address; only the low five bits matter.
     * @return the six bit palette entry.
     */
    public int peekPalette(final int address) {
        return readPalette(address);
    }

    /**
     * Reads a byte of OAM without side effects, for debug UIs.
     * <p>
     * @param address a byte of OAM, 0 to 255.
     * @return the byte at that address.
     * @see OAM#peek(int)
     */
    public int peekOAM(final int address) {
        return oam.peek(address);
    }

    /**
     * Reads a byte off the PPU bus without side effects, for debug UIs -- the pattern tables and
     * the nametables, which live outside the chip.
     * <p>
     * Palette RAM is not on that bus and so is not reachable here; {@link #peekPalette} is where
     * it lives.
     *
     * @param address any address; only the low fourteen lines exist.
     * @return the byte at that address.
     * @see VRAM#peek(int)
     */
    public int peekVRAM(final int address) {
        return vram.peek(address);
    }

    /**
     * Which byte of a sprite the evaluation is holding. Not which byte of OAM: a game that leaves
     * OAMADDR pointing into the middle of a sprite makes the two disagree, and every quirk this
     * state machine has is a consequence of that.
     */
    private enum EvaluationStep {
        Y_POSITION,
        TILE,
        ATTRIBUTES,
        X_POSITION,

        /**
         * The address has run off the end of OAM. Whatever is left of the scanline is spent
         * reading and discarding.
         */
        FINISHED,
    }
}
