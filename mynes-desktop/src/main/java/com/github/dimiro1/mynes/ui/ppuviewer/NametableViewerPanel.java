package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.PauseBox;
import com.github.dimiro1.mynes.ui.Sweep;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * A view of the nametables: the four of them side by side, the scroll window drawn over the top,
 * and whatever the pointer is on named underneath.
 * <p>
 * Beside the CHR viewer rather than inside it, and built the same way -- a {@link Sweep}, an
 * unsynchronised read of the machine, and a palette that follows Settings &gt; Palette... -- because
 * it answers the question that one cannot. The CHR viewer shows the tiles a game <em>has</em>; this
 * shows where it has <em>put</em> them, and a PPU bug is almost always about the second.
 * <p>
 * <b>Two of the four panes are usually the same memory.</b> The console has two kilobytes of
 * nametable RAM and four kilobytes of address space for it, and the cartridge decides which pairs
 * share -- so with horizontal mirroring the top two panes are one nametable drawn twice. The label
 * says which arrangement is in force, because a game that appears to have written the same screen
 * twice has usually done nothing of the kind.
 */
public final class NametableViewerPanel extends JPanel {
    /**
     * How often the view re-reads the nametables. The same quarter second the CHR viewer uses, and
     * the same reasoning: fast enough to feel live, and a sweep is about a millisecond.
     */
    private static final int REFRESH_MILLIS = 250;

    private final NES nes;
    private final NametablePanel nametables;

    private final JLabel machine = new JLabel();
    private final JLabel pointer = new JLabel();

    /**
     * @param pause the Pause tick to put at the far end of the options row, or null where whatever
     *              holds this view owns the one tick over the machine. The view neither makes one
     *              nor refreshes it -- see {@link PauseBox} -- it only says where one goes.
     */
    public NametableViewerPanel(
            final NES nes, final NESPalette palette, final @Nullable PauseBox pause) {

        this.nes = nes;
        this.nametables = new NametablePanel(nes.getPPU(), palette);

        init(pause);
        refresh();

        Sweep.every(REFRESH_MILLIS, this, this::refresh);
    }

    private void init(final @Nullable PauseBox pause) {
        setLayout(new BorderLayout());

        machine.setBorder(BorderFactory.createEmptyBorder(8, 12, 4, 12));
        pointer.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pointer.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));

        var grid = new JCheckBox("Tile grid");
        var scroll = new JCheckBox("Scroll window", true);

        grid.addActionListener(e -> nametables.setGridVisible(grid.isSelected()));
        scroll.addActionListener(e -> nametables.setScrollVisible(scroll.isSelected()));

        nametables.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(final MouseEvent e) {
                describePointer(e.getX(), e.getY());
            }
        });

        nametables.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(final MouseEvent e) {
                pointer.setText(" ");
            }
        });

        var header = new JPanel(new BorderLayout());
        header.add(machine, BorderLayout.NORTH);
        header.add(pointer, BorderLayout.SOUTH);

        var options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        options.add(grid);
        options.add(scroll);

        // Pause at the far end, away from the ticks that only change what is drawn: this one
        // changes the machine, which is a different kind of thing to be clicking.
        var controls = new JPanel(new BorderLayout());
        controls.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        controls.add(options, BorderLayout.WEST);

        if (pause != null) {
            controls.add(pause, BorderLayout.EAST);
        }

        add(header, BorderLayout.NORTH);
        add(nametables, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        describeMachine();
        pointer.setText(" ");
    }

    /**
     * Draws the nametables in {@code palette} from now on. Called on the event dispatch thread when
     * the choice in Settings &gt; Palette... changes, so the view tracks the picture rather than
     * keeping whatever was chosen when it opened.
     */
    public void setPalette(final NESPalette palette) {
        nametables.setPalette(palette);
    }

    /**
     * One sweep. Called once directly as the view is built as well as by the timer: a view that
     * waited for its first tick would come up blank for a quarter of a second, and one painted into
     * an image without ever being shown would come up blank for good.
     */
    public void refresh() {
        nametables.refresh();
        describeMachine();
    }

    /**
     * The three things that decide what is on screen and are not visible in the picture: which pairs
     * of nametables share memory, which half of the pattern tables the background is coming out of,
     * and where the scroll is.
     */
    private void describeMachine() {
        var ppu = nes.getPPU();

        machine.setText(String.format(
                "%s mirroring   background $%04X   scroll %d, %d",
                mirroring(),
                ppu.getBackgroundPatternTable(),
                scrollX(),
                scrollY()));
    }

    private String mirroring() {
        return switch (nes.getBus().getMapper().mirroring()) {
            case HORIZONTAL -> "Horizontal";
            case VERTICAL -> "Vertical";
            case FOUR_SCREEN -> "Four screen";
            case ONE_SCREEN_LOW -> "One screen, low";
            case ONE_SCREEN_HIGH -> "One screen, high";
        };
    }

    private int scrollX() {
        var ppu = nes.getPPU();

        return (ppu.getT() & 0x1F) * NametablePanel.TILE + ppu.getFineX();
    }

    private int scrollY() {
        var t = nes.getPPU().getT();

        return ((t >> 5) & 0x1F) * NametablePanel.TILE + ((t >> 12) & 7);
    }

    /**
     * Everything about the tile under the pointer, which is the question this view exists for: not
     * "what does that look like" but "which byte do I put a watchpoint on".
     */
    private void describePointer(final int x, final int y) {
        if (x < 0 || y < 0 || x >= NametablePanel.WIDTH || y >= NametablePanel.HEIGHT) {
            return;
        }

        var table = (y / NametablePanel.SCREEN_HEIGHT) * 2 + (x / NametablePanel.SCREEN_WIDTH);
        var column = (x % NametablePanel.SCREEN_WIDTH) / NametablePanel.TILE;
        var row = (y % NametablePanel.SCREEN_HEIGHT) / NametablePanel.TILE;
        var base = 0x2000 + table * 0x400;
        var name = base + row * NametablePanel.COLUMNS + column;
        var attribute = base + 0x3C0 + (row / 4) * 8 + (column / 4);
        var ppu = nes.getPPU();
        var tile = ppu.peekVRAM(name);

        pointer.setText(String.format(
                "$%04X  col %2d row %2d   tile $%02X at $%04X   attr $%04X = $%02X",
                name,
                column,
                row,
                tile,
                ppu.getBackgroundPatternTable() + tile * 16,
                attribute,
                ppu.peekVRAM(attribute)));
    }
}
