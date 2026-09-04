package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.PauseBox;
import com.github.dimiro1.mynes.ui.PauseControl;

import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A window over palette RAM: the thirty two bytes the whole picture is coloured through, and what
 * each one of them is.
 * <p>
 * The fourth of the debug viewers and the one the other three lean on. The CHR viewer shows the
 * tiles a game has, the nametable viewer shows where it has put them, the OAM viewer shows the
 * sprites over the top -- and every one of those three draws through these thirty two bytes, so a
 * game whose whole screen has gone the wrong colour has usually written one of them rather than
 * done anything at all to the other three.
 * <p>
 * Three things about palette RAM are invisible in the picture, and all three are what this is for.
 * Four of the cells are not memory: $3F10, $3F14, $3F18 and $3F1C are the matching background
 * cells, so a game that sets a sprite palette's first colour has set the screen's background.
 * Three more are memory nothing draws: $3F04, $3F08 and $3F0C hold whatever was put there and
 * never reach the screen, because a transparent background pixel takes the backdrop whichever
 * palette its tile was using. And $2001 can recolour the entire picture without touching a byte of
 * any of it, which is why the header says what $2001 is doing rather than folding it into the
 * swatches -- see {@link PaletteRAMPanel}.
 * <p>
 * Built the same way as the other two windows in this package: a timer, an unsynchronised read of
 * the machine, and a palette that follows Settings &gt; Palette... The worst case is a colour a
 * quarter of a second out of date.
 */
public final class PaletteViewerFrame extends JFrame {
    /**
     * How often the viewer re-reads palette RAM. The same quarter second the other viewers use; a
     * sweep is thirty two reads.
     */
    private static final int REFRESH_MILLIS = 250;

    private final PPU ppu;
    private final PaletteRAMPanel cells;
    private final PaletteUsePanel use;
    private final PauseBox pause;
    private final Timer refreshTimer;

    private final JLabel machine = new JLabel();
    private final JLabel pointer = new JLabel();

    public PaletteViewerFrame(
            final Component parent,
            final PPU ppu,
            final NESPalette palette,
            final PauseControl pauseControl) {

        this.ppu = ppu;
        this.cells = new PaletteRAMPanel(ppu, palette);
        this.use = new PaletteUsePanel(ppu, palette);
        this.pause = new PauseBox(pauseControl);
        this.refreshTimer = new Timer(REFRESH_MILLIS, e -> tick());

        init(parent);
        refresh();

        refreshTimer.start();
    }

    private void init(final Component parent) {
        setTitle("Palette Viewer");
        setResizable(false);
        setLayout(new BorderLayout());

        machine.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        pointer.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pointer.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));

        // Moving over a cell chooses it, and moving off the swatches does not un-choose it.
        // The screen beside them is the answer, and an answer that vanished as soon as the pointer
        // left to go and look at it would be no answer at all.
        cells.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(final MouseEvent e) {
                var cell = PaletteRAMPanel.cellAt(e.getX(), e.getY());

                if (cell >= 0) {
                    select(cell);
                }
            }
        });

        var header = new JPanel(new BorderLayout());
        header.add(machine, BorderLayout.NORTH);
        header.add(pointer, BorderLayout.SOUTH);

        // Top left rather than filling, so that the swatches' two headings and the screen's one
        // sit on the same line; the window is packed around the longest line the pointer can show,
        // which may be a little wider than the two panels together.
        var swatches = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        swatches.add(cells);

        var body = new JPanel(new BorderLayout());
        body.add(swatches, BorderLayout.WEST);
        body.add(use, BorderLayout.CENTER);

        var controls = new JPanel(new BorderLayout());
        controls.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        controls.add(pause, BorderLayout.EAST);

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        describeMachine();

        // The widest line this window will ever show, in the label, while the window is being
        // sized around it. Packing around the swatches instead would leave the notes on the seven
        // cells that need one to be drawn with an ellipsis through the middle, on whichever
        // platform draws a monospaced 12 a shade wider than this one does.
        pointer.setText(longestLine());

        pack();

        select(-1);
        pause.installIn(getRootPane());
        setLocationRelativeTo(parent);
    }

    /**
     * Draws the cells in {@code palette} from now on, following Settings &gt; Palette...
     */
    public void setPalette(final NESPalette palette) {
        cells.setPalette(palette);
        use.setPalette(palette);
    }

    /**
     * Stops the refresh timer along with the window; without this the timer would keep the viewer
     * reading a dead machine's palette forever.
     */
    @Override
    public void dispose() {
        refreshTimer.stop();
        super.dispose();
    }

    /**
     * One tick of the refresh timer, which does nothing at all while the window is put away. The
     * first draw goes through {@link #refresh()} directly instead: a window that waited for the
     * timer would come up empty for a quarter of a second, and one painted into an image without
     * ever being shown would come up empty for good.
     */
    private void tick() {
        if (isShowing()) {
            refresh();
        }
    }

    private void refresh() {
        cells.refresh();
        use.refresh();
        pause.refresh();
        describeMachine();
    }

    /**
     * The two things $2001 does to a colour on its way out of the chip, neither of which changes a
     * byte of palette RAM. A screen that has gone monochrome, or gone dark, with every cell below
     * still holding what the game meant, is one of these two and nothing else.
     */
    private void describeMachine() {
        var suffix = ppu.isGreyscale() || ppu.getEmphasis() != 0
                ? "   -- the screen is not these colours"
                : "";

        machine.setText(String.format(
                "greyscale %s   emphasis %s%s",
                ppu.isGreyscale() ? "on" : "off",
                emphasis(),
                suffix));
    }

    /**
     * The emphasis bits named rather than numbered. They do not brighten the channel they name;
     * they dim the other two, so all three at once is a dark picture rather than an unchanged one.
     */
    private String emphasis() {
        var bits = ppu.getEmphasis();

        if (bits == 0) {
            return "none";
        }

        return ((bits & 1) != 0 ? "R" : "")
                + ((bits & 2) != 0 ? "G" : "")
                + ((bits & 4) != 0 ? "B" : "");
    }

    /**
     * Everything about the chosen cell, which is the question this window exists for: not "what
     * colour is that" but "which byte do I put a watchpoint on, is it even the byte I think it is,
     * and where on the screen is it".
     */
    private void select(final int index) {
        cells.setHovered(index);
        use.setCell(index);

        if (index < 0) {
            // A space rather than nothing at all, so the label keeps its height and the window
            // is not packed a line shorter than it will be a moment later.
            pointer.setText(" ");
            return;
        }

        pointer.setText(lineFor(index, cells.valueAt(index)));
    }

    /**
     * What a cell is, in one line: where it is, which palette it belongs to, the byte in it, and
     * the thing about it the swatch does not say.
     * <p>
     * The colour itself is deliberately not spelled out in RGB. The swatch beside the pointer is
     * the colour, and which RGB a six bit entry comes out as is a property of the palette somebody
     * chose in Settings &gt; Palette... rather than of the machine being debugged.
     */
    private static String lineFor(final int index, final int value) {
        var note = PaletteCells.noteOf(index);

        return String.format(
                "$%04X  %-12s  $%02X%s",
                PaletteCells.addressOf(index),
                PaletteCells.nameOf(index),
                value,
                note.isEmpty() ? "" : "   " + note);
    }

    /**
     * The longest of the thirty two lines, measured in characters, which is the same as measured in
     * pixels because the label is monospaced. Every field but the note is a fixed width, so this is
     * really asking which cell has the most to say for itself.
     */
    private static String longestLine() {
        var longest = "";

        for (var index = 0; index < PaletteCells.CELLS; index++) {
            var line = lineFor(index, 0xFF);

            if (line.length() > longest.length()) {
                longest = line;
            }
        }

        return longest;
    }
}
