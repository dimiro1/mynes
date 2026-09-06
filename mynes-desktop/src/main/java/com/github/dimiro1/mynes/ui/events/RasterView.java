package com.github.dimiro1.mynes.ui.events;

import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import org.jetbrains.annotations.Nullable;

import javax.swing.JComponent;
import javax.swing.event.MouseInputAdapter;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.function.Consumer;

/**
 * One frame as the beam draws it, with a mark wherever the game touched the hardware.
 * <p>
 * The picture is the argument for the whole instrument. A list of writes in the order they happened
 * says the game wrote $2005 twice; this says it wrote them a hundred and fourteen lines down,
 * which is a status bar, and the same list on a raster with the marks at line 30 is a bug. Nothing
 * else in the program can put a write anywhere on a screen.
 * <p>
 * <b>Every dot of the frame is here, not just the picture.</b> A television draws 256 by 240 out of
 * 341 by 262, and everything a game does in the twenty-two lines it cannot see is exactly the part
 * that has to happen there -- the transfer at $4014, the scroll reset, the palette. So the picture
 * is drawn as an area with the blanking around it rather than as the whole raster, and the two
 * borders say which is which.
 * <p>
 * Two dots to a pixel across and two lines to a pixel down, which keeps a frame's shape and makes
 * a scanline thick enough to see. The dot is good to within two anyway -- the machine is clocked a
 * CPU cycle at a time and three dots go past in one -- so a raster drawn dot for dot would be
 * claiming a precision that is not there.
 */
final class RasterView extends JComponent {
    /**
     * How many dots a scanline has, on either console. It is the number of scanlines that differs.
     */
    private static final int DOTS = 341;

    /**
     * What a television draws out of them.
     */
    private static final int VISIBLE_DOTS = 256;
    private static final int VISIBLE_LINES = 240;

    /**
     * How many screen pixels one dot and one scanline get. Two, so that a mark on one line is
     * distinguishable from a mark on the next.
     */
    private static final int SCALE = 2;

    /**
     * How wide a mark is drawn. Wider than a dot, because one pixel of colour on a grey field is
     * invisible and the dot is not accurate to a pixel anyway.
     */
    private static final int MARK = 4;

    /**
     * How near the pointer has to be, in screen pixels, for a mark to be the one it is over.
     */
    private static final int REACH = 6;

    private static final Font LABELS = new Font(Font.SANS_SERIF, Font.PLAIN, 9);

    /**
     * How far down a label's baseline has to be to be inside the component at all.
     */
    private static final int LABEL_HEIGHT = 8;

    /**
     * Room down the left for the scanline numbers.
     */
    private static final int GUTTER = 26;

    private final int lines;

    private List<Debugger.Event> events = List.of();

    RasterView(final Region region, final Consumer<Debugger.Event> hovering) {
        this.lines = region.scanlinesPerFrame();

        setPreferredSize(new Dimension(GUTTER + DOTS * SCALE + 1, lines * SCALE + 1));
        setMinimumSize(getPreferredSize());
        setOpaque(true);

        var pointer = new MouseInputAdapter() {
            @Override
            public void mouseMoved(final MouseEvent e) {
                hovering.accept(nearest(e.getX(), e.getY()));
            }

            @Override
            public void mouseExited(final MouseEvent e) {
                hovering.accept(null);
            }
        };

        addMouseMotionListener(pointer);
        addMouseListener(pointer);
    }

    void show(final List<Debugger.Event> events) {
        this.events = events;
        repaint();
    }

    @Override
    protected void paintComponent(final Graphics g) {
        g.setColor(Theme.background());
        g.fillRect(0, 0, getWidth(), getHeight());

        blanking(g);
        scanlineLabels(g);

        for (var event : events) {
            g.setColor(EventColours.of(event.kind()));
            g.fillRect(
                    xOf(event.dot()) - MARK / 2,
                    yOf(event.scanline()) - MARK / 2,
                    MARK,
                    MARK);
        }
    }

    /**
     * The part of the frame a television never showed, shaded, with the picture left clear inside
     * it. Shaded rather than outlined because what somebody is asking is "was this in the picture
     * or not", and a filled area answers that without being read.
     */
    private void blanking(final Graphics g) {
        var right = xOf(VISIBLE_DOTS);
        var bottom = yOf(VISIBLE_LINES);

        g.setColor(shade());
        g.fillRect(right, 0, getWidth() - right, getHeight());
        g.fillRect(GUTTER, bottom, right - GUTTER, getHeight() - bottom);

        // The line the flag goes up on and the interrupt is served, which is the landmark every
        // other mark is read against.
        g.setColor(Theme.dim());
        g.drawLine(GUTTER, yOf(VISIBLE_LINES + 1), getWidth(), yOf(VISIBLE_LINES + 1));

        g.setColor(Theme.dim());
        g.drawRect(GUTTER, 0, right - GUTTER, bottom);
    }

    /**
     * A number every thirty-two lines down the left, which is enough to read a mark's line off to
     * within a few and no more than the gutter has room for. The exact number is what hovering is
     * for.
     */
    private void scanlineLabels(final Graphics g) {
        g.setFont(LABELS);
        g.setColor(Theme.muted());

        for (var line = 0; line < lines; line += 32) {
            // Centred on the line it names, except at the top, where centring would put half the
            // digits above the component and leave the one label somebody needs most unreadable.
            g.drawString(Integer.toString(line), 2, Math.max(LABEL_HEIGHT, yOf(line) + 4));
        }
    }

    private int xOf(final int dot) {
        return GUTTER + dot * SCALE;
    }

    private int yOf(final int scanline) {
        return scanline * SCALE;
    }

    /**
     * The mark under the pointer, or null. Nearest rather than first, so that a cluster in one
     * corner of a scanline can be picked apart by moving a pixel at a time.
     */
    private @Nullable Debugger.Event nearest(final int x, final int y) {
        Debugger.Event found = null;
        var closest = REACH * REACH;

        for (var event : events) {
            var dx = xOf(event.dot()) - x;
            var dy = yOf(event.scanline()) - y;
            var distance = dx * dx + dy * dy;

            if (distance <= closest) {
                closest = distance;
                found = event;
            }
        }

        return found;
    }

    /**
     * The blanking's shade: the muted colour at a sixth, which is a grey that reads as "outside"
     * without competing with a mark drawn on it. Strong enough to be seen at a glance, since
     * telling the picture from the blanking is the first thing anybody does with this.
     */
    private static Color shade() {
        var muted = Theme.muted();
        var back = Theme.background();

        return new Color(
                Math.round(muted.getRed() * 0.16f + back.getRed() * 0.84f),
                Math.round(muted.getGreen() * 0.16f + back.getGreen() * 0.84f),
                Math.round(muted.getBlue() * 0.16f + back.getBlue() * 0.84f));
    }
}
