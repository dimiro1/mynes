package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.PauseBox;
import com.github.dimiro1.mynes.ui.PauseControl;

import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

/**
 * A window over object attribute memory: all sixty four sprites, what each one is made of, and
 * where they are.
 * <p>
 * Four bytes a sprite and none of them visible in the picture -- a sprite drawn with the wrong
 * palette, behind the background rather than in front of it, flipped, or pointing at a tile in the
 * other pattern table looks like a game bug rather than an emulator one until somebody reads the
 * four bytes. So they are all here, decoded, beside the sprite they describe.
 * <p>
 * <b>A thing on the screen is usually several sprites</b>, and which several is a fact about the
 * game's code rather than about anything in OAM -- so the window guesses, by joining up the sprites
 * that touch each other and are drawn in the same palette. See {@link SpriteGroups}. The Group column says what it decided, and
 * <b>Group</b> puts the rows in that order and makes a click pick the whole thing rather than one
 * eighth of it. The field outlines the group as one shape, so what appears round a six sprite Mario
 * is Mario rather than four lines through him.
 * <p>
 * Shift and the command key still do what they do in any table, which is how to take a run of rows
 * or drop one out of a group the guess got wrong. Every selected sprite is outlined either way.
 * <p>
 * <b>On screen only</b> drops the sprites the game has parked below the picture, which on a quiet
 * frame is fifty of the sixty four and all of them identical -- the same test as the count in the
 * header, so the two never disagree.
 * <p>
 * <b>The guess is made again on every sweep, unless something is selected.</b> Groups that
 * re-formed four times a second under a moving game would shuffle rows out from under the pointer,
 * so selecting anything freezes them and clearing the selection lets them follow the screen again.
 * The filter is frozen with them, for the same reason and because a sprite that walked off the
 * bottom of the picture would otherwise take its row out from under a selection.
 * <p>
 * Built the same way as the CHR viewer: a timer, an unsynchronised read of the machine, and a
 * palette that follows Settings &gt; Palette... The worst case is a sprite a quarter of a second out
 * of date.
 */
public final class OAMViewerFrame extends JFrame {
    /**
     * How often the viewer re-reads OAM. The same quarter second the CHR viewer uses; a sweep is 256
     * bytes and 64 small tiles.
     */
    private static final int REFRESH_MILLIS = 250;

    private static final int SPRITES = 64;

    /**
     * Every sprite is eight wide and either eight or sixteen tall. The images are the taller size
     * always, and a short sprite simply leaves the bottom half unused, so nothing has to be
     * reallocated when a game switches $2000 bit 5 mid-frame.
     */
    private static final int MAX_HEIGHT = 16;

    private final PPU ppu;
    private final SpriteTable model = new SpriteTable();
    private final JTable table = new JTable(model);
    private final BufferedImage[] sprites = new BufferedImage[SPRITES];
    private final SpriteFieldPanel field;
    private final Timer refreshTimer;

    private final JLabel machine = new JLabel();
    private final JCheckBox grouped = new JCheckBox("Group");
    private final JCheckBox onScreenOnly = new JCheckBox("On screen only");
    private final PauseBox pause;

    private final int[] bytes = new int[SPRITES * 4];
    private final int[] left = new int[SPRITES];
    private final int[] top = new int[SPRITES];

    /**
     * Each sprite's palette, 0 to 3, taken out with the coordinates because the guess at what
     * belongs together needs all three -- see {@link SpriteGroups}.
     */
    private final int[] palettes = new int[SPRITES];

    /**
     * Which sprite each row of the table is showing. The identity until somebody asks for the rows
     * to be grouped, and the reason every column reads through it rather than through the row.
     */
    private final int[] order = new int[SPRITES];

    /**
     * How many of {@link #order} the table is showing, which is all sixty four until somebody asks
     * for the parked ones to be left out.
     */
    private int rows = SPRITES;

    /**
     * Which group the window thinks each sprite belongs to, shown whether or not the rows have been
     * put in that order: knowing that sprites 4, 5, 7 and 8 are one thing is worth having even in a
     * list that is still in OAM order.
     */
    private final int[] group = new int[SPRITES];

    /**
     * Set while the table's own selection is being put back, so that the listener does not take
     * that for somebody clicking and go round again.
     */
    private boolean adjustingSelection;

    private NESPalette palette;
    private int height = 8;

    public OAMViewerFrame(
            final Component parent,
            final PPU ppu,
            final NESPalette palette,
            final PauseControl pauseControl) {

        this.ppu = ppu;
        this.palette = palette;
        this.field = new SpriteFieldPanel(sprites, ppu, palette);
        this.pause = new PauseBox(pauseControl);
        this.refreshTimer = new Timer(REFRESH_MILLIS, e -> tick());

        for (var i = 0; i < SPRITES; i++) {
            order[i] = i;
            sprites[i] = new BufferedImage(8, MAX_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        }

        // Before the window is built, because the header label is one of the things it fills in
        // and pack() sizes the window from a label with something in it. An empty one is sixteen
        // pixels shorter, and every one of those pixels comes off the bottom of the field beside
        // the table.
        refresh();
        init(parent);

        refreshTimer.start();
    }

    private void init(final Component parent) {
        setTitle("OAM Viewer");
        setResizable(false);
        setLayout(new BorderLayout());

        machine.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        table.setRowHeight(MAX_HEIGHT * 2 + 4);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setDefaultRenderer(BufferedImage.class, new SpriteRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!adjustingSelection) {
                selectionChanged(e.getValueIsAdjusting());
            }
        });

        grouped.setToolTipText(
                "Put the sprites that touch each other together, and pick them with one click");
        grouped.addActionListener(e -> regroup(spritesIn(table.getSelectedRows())));

        onScreenOnly.setToolTipText(
                "Leave out the sprites the game has parked below the picture");
        onScreenOnly.addActionListener(e -> regroup(spritesIn(table.getSelectedRows())));

        for (var column = 0; column < model.getColumnCount(); column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(model.widthOf(column));
        }

        var listing = new JScrollPane(table);
        listing.setPreferredSize(new java.awt.Dimension(490, PPU.SCREEN_HEIGHT * 2));

        var body = new JPanel(new BorderLayout(8, 0));
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        body.add(listing, BorderLayout.CENTER);
        body.add(field, BorderLayout.EAST);

        var options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        options.add(grouped);
        options.add(onScreenOnly);

        // Pause at the far end, away from the ticks that only change what is drawn: this one
        // changes the machine, which is a different kind of thing to be clicking.
        var controls = new JPanel(new BorderLayout());
        controls.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        controls.add(options, BorderLayout.WEST);
        controls.add(pause, BorderLayout.EAST);

        add(machine, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);

        pack();
        pause.installIn(getRootPane());
        setLocationRelativeTo(parent);
    }

    /**
     * Draws the sprites in {@code palette} from now on, following Settings &gt; Palette...
     */
    public void setPalette(final NESPalette palette) {
        this.palette = palette;
        field.setPalette(palette);
        refresh();
    }

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

    /**
     * One sweep: the 256 bytes, then the 64 tiles they name.
     * <p>
     * Read in one pass and drawn from that copy rather than read again per column, so that every
     * row of the table describes one moment of the machine rather than four.
     */
    private void refresh() {
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = ppu.peekOAM(i);
        }

        height = ppu.getSpriteHeight();

        for (var sprite = 0; sprite < SPRITES; sprite++) {
            left[sprite] = bytes[sprite * 4 + 3];
            top[sprite] = bytes[sprite * 4];
            palettes[sprite] = bytes[sprite * 4 + 2] & 3;

            render(sprite);
        }

        // Only while nothing is selected. A guess made again four times a second is the one
        // worth having when the window is being watched, and the one thing that must not happen
        // while it is being used: the rows would move out from under the click on its way down.
        if (table.getSelectionModel().isSelectionEmpty()) {
            regroup(new int[0]);
        } else {
            model.fireTableRowsUpdated(0, Math.max(0, rows - 1));
        }

        field.setPositions(left, top, height);
        pause.refresh();
        describeMachine();
    }

    /**
     * The three things that decide what the sixty four are and are not in any one of them, and what
     * the window has made of them. Said again whenever the rows change as well as on every sweep,
     * so that a count nobody could arrive at by looking at the table never sits in the header.
     */
    private void describeMachine() {
        var groups = groups();

        machine.setText(String.format(
                "8x%d sprites   pattern %s   %d on screen   %d group%s",
                height, patterns(), onScreen(), groups, groups == 1 ? "" : "s"));
    }

    /**
     * One sprite, decoded into its own little image.
     * <p>
     * A tall sprite ignores $2000's table bit: the tile number's low bit picks the table and the
     * rest of it picks a pair of tiles, the second being the bottom half. Flipping one vertically
     * swaps which half goes where as well as turning each half over, which is the one part of this
     * that is easy to get subtly wrong and invisible when it is.
     */
    private void render(final int sprite) {
        var image = sprites[sprite];
        var pixels = ((java.awt.image.DataBufferInt)
                image.getRaster().getDataBuffer()).getData();
        var tile = bytes[sprite * 4 + 1];
        var attributes = bytes[sprite * 4 + 2];
        var flipX = (attributes & 0x40) != 0;
        var flipY = (attributes & 0x80) != 0;
        var colours = Tiles.coloursOf(ppu, palette, 0x10 + (attributes & 3) * 4, true);

        Arrays.fill(pixels, 0);

        if (height == 8) {
            Tiles.draw(
                    pixels, 8, 0, 0, ppu,
                    ppu.getSpritePatternTable() + tile * 16, colours, flipX, flipY);

            return;
        }

        var base = ((tile & 1) << 12) | ((tile & 0xFE) << 4);

        Tiles.draw(pixels, 8, 0, flipY ? 8 : 0, ppu, base, colours, flipX, flipY);
        Tiles.draw(pixels, 8, 0, flipY ? 0 : 8, ppu, base + 16, colours, flipX, flipY);
    }

    /**
     * Where the sprites are taking their tiles from, spelled the way it actually works: one address
     * for short sprites, and "the tile number's low bit" for tall ones.
     */
    private String patterns() {
        return height == 16
                ? "$0000/$1000 by tile bit 0"
                : String.format("$%04X", ppu.getSpritePatternTable());
    }

    /**
     * Somebody has clicked, shift-clicked or dragged in the table.
     * <p>
     * A plain click on a single row is taken to mean the whole group while the rows are grouped,
     * which is the point of grouping them: a person pointing at Mario's foot means Mario. Shift and
     * the command key are left alone, so the run they build and the sprite they drop are exactly
     * what they look like -- and dropping one is how to disagree with a guess that joined two
     * things together.
     */
    private void selectionChanged(final boolean adjusting) {
        var rows = table.getSelectedRows();

        if (!adjusting && grouped.isSelected() && rows.length == 1) {
            selectGroup(group[order[rows[0]]]);
            return;
        }

        field.setSelected(spritesIn(rows));
    }

    private void selectGroup(final int number) {
        var chosen = new ArrayList<Integer>();

        for (var row = 0; row < rows; row++) {
            if (group[order[row]] == number) {
                chosen.add(row);
            }
        }

        adjustingSelection = true;

        try {
            table.clearSelection();

            for (var row : chosen) {
                table.addRowSelectionInterval(row, row);
            }
        } finally {
            adjustingSelection = false;
        }

        field.setSelected(spritesIn(table.getSelectedRows()));
    }

    /**
     * Works the groups out again, and the row order with them when somebody has asked for it.
     *
     * @param keep the sprites the selection is on, which are somewhere else once the rows have
     *             moved.
     */
    private void regroup(final int[] keep) {
        System.arraycopy(
                SpriteGroups.of(left, top, palettes, height), 0, group, 0, SPRITES);

        var listed = new ArrayList<Integer>();

        for (var sprite = 0; sprite < SPRITES; sprite++) {
            if (!onScreenOnly.isSelected() || isOnScreen(sprite)) {
                listed.add(sprite);
            }
        }

        if (grouped.isSelected()) {
            // Group by group, and inside a group by sprite number -- which is the order the
            // hardware considers them in, so the one that covers the others is still first.
            listed.sort(Comparator
                    .comparingInt((Integer sprite) -> group[sprite])
                    .thenComparingInt(sprite -> sprite));
        }

        var before = rows;

        rows = listed.size();

        for (var i = 0; i < rows; i++) {
            order[i] = listed.get(i);
        }

        adjustingSelection = true;

        try {
            // A table that has gained or lost rows needs to be told so rather than merely told its
            // contents changed, and being told costs the selection -- which is put back below out
            // of the sprites it was on rather than out of the rows, because the rows have moved.
            if (rows == before) {
                model.fireTableRowsUpdated(0, Math.max(0, rows - 1));
            } else {
                model.fireTableDataChanged();
            }

            table.clearSelection();

            for (var sprite : keep) {
                var row = rowOf(sprite);

                if (row >= 0) {
                    table.addRowSelectionInterval(row, row);
                }
            }
        } finally {
            adjustingSelection = false;
        }

        field.setSelected(spritesIn(table.getSelectedRows()));
        describeMachine();

        if (rowOf(first(keep)) >= 0) {
            table.scrollRectToVisible(table.getCellRect(rowOf(first(keep)), 0, true));
        }
    }

    /**
     * The first of a selection, or -1 for none -- which {@link #rowOf} answers -1 to, so a caller
     * only has one thing to check.
     */
    private static int first(final int[] sprites) {
        return sprites.length == 0 ? -1 : sprites[0];
    }

    private int[] spritesIn(final int[] rows) {
        var chosen = new int[rows.length];

        for (var i = 0; i < rows.length; i++) {
            chosen[i] = order[rows[i]];
        }

        return chosen;
    }

    /**
     * Which row a sprite is on, or -1 when the table is not showing it -- which is what the filter
     * does to the fifty a game has put away.
     */
    private int rowOf(final int sprite) {
        for (var row = 0; row < rows; row++) {
            if (order[row] == sprite) {
                return row;
            }
        }

        return -1;
    }

    /**
     * How many things the window thinks it is showing, which is the number the guess is worth
     * judging by: sixty four says it joined nothing up, one says it joined everything.
     * <p>
     * Counted over the rows rather than over OAM, so that it says two when two are listed. A number
     * in the header that nobody could arrive at by counting the table would be worse than no number.
     */
    private int groups() {
        var seen = new java.util.HashSet<Integer>();

        for (var row = 0; row < rows; row++) {
            seen.add(group[order[row]]);
        }

        return seen.size();
    }

    /**
     * How many of the sixty four could be seen at all. Y is compared against the line being
     * evaluated, so anything from 239 up is parked rather than drawn -- which is how a game turns a
     * sprite off, and the number worth having at a glance.
     */
    private int onScreen() {
        var count = 0;

        for (var sprite = 0; sprite < SPRITES; sprite++) {
            if (isOnScreen(sprite)) {
                count++;
            }
        }

        return count;
    }

    /**
     * Whether a sprite lands anywhere in the picture at all. The same test the count above uses, so
     * that the number in the header and the rows the filter leaves are never two different answers.
     */
    private boolean isOnScreen(final int sprite) {
        return top[sprite] < PPU.SCREEN_HEIGHT - 1;
    }

    /**
     * The sixty four sprites, one per row.
     * <p>
     * The columns are the four bytes as they are in memory and then what they mean, rather than one
     * or the other: the byte is what a watchpoint catches and the meaning is what the question was
     * about, and having to convert between them by hand is the thing this window exists to stop.
     */
    private final class SpriteTable extends AbstractTableModel {
        private final String[] columns = {
                "Group", "#", "", "X", "Y", "Tile", "Palette", "Priority", "Flip", "OAM",
        };

        private final int[] widths = {
                48, 28, 40, 40, 40, 48, 56, 64, 44, 48,
        };

        int widthOf(final int column) {
            return widths[column];
        }

        @Override
        public int getRowCount() {
            return rows;
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(final int column) {
            return columns[column];
        }

        @Override
        public Class<?> getColumnClass(final int column) {
            return column == 2 ? BufferedImage.class : String.class;
        }

        /**
         * Read through {@link #order} rather than straight off the row, which is the whole of what
         * Nearest first does: the rows move and the sixty four sprites do not.
         */
        @Override
        public Object getValueAt(final int row, final int column) {
            var sprite = order[row];
            var attributes = bytes[sprite * 4 + 2];

            return switch (column) {
                case 0 -> groupLabel(row, sprite);
                case 1 -> Integer.toString(sprite);
                case 2 -> sprites[sprite];
                case 3 -> String.format("$%02X", bytes[sprite * 4 + 3]);
                case 4 -> String.format("$%02X", bytes[sprite * 4]);
                case 5 -> String.format("$%02X", bytes[sprite * 4 + 1]);
                case 6 -> Integer.toString(4 + (attributes & 3));
                case 7 -> (attributes & 0x20) != 0 ? "behind" : "front";
                case 8 -> flip(attributes);
                default -> String.format("$%02X", sprite * 4);
            };
        }

        /**
         * Which group a row is in, said the way that row's position makes useful.
         * <p>
         * In group order the number goes on the first row of each run and how many are in it beside
         * it, and the rest of the run is left blank -- so the table reads as blocks, which is the
         * grouping made visible and the nearest thing to a tree a table can be. Out of that order
         * there are no runs to head, so every row carries its own number and the size is dropped:
         * it would be the same figure repeated down the column, saying nothing the number does not.
         */
        private String groupLabel(final int row, final int sprite) {
            if (!grouped.isSelected()) {
                return Integer.toString(group[sprite] + 1);
            }

            if (row > 0 && group[order[row - 1]] == group[sprite]) {
                return "";
            }

            return group[sprite] + 1 + " (" + sizeOf(group[sprite]) + ")";
        }

        private int sizeOf(final int number) {
            var count = 0;

            for (var sprite = 0; sprite < SPRITES; sprite++) {
                if (group[sprite] == number) {
                    count++;
                }
            }

            return count;
        }

        private String flip(final int attributes) {
            var horizontal = (attributes & 0x40) != 0;
            var vertical = (attributes & 0x80) != 0;

            if (!horizontal && !vertical) {
                return "";
            }

            return (horizontal ? "H" : "") + (vertical ? "V" : "");
        }
    }

    /**
     * The sprite itself in a table cell, drawn at twice size and with nearest neighbour scaling:
     * pixel art scaled smoothly turns to mush.
     */
    private final class SpriteRenderer extends JComponent
            implements javax.swing.table.TableCellRenderer {

        private BufferedImage image;
        private boolean selected;

        @Override
        public Component getTableCellRendererComponent(
                final JTable table,
                final Object value,
                final boolean isSelected,
                final boolean hasFocus,
                final int row,
                final int column) {

            image = (BufferedImage) value;
            selected = isSelected;

            return this;
        }

        @Override
        protected void paintComponent(final Graphics g) {
            var g2 = (java.awt.Graphics2D) g.create();

            try {
                g2.setColor(selected ? table.getSelectionBackground() : table.getBackground());
                g2.fillRect(0, 0, getWidth(), getHeight());

                if (image == null) {
                    return;
                }

                // Centred rather than pinned to the top, so that a row does not appear to change
                // height when the game switches to tall sprites.
                g2.setRenderingHint(
                        RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
                g2.drawImage(
                        image,
                        4,
                        (getHeight() - height * 2) / 2,
                        8 * 2,
                        height * 2,
                        null);
            } finally {
                g2.dispose();
            }
        }
    }
}
