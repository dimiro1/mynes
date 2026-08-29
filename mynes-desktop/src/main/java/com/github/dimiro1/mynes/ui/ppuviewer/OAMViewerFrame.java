package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;

import javax.swing.BorderFactory;
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
import java.awt.Font;
import java.awt.Graphics;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * A window over object attribute memory: all sixty four sprites, what each one is made of, and
 * where they are.
 * <p>
 * Four bytes a sprite and none of them visible in the picture -- a sprite drawn with the wrong
 * palette, behind the background rather than in front of it, flipped, or pointing at a tile in the
 * other pattern table looks like a game bug rather than an emulator one until somebody reads the
 * four bytes. So they are all here, decoded, beside the sprite they describe.
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
    private final SpriteFieldPanel field = new SpriteFieldPanel(sprites);
    private final Timer refreshTimer;

    private final JLabel machine = new JLabel();

    private final int[] bytes = new int[SPRITES * 4];
    private final int[] left = new int[SPRITES];
    private final int[] top = new int[SPRITES];

    private NESPalette palette;
    private int height = 8;

    public OAMViewerFrame(final Component parent, final PPU ppu, final NESPalette palette) {
        this.ppu = ppu;
        this.palette = palette;
        this.refreshTimer = new Timer(REFRESH_MILLIS, e -> tick());

        for (var i = 0; i < SPRITES; i++) {
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
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setDefaultRenderer(BufferedImage.class, new SpriteRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.getSelectionModel().addListSelectionListener(
                e -> field.setSelected(table.getSelectedRow()));

        for (var column = 0; column < model.getColumnCount(); column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(model.widthOf(column));
        }

        var listing = new JScrollPane(table);
        listing.setPreferredSize(new java.awt.Dimension(440, PPU.SCREEN_HEIGHT * 2));

        var body = new JPanel(new BorderLayout(8, 0));
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
        body.add(listing, BorderLayout.CENTER);
        body.add(field, BorderLayout.EAST);

        add(machine, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);

        pack();
        setLocationRelativeTo(parent);
    }

    /**
     * Draws the sprites in {@code palette} from now on, following Settings &gt; Palette...
     */
    public void setPalette(final NESPalette palette) {
        this.palette = palette;
        refresh();
    }

    @Override
    public void dispose() {
        refreshTimer.stop();
        super.dispose();
    }

    /**
     * One sweep: the 256 bytes, then the 64 tiles they name.
     * <p>
     * Read in one pass and drawn from that copy rather than read again per column, so that every
     * row of the table describes one moment of the machine rather than four.
     */
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
        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = ppu.peekOAM(i);
        }

        height = ppu.getSpriteHeight();

        for (var sprite = 0; sprite < SPRITES; sprite++) {
            left[sprite] = bytes[sprite * 4 + 3];
            top[sprite] = bytes[sprite * 4];

            render(sprite);
        }

        model.fireTableRowsUpdated(0, SPRITES - 1);
        field.setPositions(left, top, height);

        machine.setText(String.format(
                "8x%d sprites   pattern %s   %d on screen", height, patterns(), onScreen()));
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

        java.util.Arrays.fill(pixels, 0);

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
     * How many of the sixty four could be seen at all. Y is compared against the line being
     * evaluated, so anything from 239 up is parked rather than drawn -- which is how a game turns a
     * sprite off, and the number worth having at a glance.
     */
    private int onScreen() {
        var count = 0;

        for (var sprite = 0; sprite < SPRITES; sprite++) {
            if (top[sprite] < PPU.SCREEN_HEIGHT - 1) {
                count++;
            }
        }

        return count;
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
                "#", "", "X", "Y", "Tile", "Palette", "Priority", "Flip", "OAM",
        };

        private final int[] widths = {
                28, 40, 40, 40, 48, 56, 64, 44, 48,
        };

        int widthOf(final int column) {
            return widths[column];
        }

        @Override
        public int getRowCount() {
            return SPRITES;
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
            return column == 1 ? BufferedImage.class : String.class;
        }

        @Override
        public Object getValueAt(final int row, final int column) {
            var attributes = bytes[row * 4 + 2];

            return switch (column) {
                case 0 -> Integer.toString(row);
                case 1 -> sprites[row];
                case 2 -> String.format("$%02X", bytes[row * 4 + 3]);
                case 3 -> String.format("$%02X", bytes[row * 4]);
                case 4 -> String.format("$%02X", bytes[row * 4 + 1]);
                case 5 -> Integer.toString(4 + (attributes & 3));
                case 6 -> (attributes & 0x20) != 0 ? "behind" : "front";
                case 7 -> flip(attributes);
                default -> String.format("$%02X", row * 4);
            };
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
