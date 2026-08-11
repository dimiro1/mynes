package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;

import java.util.Arrays;

/**
 * PPU implements the 2C02 picture processing unit found in the NTSC NES.
 * <p>
 * The chip is a state machine clocked three times per CPU cycle, and almost everything it is
 * known for -- the VBlank flag races, the sprite overflow bug, the $2007 increment glitch -- is a
 * consequence of what it happens to be doing on a particular dot. So this is written as a dot
 * machine: {@link #tick()} does the work belonging to the current {@code (scanline, dot)} and
 * then moves on, and the register handlers below look at where the beam is rather than at a pile
 * of separately maintained flags.
 * <p>
 * A frame is 341 dots by 262 scanlines:
 * <ul>
 *   <li>0-239: visible, one pixel per dot for the first 256 dots</li>
 *   <li>240: post-render, the PPU idles</li>
 *   <li>241-260: vertical blank; the VBlank flag is set on dot 1 of line 241</li>
 *   <li>261: pre-render, which behaves like a visible line except that nothing is drawn and the
 *       status flags are cleared on dot 1</li>
 * </ul>
 * With rendering enabled, odd frames drop the last dot of the pre-render line, so a frame is
 * 89342 dots normally and 89341 then.
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
     * The first scanline that is not drawn.
     */
    private static final int POST_RENDER_LINE = 240;

    /**
     * The scanline VBlank starts on.
     */
    private static final int VBLANK_START_LINE = 241;

    /**
     * The last scanline of a frame, which prepares the shifters for line 0 rather than drawing.
     */
    private static final int PRE_RENDER_LINE = 261;

    /**
     * The dot of {@link #VBLANK_START_LINE} the VBlank flag is set on, and equally the dot of
     * {@link #PRE_RENDER_LINE} the status flags are cleared on.
     * <p>
     * Also the dot a $2002 read has to land on to suppress the flag entirely: see
     * {@link #preventVBlankFlag}.
     */
    private static final int STATUS_DOT = 1;

    /**
     * How many frames an open bus bit holds its charge for.
     * <p>
     * The real decay is around 600 milliseconds, which is about 36 frames, and it varies between
     * consoles and with temperature. This is the value {@code ppu_open_bus.nes} is happy with.
     */
    private static final int OPEN_BUS_DECAY_FRAMES = 36;

    /**
     * How many dots a write to $2001 takes to reach the rendering hardware.
     */
    private static final int MASK_WRITE_DELAY_DOTS = 2;

    /**
     * How many dots a second $2006 write takes to reach the VRAM address counter.
     */
    private static final int ADDRESS_WRITE_DELAY_DOTS = 2;

    /**
     * How many dots an eight byte row of OAM holds its charge for.
     * <p>
     * A vertical blank is 6820 dots and the charge lasts "at least as long as an NTSC vertical
     * blank interval, but not much longer than this", so this is a little over one of them --
     * about 1.7 milliseconds.
     */
    private static final int OAM_DECAY_DOTS = 9000;

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

    // ---------------------------------------------------------------- beam position

    private int scanline = 0;
    private int dot = 0;

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

    private int oamAddress;
    private final int[] oam = new int[256];

    /**
     * The dot each eight byte row of OAM was last refreshed on. Per row rather than per byte
     * because that is how the DRAM behind it is wired: touching any byte of a row refreshes all
     * eight of them.
     */
    private final long[] oamRefreshedOn = new long[32];

    /**
     * Palette RAM. Thirty two entries of six bits, inside the PPU rather than on its bus.
     */
    private final int[] palette = new int[32];

    // ---------------------------------------------------------------- background pipeline

    /**
     * The four bytes of the tile currently being fetched. They sit here until the eight dot fetch
     * is over and {@link #reloadShifters()} hands them to the shift registers.
     */
    private int nameTableLatch;
    private int attributeLatch;
    private int patternLowLatch;
    private int patternHighLatch;

    /**
     * The pattern shift registers, sixteen bits each: the tile on screen in the top half and the
     * one after it in the bottom half. A pixel is whichever bit fine X points at.
     */
    private int patternShiftLow;
    private int patternShiftHigh;

    /**
     * The attribute shift registers, alongside the pattern ones and holding the palette number
     * for the same pixels.
     */
    private int attributeShiftLow;
    private int attributeShiftHigh;

    // ---------------------------------------------------------------- sprite pipeline

    /**
     * The eight sprites the PPU has picked out for the next scanline, four bytes each. Filled with
     * $FF at the start of every visible line and then written by the evaluation state machine.
     */
    private final int[] secondaryOAM = new int[32];

    /**
     * Which sprite of the sixty four in OAM the evaluation is looking at, and which of its four
     * bytes. The pair is a single counter on real hardware, and the fact that the overflow scan
     * increments them independently is the whole of the sprite overflow bug.
     */
    private int evaluationSprite;
    private int evaluationByte;

    /**
     * Where in secondary OAM the next byte goes.
     */
    private int evaluationSlot;

    /**
     * The byte read on the odd dot, acted on at the even one.
     */
    private int evaluationLatch;

    private EvaluationStep evaluationStep = EvaluationStep.FIND_SPRITE;

    /**
     * How many of the three bytes that follow an in-range sprite found by the overflow scan are
     * still to be read.
     */
    private int overflowReadsLeft;

    /**
     * The sprite the evaluation started from, which is whatever OAMADDR pointed at when the
     * scanline reached dot 65. Sprite 0 hit is really "the first sprite examined", not "sprite
     * number zero", and the two only differ when a game leaves OAMADDR somewhere else.
     */
    private int firstSpriteExamined;

    private int spritesFound;

    /**
     * Whether the sprite that landed in the first secondary OAM slot was the first one examined,
     * and so the one that can set the sprite 0 hit flag. One flag for the line being evaluated
     * and one for the line being drawn.
     */
    private boolean spriteZeroOnNextLine;
    private boolean spriteZeroOnThisLine;

    /**
     * The sprite output units, loaded during dots 257-320 and drained across the next scanline.
     */
    private int spriteCount;
    private final int[] spriteX = new int[8];
    private final int[] spriteAttributes = new int[8];
    private final int[] spritePatternLow = new int[8];
    private final int[] spritePatternHigh = new int[8];

    // ---------------------------------------------------------------- open bus

    /**
     * The PPU's own open bus, a row of eight tiny capacitors on the data pins. Reading a
     * write-only register, or a bit the PPU does not drive, comes back as whatever is still
     * charged here.
     */
    private int openBus;

    /**
     * The frame each open bus bit was last refreshed on. Per bit rather than per byte because
     * different reads refresh different bits.
     */
    private final long[] openBusRefreshedOn = new long[8];

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
        this.bus = bus;
        this.mapper = mapper;
        this.vram = new VRAM(mapper);

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

        // The VBlank flag is untouched, but the NMI enable bit has just gone, so the line has to
        // be settled again.
        updateNMILine();
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

        clock++;

        if (warmingUp && scanline == PRE_RENDER_LINE) {
            // The internal reset signal is released when the beam first reaches the pre-render
            // line, which from power on is 89001 dots, or 29667 CPU cycles -- the wiki's "around
            // 29658", said in the units this class thinks in.
            warmingUp = false;
        }

        if (maskDelay > 0 && --maskDelay == 0) {
            mask = pendingMask;
        }

        if (addressDelay > 0 && --addressDelay == 0) {
            // Ahead of the fetch pipeline rather than behind it, so that the dot the load lands
            // on already reads from the new address -- and so that a coarse X increment that fell
            // into the gap is overwritten, which is what the hardware does.
            v = t;

            // The counter drives the address bus, so loading it puts the address out there with
            // no access going with it. That is how a game with the picture switched off clocks an
            // MMC3's scanline counter, and it happens here rather than at the write because until
            // this dot the bus is still holding the old address.
            mapper.ppuAddress(v & 0x3FFF);
        }

        if (isRenderingLine()) {
            if (scanline == PRE_RENDER_LINE && dot == STATUS_DOT) {
                endVBlank();
            }

            if (isRenderingEnabled()) {
                backgroundTick();
                spriteTick();
            }

            // The picture comes out whether or not anything is being rendered: with rendering off
            // the screen is a flat sheet of the backdrop colour rather than nothing at all.
            if (scanline < POST_RENDER_LINE && dot >= 1 && dot <= SCREEN_WIDTH) {
                renderPixel();
            }
        } else if (scanline == VBLANK_START_LINE && dot == STATUS_DOT) {
            startVBlank();
        }

        advance();
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
     */
    private void advance() {
        if (scanline == PRE_RENDER_LINE && dot == LAST_DOT - 1 && oddFrame && isRenderingEnabled()) {
            startFrame();
            return;
        }

        dot++;

        if (dot > LAST_DOT) {
            dot = 0;
            scanline++;

            if (scanline > PRE_RENDER_LINE) {
                startFrame();
            }
        }
    }

    private void startFrame() {
        scanline = 0;
        dot = 0;
        frame++;
        oddFrame = !oddFrame;
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
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_rendering#Cycles_1-256">NESdev: PPU rendering</a>
     */
    private void backgroundTick() {
        var fetching = (dot >= 1 && dot <= 256) || (dot >= 321 && dot <= 336);

        if ((dot >= 2 && dot <= 257) || (dot >= 322 && dot <= 337)) {
            shiftBackground();
        }

        // Dots 9, 17, ... 257, then 329 and 337. The reload at dots 1 and 321 that this also
        // catches is a repeat of the one at 337, so it changes nothing.
        if ((fetching || dot == 257 || dot == 337) && (dot & 7) == 1) {
            reloadShifters();
        }

        if (fetching) {
            switch (dot & 7) {
                case 1 -> nameTableLatch = vram.read(0x2000 | (v & 0x0FFF));
                case 3 -> attributeLatch = fetchAttribute();
                case 5 -> patternLowLatch = vram.read(patternAddress());
                case 7 -> patternHighLatch = vram.read(patternAddress() + 8);
                case 0 -> incrementCoarseX();
                default -> { /* the odd dots drive the address, the even ones take the byte */ }
            }
        }

        if (dot == 256) {
            incrementY();
        } else if (dot == 257) {
            copyHorizontalPosition();
        } else if (scanline == PRE_RENDER_LINE && dot >= 280 && dot <= 304) {
            copyVerticalPosition();
        } else if (dot == 337 || dot == 339) {
            // Two more nametable reads that nothing uses. They exist because the fetch machinery
            // has nothing else to do, and mappers that watch the address bus can see them.
            vram.read(0x2000 | (v & 0x0FFF));
        }
    }

    /**
     * Reads the attribute byte covering the tile being fetched and picks out its two bits.
     * <p>
     * One attribute byte covers a four tile by four tile block and packs four two bit palette
     * numbers, one per two by two quadrant. Bit 1 of coarse X picks the left or right half and
     * bit 1 of coarse Y the top or bottom, so the pair wanted is at bit
     * {@code (coarseY & 2) << 1 | (coarseX & 2)}.
     */
    private int fetchAttribute() {
        var address = 0x23C0 | (v & 0x0C00) | ((v >> 4) & 0x38) | ((v >> 2) & 0x07);
        var shift = ((v >> 4) & 0x04) | (v & 0x02);

        return (vram.read(address) >> shift) & 0x03;
    }

    /**
     * @return the address of the low pattern byte for the tile just named, at the row fine Y
     * points at. The high byte is eight further on.
     */
    private int patternAddress() {
        return ((ctrl & CTRL_BACKGROUND_TABLE) != 0 ? 0x1000 : 0x0000)
                + nameTableLatch * 16
                + ((v >> 12) & 0x07);
    }

    private void shiftBackground() {
        patternShiftLow = (patternShiftLow << 1) & 0xFFFF;
        patternShiftHigh = (patternShiftHigh << 1) & 0xFFFF;
        attributeShiftLow = (attributeShiftLow << 1) & 0xFFFF;
        attributeShiftHigh = (attributeShiftHigh << 1) & 0xFFFF;
    }

    /**
     * Drops the tile that has just been fetched into the bottom half of the shift registers,
     * eight dots before the beam needs it.
     * <p>
     * The attribute is two bits for the whole tile rather than one per pixel, so its shift
     * registers are filled with eight copies of each bit. Real hardware keeps a one bit latch and
     * a narrower shifter instead; the picture is the same.
     */
    private void reloadShifters() {
        patternShiftLow = (patternShiftLow & 0xFF00) | patternLowLatch;
        patternShiftHigh = (patternShiftHigh & 0xFF00) | patternHighLatch;
        attributeShiftLow = (attributeShiftLow & 0xFF00) | ((attributeLatch & 1) != 0 ? 0xFF : 0x00);
        attributeShiftHigh = (attributeShiftHigh & 0xFF00) | ((attributeLatch & 2) != 0 ? 0xFF : 0x00);
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
                evaluateSprites();
            }
        }

        if (dot < 257 || dot > 320) {
            return;
        }

        // OAMADDR is held at zero for the whole fetch phase. A game that writes it during
        // rendering and expects to find it again afterwards will not.
        oamAddress = 0;

        if (dot == 257) {
            // Nothing was evaluated for the line after the pre-render one, so nothing is drawn
            // on it.
            spriteCount = scanline == PRE_RENDER_LINE ? 0 : spritesFound;
            spriteZeroOnThisLine = scanline != PRE_RENDER_LINE && spriteZeroOnNextLine;
        }

        var offset = (dot - 257) & 7;

        if (offset == 0 || offset == 2) {
            // Two reads of the nametable address the background fetch left behind. Nothing uses
            // the bytes -- the sprite's tile number came out of secondary OAM, not out of a
            // nametable -- but a mapper watching the address bus can see them.
            vram.read(0x2000 | (v & 0x0FFF));
        } else if (offset == 4) {
            loadSpriteUnit((dot - 257) >> 3);
        }
    }

    private void clearSecondaryOAM() {
        // One byte every two dots: the odd dot reads (and always reads $FF), the even one writes.
        if ((dot & 1) == 0) {
            secondaryOAM[(dot >> 1) - 1] = 0xFF;
        }
    }

    /**
     * The sprite evaluation state machine, one step per dot.
     * <p>
     * Odd dots read a byte of OAM, even dots decide what to do with it. Written out literally
     * rather than as a loop over sixty four sprites, because the interesting behaviour is all in
     * what happens when it runs out of time or out of slots -- and in the overflow scan, which is
     * documented hardware and is documented as being wrong.
     */
    private void evaluateSprites() {
        if (dot == 65) {
            beginEvaluation();
        }

        if ((dot & 1) == 1) {
            evaluationLatch = readOAM(((evaluationSprite << 2) | evaluationByte) & 0xFF);
            return;
        }

        switch (evaluationStep) {
            case FIND_SPRITE -> findSprite();
            case COPY_SPRITE -> copySprite();
            case OVERFLOW_SCAN -> scanForOverflow();
            case FINISHED -> nextSprite();
        }
    }

    private void beginEvaluation() {
        // "The OAM memory is refreshed once per scanline while rendering is enabled" -- and this
        // runs at dot 65 of a visible line with rendering on, which is exactly that condition. So
        // OAM only ever decays for a game that leaves rendering off for more than a millisecond.
        for (var row = 0; row < oamRefreshedOn.length; row++) {
            refreshOAMRow(row);
        }

        firstSpriteExamined = oamAddress >> 2;
        evaluationSprite = firstSpriteExamined;
        evaluationByte = oamAddress & 3;
        evaluationSlot = 0;
        evaluationStep = EvaluationStep.FIND_SPRITE;
        overflowReadsLeft = 0;
        spritesFound = 0;
        spriteZeroOnNextLine = false;
    }

    /**
     * Looks at a sprite's Y coordinate. It is copied into the next free slot either way -- the
     * hardware writes first and only keeps the slot if the sprite turned out to be wanted.
     */
    private void findSprite() {
        if (spritesFound < 8) {
            secondaryOAM[evaluationSlot] = evaluationLatch;
        }

        if (!isInRange(evaluationLatch)) {
            nextSprite();
            return;
        }

        if (evaluationSlot == 0) {
            spriteZeroOnNextLine = evaluationSprite == firstSpriteExamined;
        }

        evaluationSlot++;
        evaluationByte = 1;
        evaluationStep = EvaluationStep.COPY_SPRITE;
    }

    /**
     * Copies the three bytes after the Y coordinate: tile, attributes and X.
     */
    private void copySprite() {
        secondaryOAM[evaluationSlot++] = evaluationLatch;
        evaluationByte++;

        if (evaluationByte < 4) {
            return;
        }

        evaluationByte = 0;
        spritesFound++;
        evaluationStep = spritesFound == 8 ? EvaluationStep.OVERFLOW_SCAN : EvaluationStep.FIND_SPRITE;

        nextSprite();
    }

    /**
     * The overflow scan, which is where the sprite overflow flag gets its reputation.
     * <p>
     * Once eight sprites have been found the hardware keeps looking, but the counter it uses to
     * step through OAM is wrong: on a miss it increments the byte index as well as the sprite
     * index, and the byte index does not carry. So after the first miss it is comparing a
     * sprite's tile number against the scanline, then an attribute byte, then an X coordinate,
     * and the flag ends up set or clear more or less at random. Games rely on the exact pattern,
     * so the bug is reproduced rather than corrected.
     */
    private void scanForOverflow() {
        if (overflowReadsLeft > 0) {
            // The three bytes after an in-range hit, read with a properly carrying counter.
            overflowReadsLeft--;
            evaluationByte++;

            if (evaluationByte == 4) {
                evaluationByte = 0;
                nextSprite();
            }

            return;
        }

        if (isInRange(evaluationLatch)) {
            spriteOverflow = true;
            overflowReadsLeft = 3;
            return;
        }

        // The bug: both counters move, and the byte index wraps on its own.
        evaluationByte = (evaluationByte + 1) & 3;
        nextSprite();
    }

    /**
     * Moves to the next sprite, finishing the evaluation once the counter has been all the way
     * round. Whatever is left of the scanline after that is spent reading the same byte over and
     * over and throwing it away.
     */
    private void nextSprite() {
        evaluationSprite = (evaluationSprite + 1) & 0x3F;

        if (evaluationSprite == 0) {
            evaluationStep = EvaluationStep.FINISHED;
            evaluationByte = 0;
        }
    }

    /**
     * @return whether a sprite with this Y coordinate covers the scanline being evaluated.
     */
    private boolean isInRange(final int y) {
        var row = scanline - y;
        return row >= 0 && row < spriteHeight();
    }

    private int spriteHeight() {
        return (ctrl & CTRL_TALL_SPRITES) != 0 ? 16 : 8;
    }

    /**
     * Loads one of the eight sprite output units from the slot of secondary OAM that feeds it.
     * <p>
     * Unused slots still go through the motions -- secondary OAM was wiped to $FF, so they fetch
     * row 0 of tile $FF -- but {@link #spriteCount} keeps them off the screen.
     */
    private void loadSpriteUnit(final int unit) {
        var base = unit * 4;
        var y = secondaryOAM[base];
        var tile = secondaryOAM[base + 1];
        var attributes = secondaryOAM[base + 2];

        spriteAttributes[unit] = attributes;
        spriteX[unit] = secondaryOAM[base + 3];

        var height = spriteHeight();
        var row = scanline - y;

        if (row < 0 || row >= height) {
            row = 0;
        }

        if ((attributes & 0x80) != 0) {
            row = height - 1 - row;
        }

        int address;

        if (height == 16) {
            // A tall sprite ignores $2000's table bit: the tile number's low bit picks the table
            // and the rest of it picks a pair of tiles, the second being the bottom half.
            address = ((tile & 1) << 12) | ((tile & 0xFE) << 4);
            address += row >= 8 ? 16 + (row & 7) : row;
        } else {
            address = ((ctrl & CTRL_SPRITE_TABLE) != 0 ? 0x1000 : 0x0000) | (tile << 4) | row;
        }

        var low = vram.read(address);
        var high = vram.read(address + 8);

        if ((attributes & 0x40) != 0) {
            low = reverseBits(low);
            high = reverseBits(high);
        }

        spritePatternLow[unit] = low;
        spritePatternHigh[unit] = high;
    }

    /**
     * Turns a pattern byte back to front, which is how a horizontally flipped sprite is drawn:
     * the hardware loads the shift register the other way round rather than shifting the other
     * way.
     */
    private static int reverseBits(final int value) {
        return Integer.reverse(value) >>> 24;
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
        var unit = -1;
        var colour = 0;

        if ((mask & MASK_SHOW_SPRITES) != 0 && (x >= 8 || (mask & MASK_SHOW_SPRITES_LEFT) != 0)) {
            for (var i = 0; i < spriteCount; i++) {
                var offset = x - spriteX[i];

                if (offset < 0 || offset > 7) {
                    continue;
                }

                var bit = 0x80 >> offset;
                colour = ((spritePatternHigh[i] & bit) != 0 ? 2 : 0)
                        | ((spritePatternLow[i] & bit) != 0 ? 1 : 0);

                if (colour != 0) {
                    unit = i;
                    break;
                }
            }
        }

        // From here down the debug layer switches take part, but only in what is returned: the
        // sprite search above has already run, and the hit flag below still uses the real
        // background pixel, so a hidden layer stays invisible to the game itself.
        var drawnBackground = backgroundLayerVisible ? background : 0;

        if (unit < 0) {
            return drawnBackground;
        }

        // The hit is about two opaque pixels meeting, not about which of them is drawn, so a
        // sprite hidden behind the background still sets it. The last pixel of the line never
        // does, for reasons lost with the hardware.
        if (unit == 0 && spriteZeroOnThisLine && background != 0 && x != SCREEN_WIDTH - 1) {
            spriteZeroHit = true;
        }

        var attributes = spriteAttributes[unit];

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

        // Fine X chooses which of the sixteen bits in flight is the one on screen now. It is the
        // only part of the scroll position that is applied here rather than by the fetch.
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
     * Turns a palette entry into the value the framebuffer carries, applying the two things $2001
     * can do to a colour on its way out of the chip: drop the hue, and set the emphasis bits.
     * <p>
     * Both belong here rather than in the front end, because the hardware really does force the
     * index down and really does put those three bits on the wire. What the resulting signal looks
     * like on a television is somebody else's problem -- see
     * {@code com.github.dimiro1.mynes.ui.palette.NESPalette}.
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
            default -> openBus();
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
        refreshOpenBus(value, 0xFF);

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
        return switch (register & 7) {
            case 2 -> status() | (openBus & 0x1F);
            case 4 -> oam[oamAddress];
            case 7 -> (v & 0x3FFF) >= 0x3F00
                    ? (openBus & 0xC0) | readPalette(v)
                    : readBuffer;
            default -> openBus;
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
        var value = status() | (openBus() & 0x1F);

        if (scanline == VBLANK_START_LINE && dot == STATUS_DOT) {
            preventVBlankFlag = true;
        }

        vblankFlag = false;
        writeLatch = false;
        updateNMILine();

        refreshOpenBus(value, 0xE0);
        return value;
    }

    private int status() {
        return (vblankFlag ? STATUS_VBLANK : 0)
                | (spriteZeroHit ? STATUS_SPRITE_ZERO_HIT : 0)
                | (spriteOverflow ? STATUS_SPRITE_OVERFLOW : 0);
    }

    /**
     * Reads $2004, which is a plain window onto OAM at the current address and does not move it.
     * <p>
     * The one exception is the first 64 dots of a visible scanline with rendering on: the sprite
     * evaluation hardware is busy filling secondary OAM with $FF, and that is what a read sees.
     */
    private int readOAMData() {
        var value = isClearingSecondaryOAM() ? 0xFF : readOAM(oamAddress);

        refreshOpenBus(value, 0xFF);
        return value;
    }

    /**
     * Writes $2004.
     * <p>
     * During rendering the sprite evaluation hardware owns OAM, so the byte is dropped -- but the
     * address still moves, and by four rather than one, because what the write actually clocks is
     * the sprite counter rather than the byte counter.
     */
    private void writeOAMData(final int value) {
        if (isRenderingEnabled() && isRenderingLine()) {
            oamAddress = (oamAddress + 4) & 0xFF;
            return;
        }

        // Bits 2 to 4 of a sprite's attribute byte do not exist: there are no RAM cells behind
        // them, so they read back as zero no matter what was written. Masking here rather than on
        // the read path means OAM DMA, which funnels through this same method, is covered too.
        writeOAM(oamAddress, (oamAddress & 3) == 2 ? value & 0xE3 : value);
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
     * in the address space, so the buffer is left holding that instead.
     */
    private int readData() {
        var address = v & 0x3FFF;
        int value;

        if (address >= 0x3F00) {
            value = (openBus() & 0xC0) | (readPalette(address) & greyscaleMask());
            // The palette address itself goes on the bus -- the nametable below it is what
            // answers, because only thirteen of the fourteen lines reach the nametable RAM, but
            // A12 is high and a mapper watching the bus sees that. Reading $3F05 and reading
            // $2F05 fetch the same byte and differ only in what the cartridge saw go past.
            readBuffer = vram.read(address);
            refreshOpenBus(value, 0x3F);
        } else {
            value = readBuffer;
            readBuffer = vram.read(address);
            refreshOpenBus(value, 0xFF);
        }

        incrementAddress();
        return value;
    }

    private void writeData(final int value) {
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
     * Reads a byte of OAM, refreshing the row it lives in.
     */
    private int readOAM(final int address) {
        refreshOAMRow(address >> 3);
        return oam[address];
    }

    /**
     * Writes a byte of OAM, refreshing the row it lives in.
     */
    private void writeOAM(final int address, final int value) {
        refreshOAMRow(address >> 3);
        oam[address] = value;
    }

    /**
     * Lets an eight byte row of OAM decay if it has gone too long untouched, and then starts its
     * clock again.
     * <p>
     * OAM is DRAM with no refresh circuit of its own, so the only thing that keeps it alive is
     * being read or written. The sprite evaluation does that once per scanline, but only while
     * rendering is enabled; a row left alone for longer than {@link #OAM_DECAY_DOTS} loses its
     * charge and reads back as zero. Zeroing the array here rather than masking on the way out is
     * what keeps sprite evaluation, $2004 and OAM DMA all seeing the same OAM.
     *
     * @see <a href="https://www.nesdev.org/wiki/PPU_OAM">NESdev: PPU OAM</a>
     */
    private void refreshOAMRow(final int row) {
        if (clock - oamRefreshedOn[row] >= OAM_DECAY_DOTS) {
            Arrays.fill(oam, row * 8, row * 8 + 8, 0);
        }

        oamRefreshedOn[row] = clock;
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
     * Reads the open bus latch, letting any bit that has gone too long without a refresh decay
     * to zero first.
     */
    private int openBus() {
        for (var bit = 0; bit < 8; bit++) {
            if (frame - openBusRefreshedOn[bit] >= OPEN_BUS_DECAY_FRAMES) {
                openBus &= ~(1 << bit);
            }
        }

        return openBus;
    }

    /**
     * Drives some of the data pins, which both sets those bits and starts their decay over.
     *
     * @param value the byte being driven.
     * @param mask  which bits of it the PPU actually drives; the rest keep their old charge.
     */
    private void refreshOpenBus(final int value, final int mask) {
        openBus = (openBus & ~mask) | (value & mask);

        for (var bit = 0; bit < 8; bit++) {
            if ((mask & (1 << bit)) != 0) {
                openBusRefreshedOn[bit] = frame;
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
        return scanline < POST_RENDER_LINE || scanline == PRE_RENDER_LINE;
    }

    /**
     * @return true while the sprite evaluation hardware is filling secondary OAM with $FF, which
     * is the one window where $2004 does not read OAM.
     */
    private boolean isClearingSecondaryOAM() {
        return isRenderingEnabled()
                && scanline < POST_RENDER_LINE
                && dot >= 1 && dot <= 64;
    }

    private void updateNMILine() {
        bus.setNMILine(vblankFlag && (ctrl & CTRL_NMI_ENABLE) != 0);
    }

    // ================================================================ inspection

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
     * Unlike {@link #readOAM} this does not refresh the row, which is the whole point: a debugger
     * that kept OAM alive by looking at it would hide the decay it was there to watch.
     *
     * @param address a byte of OAM, 0 to 255.
     * @return the byte at that address.
     */
    public int peekOAM(final int address) {
        return oam[address & 0xFF];
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
     * What the sprite evaluation state machine is in the middle of doing.
     */
    private enum EvaluationStep {
        /**
         * Reading a sprite's Y coordinate to see whether it belongs on the next scanline.
         */
        FIND_SPRITE,

        /**
         * Copying the three bytes after the Y coordinate of a sprite that does.
         */
        COPY_SPRITE,

        /**
         * Eight sprites have been found, so nothing more can be copied, but the hardware keeps
         * reading OAM to decide whether to set the overflow flag -- with the counter bug that
         * makes the answer famously unreliable.
         */
        OVERFLOW_SCAN,

        /**
         * All sixty four sprites have been looked at. Whatever is left of the scanline is spent
         * reading OAM and discarding it.
         */
        FINISHED,
    }
}
