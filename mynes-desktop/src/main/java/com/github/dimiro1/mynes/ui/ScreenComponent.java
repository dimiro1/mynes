package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.palette.Palettes;
import com.github.dimiro1.mynes.video.FrameRenderer;
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
 * {@link #present(int[])}, the event dispatch thread paints it, and a lock covers both buffers so
 * that a paint never catches a half copied picture. The lock is held for an arraycopy of 240KB and
 * 61440 array lookups, sixty times a second -- tens of microseconds, so neither side waits for
 * long.
 * <p>
 * The picture is cropped, by {@link FrameRenderer#OVERSCAN_TOP} and its neighbours, which is also
 * where the reason for it is written down.
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
     * How the frame is coloured: through {@link #palette}, or by decoding the signal.
     */
    private VideoFilter videoFilter = VideoFilter.NONE;

    /**
     * The composite decoder, built the first time somebody asks for it. A window that is never
     * switched to it should not carry its tables.
     */
    private @Nullable NTSCFilter ntsc;

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
        setPreferredSize(new Dimension(
                PPU.SCREEN_WIDTH * scale.factor(),
                FrameRenderer.VISIBLE_HEIGHT * scale.factor()));

        revalidate();
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
     * Called from the emulation thread, like {@link #present(int[])}, and by the same rule: that is
     * the thread that knows whether the machine is actually going backwards, which is not the same
     * question as whether the key is down -- a paused machine, or a history that has run out, is
     * a key held with nothing happening.
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

            return videoFilter == VideoFilter.NTSC
                    ? FrameRenderer.render(frame, ntsc(), framePhase, true, scale.factor())
                    : FrameRenderer.render(frame, palette, true, scale.factor());
        }
    }

    /**
     * Draws everything from now on in {@code palette}, including the frame already on screen.
     * <p>
     * Called on the event dispatch thread, which is what makes flicking through the palettes a
     * live comparison: the picture behind the dialog changes on every selection, and it changes
     * even with the emulator paused, because the recolouring works from the frame that is already
     * here rather than from the next one.
     */
    /**
     * Colours everything from now on with {@code filter}, including the frame already on screen.
     * <p>
     * Called on the event dispatch thread, like {@link #setPalette}, and for the same reason: with
     * the emulator paused the picture behind the menu is the comparison, and there is no next frame
     * coming to apply the change to.
     */
    public void setVideoFilter(final VideoFilter filter) {
        synchronized (frameLock) {
            this.videoFilter = filter;
            colourise();
        }

        repaint();
    }

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

        for (var i = 0; i < pixels.length; i++) {
            pixels[i] = palette[frame[i]];
        }
    }

    private NTSCFilter ntsc() {
        if (ntsc == null) {
            ntsc = new NTSCFilter();
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
            var scale = Math.min(
                    getWidth() / (double) PPU.SCREEN_WIDTH,
                    getHeight() / (double) FrameRenderer.VISIBLE_HEIGHT);
            var width = (int) (PPU.SCREEN_WIDTH * scale);
            var height = (int) (FrameRenderer.VISIBLE_HEIGHT * scale);
            var x = (getWidth() - width) / 2;
            var y = (getHeight() - height) / 2;

            synchronized (frameLock) {
                g2.drawImage(
                        image,
                        x, y, x + width, y + height,
                        0, FrameRenderer.OVERSCAN_TOP,
                        PPU.SCREEN_WIDTH, FrameRenderer.VISIBLE_BOTTOM,
                        null);
            }

            if (rewinding) {
                drawRewindMarker(g2, x, y, height);
            }

        } finally {
            g2.dispose();
        }
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
