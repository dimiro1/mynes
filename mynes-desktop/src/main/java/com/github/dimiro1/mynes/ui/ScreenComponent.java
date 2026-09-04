package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.CRTScreen;
import com.github.dimiro1.mynes.video.Crop;
import com.github.dimiro1.mynes.video.FrameRenderer;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.NTSCFilter;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;

/**
 * The television. Draws the PPU's framebuffer, scaled and letterboxed to fill the window, in
 * whichever palette the user has picked.
 * <p>
 * The chip emits colour indices rather than colours -- see {@link PPU#getFrameBuffer()} -- so the
 * mapping to RGB happens here, which is what makes the palette a property of this end of the wire:
 * changing it is one field on the event dispatch thread, and it holds across a power cycle without
 * anything having to remember to re-apply it.
 * <p>
 * Two threads meet here. The emulation thread hands over a finished frame through
 * {@link #present(int[], int)}, the event dispatch thread paints it, and a lock covers both
 * buffers so that a paint never catches a half copied picture. The lock is held for an arraycopy
 * of 240KB and 61440 array lookups, sixty times a second -- tens of microseconds, so neither side
 * waits for long.
 * <p>
 * The picture is cropped, by {@link FrameRenderer#OVERSCAN_TOP} and its neighbours, which is also
 * where the reason for it is written down -- unless {@link #setOverscan} has been told to show
 * those lines, which is the headless mode's {@code --full-frame} asked of the window.
 * {@link #setLeftEdge} takes eight columns off the other axis, and
 * {@link com.github.dimiro1.mynes.video.Crop} is where the two meet.
 */
public class ScreenComponent extends JComponent {

    /**
     * How tall the rewind marker is as a fraction of the picture, and how small it is allowed to get
     * before the fraction stops applying -- at 1x an eighteenth of the picture is twelve pixels, and
     * anything under about six stops reading as a triangle at all.
     */
    private static final int MARKER_HEIGHT_DIVISOR = 18;
    private static final int MARKER_MINIMUM = 6;

    /**
     * Translucent, because it sits over a game somebody is trying to see. White reads against nearly
     * every NES palette entry; the shadow is what carries it over the few it does not.
     */
    private static final Color MARKER_FILL = new Color(255, 255, 255, 210);
    private static final Color MARKER_SHADOW = new Color(0, 0, 0, 140);

    private final Object frameLock = new Object();
    private final BufferedImage image = new BufferedImage(
            PPU.SCREEN_WIDTH, PPU.SCREEN_HEIGHT, BufferedImage.TYPE_INT_RGB);

    /**
     * The image's own storage, written into directly. Reaching for the backing array like this
     * costs the image its hardware acceleration, which is the trade: one pass per frame beats
     * 61440 calls to {@link BufferedImage#setRGB}.
     */
    private final int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

    /**
     * The last frame as the PPU handed it over, in colour indices. Kept so that changing the
     * palette can recolour the picture already on screen instead of waiting for another frame --
     * which, with the emulator paused, is not coming.
     */
    private final int[] frame = new int[PPU.SCREEN_WIDTH * PPU.SCREEN_HEIGHT];

    /**
     * Whether {@link #frame} holds a picture yet. Without this the window before any ROM is loaded
     * would colourise an all-zero frame to entry 0, which is dark grey rather than the black a
     * television off shows.
     */
    private boolean hasFrame;

    private int[] palette = Palettes.defaultPalette().colours();

    /**
     * The magnification the component asks the window for. Kept because it is half of the answer:
     * how tall it asks to be is that number of screen pixels per line times however many lines
     * {@link #overscan} says there are, so either setting moving has to work the sum again.
     */
    private ScreenScale scale = ScreenScale.defaultScale();

    /**
     * Whether the scanlines a television hid are drawn. Written and read on the event dispatch
     * thread and nowhere else -- unlike the palette and the filter, which the emulation thread
     * reads inside {@link #colourise}. This one reaches no further than where the picture is put:
     * {@link #image} is all 240 lines whatever this says, and it is the painting and the snapshot
     * that decide how much of it to take.
     */
    private boolean overscan;

    /**
     * Whether the eight columns the chip clips down the left edge are drawn. True, because they
     * are the picture on every game that does not clip them, and the games that do are the only
     * ones with anything to hide -- {@link com.github.dimiro1.mynes.video.Crop} is where what they
     * are hiding is written down. Kept on the same thread and reaching the same places
     * {@link #overscan} does, and for the same reasons.
     */
    private boolean leftEdge = true;

    /**
     * Which rectangle of {@link #image} the two settings above add up to, worked out when either
     * of them moves rather than on every call. A paint asks several times over and the answer
     * cannot change between them, since all of this is the event dispatch thread's.
     */
    private Crop crop = Crop.TELEVISION;

    /**
     * How the frame is drawn: through {@link #palette}, by decoding the signal, or through the
     * palette and onto a tube.
     */
    private VideoFilter videoFilter = VideoFilter.NONE;

    /**
     * How hard whichever of the two filters is on is applied. Kept beside the filter rather than
     * only on the decoder, so that a window that has never switched to either still remembers what
     * it was told.
     */
    private FilterStrength filterStrength = FilterStrength.defaultStrength();

    /**
     * Whether the tube's glass is curved. Beside the strength and for the same reason: a window
     * that is not showing a tube still remembers what it was told about one.
     */
    private boolean warp;

    /**
     * The composite decoder, built the first time somebody asks for it. A window that is never
     * switched to it should not carry its tables.
     */
    private @Nullable NTSCFilter ntsc;

    /**
     * The picture as a tube would have shown it, at the size the window is currently drawing, or
     * null while nothing is asking for one.
     * <p>
     * A buffer of its own because this is the one filter whose answer depends on how big the
     * picture is: {@link #image} is one pixel per pixel the chip drew, and a scanline lives between
     * two rows of the picture <em>on screen</em>. So {@link CRTScreen} magnifies, masks and bends in
     * one pass into here, and {@link Graphics2D} is handed the result rather than the frame.
     * <p>
     * Kept across frames because it is reallocated only when the window is resized. Touched only
     * while painting, which is the event dispatch thread, so it is outside {@link #frameLock}.
     */
    private @Nullable BufferedImage tube;

    private int @Nullable [] tubePixels;

    /**
     * The frame as {@link #colourise} left it, copied out from under {@link #frameLock} so that the
     * tube pass -- which is a couple of milliseconds where the rest of a paint is microseconds --
     * does not hold the emulation thread off while it runs.
     */
    private int @Nullable [] tubeSource;

    /**
     * Which of the three subcarrier alignments {@link #frame} was drawn at. Kept beside the frame
     * for the same reason the frame is kept: switching the filter has to be able to redraw the
     * picture that is already on screen, and with the emulator paused there is no other one coming.
     */
    private int framePhase;

    /**
     * Whether to draw the rewind marker over the picture. Written by the emulation thread and read
     * by the event dispatch thread when it paints, which is what {@code volatile} is here for; it is
     * outside {@link #frameLock} on purpose, since a marker that appeared a frame late would be
     * nobody's problem and holding the lock for it would be.
     */
    private volatile boolean rewinding;

    public ScreenComponent() {
        setScale(ScreenScale.defaultScale());
        setOpaque(true);
    }

    /**
     * Asks to be drawn at {@code scale} times the size of the NES's visible picture.
     * <p>
     * A request rather than a size: this is the preferred size, and it is the window packing itself
     * around the component that makes it the real one. Nothing about the drawing changes, which is
     * the point -- {@link #paintComponent} works from whatever size the component ends up at, so a
     * window dragged to some size in between keeps working exactly as it did.
     */
    public void setScale(final ScreenScale scale) {
        this.scale = scale;

        askForRoom();
    }

    /**
     * Draws the scanlines a television hid behind its bezel, or stops.
     * <p>
     * The same question the headless mode's {@code --full-frame} answers, and answered the same
     * way: nothing about the machine or the framebuffer changes, only how much of the frame is
     * looked at. So it reaches the picture on screen, the screenshots and the clipboard alike,
     * and it works with the emulator paused -- the frame is already here and nothing has to be
     * colourised again for it, since {@link #image} was never the cropped one.
     * <p>
     * The component asks for sixteen more rows, or sixteen fewer, because the alternative is
     * fitting a taller picture into the height the window already had: the lines somebody just
     * asked to see would arrive by shrinking everything else, which is not what was asked.
     */
    public void setOverscan(final boolean overscan) {
        if (this.overscan == overscan) {
            return;
        }

        this.overscan = overscan;

        recrop();
    }

    /**
     * Draws the eight columns the chip clips down the left edge, or stops.
     * <p>
     * The other half of the same question {@link #setOverscan} asks, kept apart from it because it
     * has the opposite answer by default and a different reason for it: what a television hid at
     * the top and the bottom is a mess the game did not mean to draw, and what is down the left
     * edge is the backdrop colour the chip was <em>told</em> to put there. So the lines go unless
     * somebody asks for them and the columns stay unless somebody asks to be rid of them.
     * <p>
     * The window is packed around the eight columns the way it is packed around the sixteen rows,
     * and for the reason written above them.
     */
    public void setLeftEdge(final boolean leftEdge) {
        if (this.leftEdge == leftEdge) {
            return;
        }

        this.leftEdge = leftEdge;

        recrop();
    }

    private void askForRoom() {
        setPreferredSize(new Dimension(
                columns() * scale.factor(), lines() * scale.factor()));

        revalidate();
    }

    /**
     * Works out which rectangle of the frame is the picture, and asks for the room to draw it in.
     */
    private void recrop() {
        var whole = overscan ? Crop.FULL_FRAME : Crop.TELEVISION;

        crop = leftEdge ? whole : whole.withoutLeftEdge();

        askForRoom();
        repaint();
    }

    private int top() {
        return crop.top();
    }

    private int lines() {
        return crop.height();
    }

    private int left() {
        return crop.left();
    }

    private int columns() {
        return crop.width();
    }

    /**
     * Takes a copy of a completed frame and asks for a repaint.
     * <p>
     * Called from the emulation thread. {@link #repaint()} is one of the few Swing methods that is
     * safe to call from anywhere: it only posts a request to the event queue.
     *
     * @param frameBuffer the PPU's live framebuffer, {@link PPU#getFrameBuffer()}.
     * @param framePhase  where that frame sits in the subcarrier's cycle,
     *                    {@link PPU#getFramePhase()}. Ignored unless the NTSC filter is on, and
     *                    taken here rather than read back later because it belongs to this frame
     *                    and the beam has already moved on.
     */
    public void present(final int[] frameBuffer, final int framePhase) {
        synchronized (frameLock) {
            System.arraycopy(frameBuffer, 0, frame, 0, frame.length);
            this.framePhase = framePhase;
            hasFrame = true;
            colourise();
        }

        repaint();
    }

    /**
     * Draws the rewind marker over the picture, or stops.
     * <p>
     * Called from the emulation thread, like {@link #present(int[], int)}, and by the same rule:
     * that is the thread that knows whether the machine is actually going backwards, which is not
     * the same question as whether the key is down -- a paused machine, or a history that has run
     * out, is a key held with nothing happening.
     * <p>
     * Over the picture rather than in it. What the PPU drew is what the PPU drew, and a marker
     * painted into the framebuffer would end up in screenshots and in the frame hashes, where it
     * would be a lie about the machine.
     */
    public void setRewinding(final boolean rewinding) {
        if (this.rewinding == rewinding) {
            return;
        }

        this.rewinding = rewinding;

        repaint();
    }

    /**
     * The picture as it stands, magnified {@code scale} times, or null before the first frame.
     * <p>
     * Drawn again from the colour indices rather than read back off the screen, which is what makes
     * it a picture of the machine rather than one of the window. The component fits its picture to
     * whatever size the window has been dragged to, so what is on screen is very likely a fractional
     * magnification with black down either side of it; this is the 256x224 the PPU drew, cropped the
     * way the window crops it and magnified by a whole number.
     * <p>
     * Called on the event dispatch thread, and it asks the emulation thread for nothing: the last
     * frame and the palette it is drawn with are both already here. So it works on a machine that is
     * paused or stopped at a breakpoint, which is when somebody is most likely to want a picture of
     * one.
     */
    public @Nullable BufferedImage snapshot(final ScreenScale scale) {
        synchronized (frameLock) {
            if (!hasFrame) {
                return null;
            }

            return switch (videoFilter) {
                case NTSC -> FrameRenderer.render(frame, ntsc(), framePhase, crop, scale.factor());
                case CRT -> FrameRenderer.render(
                        frame, palette, filterStrength, warp, crop, scale.factor());
                case NONE -> FrameRenderer.render(frame, palette, crop, scale.factor());
            };
        }
    }

    /**
     * Colours everything from now on with {@code filter}, including the frame already on screen.
     * <p>
     * Called on the event dispatch thread, like {@link #setPalette}, and for the same reason: with
     * the emulator paused the picture behind the menu is the comparison, and there is no next frame
     * coming to apply the change to.
     * <p>
     * All three at once rather than a setter each, because the window has one place that decides
     * all of them and a picture redrawn three times for one menu click would be the decoder's two
     * milliseconds paid for twice over.
     */
    public void setVideoFilter(
            final VideoFilter filter, final FilterStrength strength, final boolean warp) {
        synchronized (frameLock) {
            this.videoFilter = filter;
            this.filterStrength = strength;
            this.warp = warp;

            if (ntsc != null) {
                ntsc.setStrength(strength);
            }

            colourise();
        }

        repaint();
    }

    /**
     * Draws everything from now on in {@code palette}, including the frame already on screen.
     * <p>
     * Called on the event dispatch thread, which is what makes flicking through the palettes a
     * live comparison: the picture behind the dialog changes on every selection, and it changes
     * even with the emulator paused, because the recolouring works from the frame that is already
     * here rather than from the next one.
     */
    public void setPalette(final NESPalette palette) {
        synchronized (frameLock) {
            this.palette = palette.colours();
            colourise();
        }

        repaint();
    }

    /**
     * Turns the frame's colour indices into the image. The caller holds {@link #frameLock}.
     * <p>
     * The composite decoder costs a couple of milliseconds a frame where the palette costs tens of
     * microseconds, and it is done here rather than on the event dispatch thread for the same
     * reason the palette is: this is the one place both a new frame and a changed setting can reach,
     * so a picture that is already on screen is redrawn by the same code that drew it.
     */
    private void colourise() {
        if (!hasFrame) {
            return;
        }

        if (videoFilter == VideoFilter.NTSC) {
            System.arraycopy(ntsc().colourise(frame, framePhase), 0, pixels, 0, pixels.length);
            return;
        }

        // The tube comes through here too, and unchanged: what it does is a fact about the rows of
        // the picture on screen rather than about the colour of a pixel, and this image is one
        // pixel per pixel the chip drew. It is tubeAt that turns this into the other one, at the
        // size the window is drawing, where those rows exist.
        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = palette[frame[i]];
        }
    }

    private NTSCFilter ntsc() {
        if (ntsc == null) {
            ntsc = new NTSCFilter(filterStrength);
        }

        return ntsc;
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);

        var g2 = (Graphics2D) g.create();

        try {
            g2.setColor(Color.BLACK);
            g2.fillRect(0, 0, getWidth(), getHeight());

            // Nearest neighbour: a blurred NES picture looks worse than a blocky one.
            g2.setRenderingHint(
                    RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

            // Uniform scale, centred, so the aspect ratio survives a resize in either direction.
            var fit = Math.min(
                    getWidth() / (double) columns(),
                    getHeight() / (double) lines());
            var width = (int) (columns() * fit);
            var height = (int) (lines() * fit);
            var x = (getWidth() - width) / 2;
            var y = (getHeight() - height) / 2;

            var onATube = tubeAt(width, height);

            if (onATube != null) {
                g2.drawImage(onATube, x, y, null);
            } else {
                synchronized (frameLock) {
                    g2.drawImage(
                            image,
                            x, y, x + width, y + height,
                            left(), top(),
                            left() + columns(), top() + lines(),
                            null);
                }
            }

            if (rewinding) {
                drawRewindMarker(g2, x, y, height);
            }

        } finally {
            g2.dispose();
        }
    }

    /**
     * The picture as a tube would show it at this size, or null when a tube is not what is drawing.
     * <p>
     * Unlike the other two filters this one cannot be handed to {@link Graphics2D} to magnify:
     * where a scanline goes is a question about the rows of the picture on screen, and a bent
     * picture is not a rectangle for {@code drawImage} to stretch at all. So the whole picture is
     * built here at the size it is going to be drawn -- magnification included -- and blitted one
     * for one.
     * <p>
     * The magnification is a fraction whenever the window has been dragged to something that is not
     * a multiple, and {@link CRTScreen} is built to answer for one, so the real number goes in
     * rather than a rounded picture that would not line up with the pixels around it.
     * <p>
     * The frame is copied out from under {@link #frameLock} before the pass rather than the pass
     * running inside it. The lock is otherwise held for tens of microseconds sixty times a second
     * and this is a couple of milliseconds; holding it for that would be the emulation thread
     * waiting on the drawing, which is the one thing the two buffers exist to avoid.
     */
    private @Nullable BufferedImage tubeAt(final int width, final int height) {
        if (width <= 0 || height <= 0) {
            return null;
        }

        var source = tubeSource;
        FilterStrength strength;
        boolean curved;

        synchronized (frameLock) {
            if (videoFilter != VideoFilter.CRT) {
                return null;
            }

            if (source == null) {
                source = new int[pixels.length];
                tubeSource = source;
            }

            System.arraycopy(pixels, 0, source, 0, pixels.length);
            strength = filterStrength;
            curved = warp;
        }

        var target = tube;

        if (target == null || target.getWidth() != width || target.getHeight() != height) {
            target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            tube = target;
            tubePixels = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
        }

        CRTScreen.draw(
                source,
                crop,
                tubePixels,
                width,
                height,
                strength,
                curved);

        return target;
    }

    /**
     * A pair of triangles pointing back the way the game is going, in the corner a video recorder
     * used to put them.
     * <p>
     * The <em>bottom</em> left, which is the one decision here worth explaining: the top is where a
     * NES game keeps its score, its lives and its timer, and a marker over Super Mario Bros.'s
     * MARIO 000000 is a marker in the way. Almost nothing puts a status bar along the bottom.
     * <p>
     * Sized off the picture rather than off the window, so it stays the same size relative to the
     * game at every scale, and drawn twice -- once offset in black -- because a translucent white
     * mark on its own disappears into a bright sky.
     */
    private static void drawRewindMarker(
            final Graphics2D g2, final int x, final int y, final int height) {
        var size = Math.max(MARKER_MINIMUM, height / MARKER_HEIGHT_DIVISOR);
        var arrow = size * 3 / 4;
        var gap = Math.max(1, size / 5);
        var margin = size * 2 / 3;
        var offset = Math.max(1, size / 12);

        var left = x + margin;
        var top = y + height - margin - size;

        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // The shadow first and the mark over it, so the offset copy reads as a shadow rather than
        // as a third triangle.
        for (var shadow = 1; shadow >= 0; shadow--) {
            g2.setColor(shadow == 1 ? MARKER_SHADOW : MARKER_FILL);

            for (var triangle = 0; triangle < 2; triangle++) {
                var start = left + triangle * (arrow + gap) + shadow * offset;
                var line = top + shadow * offset;

                g2.fillPolygon(
                        new int[]{start + arrow, start + arrow, start},
                        new int[]{line, line + size, line + size / 2},
                        3);
            }
        }
    }
}
