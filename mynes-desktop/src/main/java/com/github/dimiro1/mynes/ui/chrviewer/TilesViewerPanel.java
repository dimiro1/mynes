package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.Cart;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The currently mapped 4 KB pattern table, with either 8x8 tiles or real 8x16 pairs. */
public final class TilesViewerPanel extends JComponent {
    private static final int COLUMNS = 16;
    private static final int CELL = 17;
    private static final Color SELECTION = new Color(255, 222, 54);

    public enum Mode { MODE_8X8, MODE_8X16 }

    public interface ChangeListener {
        void selectedTileChanged(int tileNumber);
    }

    private final Cart cart;
    private final List<ChangeListener> listeners = new ArrayList<>();
    private final int[][] bytes = new int[256][16];
    private final BufferedImage[] images = new BufferedImage[256];
    private int[] palette = TileComponent.DEFAULT_PALETTE.clone();
    private Mode mode = Mode.MODE_8X8;
    private int baseAddress;
    private int selectedTile;
    private int zoom = 2;

    public TilesViewerPanel(final Cart cart) {
        this(cart, 0);
    }

    public TilesViewerPanel(final Cart cart, final int baseAddress) {
        this.cart = cart;
        this.baseAddress = baseAddress;
        setFocusable(true);
        for (var i = 0; i < images.length; i++) {
            images[i] = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        }
        refreshTiles();
        sizeChanged();

        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(final MouseEvent e) {
                requestFocusInWindow();
                var x = e.getX() / (CELL * zoom);
                var y = e.getY() / (CELL * zoom * (mode == Mode.MODE_8X16 ? 2 : 1));
                if (x >= 0 && x < COLUMNS && y >= 0 && y < rows()) {
                    select(mode == Mode.MODE_8X16 ? (y * COLUMNS + x) * 2 : y * COLUMNS + x);
                }
            }
        });
        bind("LEFT", -1, 0);
        bind("RIGHT", 1, 0);
        bind("UP", 0, -1);
        bind("DOWN", 0, 1);
    }

    private void bind(final String key, final int dx, final int dy) {
        getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), key);
        getActionMap().put(key, new AbstractAction() {
            @Override
            public void actionPerformed(final ActionEvent e) {
                var index = mode == Mode.MODE_8X16 ? selectedTile / 2 : selectedTile;
                var x = Math.max(0, Math.min(COLUMNS - 1, index % COLUMNS + dx));
                var y = Math.max(0, Math.min(rows() - 1, index / COLUMNS + dy));
                select((y * COLUMNS + x) * (mode == Mode.MODE_8X16 ? 2 : 1));
            }
        });
    }

    private int rows() {
        return mode == Mode.MODE_8X16 ? 8 : 16;
    }

    private void sizeChanged() {
        setPreferredSize(new Dimension(COLUMNS * CELL * zoom, 16 * CELL * zoom));
        revalidate();
        repaint();
    }

    public void addChangeListener(final ChangeListener listener) {
        listeners.add(listener);
    }

    public int selectedTile() {
        return selectedTile;
    }

    public void setZoom(final int zoom) {
        if (zoom < 1 || zoom > 3) {
            throw new IllegalArgumentException("Zoom must be from 1 to 3");
        }
        this.zoom = zoom;
        sizeChanged();
    }

    public void setBaseAddress(final int baseAddress) {
        if (this.baseAddress != baseAddress) {
            this.baseAddress = baseAddress;
            refreshTiles();
        }
    }

    public void refreshTiles() {
        var changed = false;
        for (var tile = 0; tile < 256; tile++) {
            var tileChanged = false;
            for (var i = 0; i < 16; i++) {
                var value = cart.mapper().charRead(baseAddress + tile * 16 + i);
                if (bytes[tile][i] != value) {
                    bytes[tile][i] = value;
                    tileChanged = true;
                }
            }
            if (tileChanged) {
                render(tile);
                changed = true;
            }
        }
        if (changed) {
            repaint();
        }
    }

    public void setPalette(final int[] colours) {
        if (!Arrays.equals(palette, colours)) {
            palette = colours.clone();
            for (var tile = 0; tile < 256; tile++) {
                render(tile);
            }
            repaint();
        }
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(final Mode mode) {
        if (this.mode != mode) {
            this.mode = mode;
            select(mode == Mode.MODE_8X16 ? selectedTile & 0xFE : selectedTile);
            repaint();
        }
    }

    private void select(final int tile) {
        if (selectedTile != tile) {
            selectedTile = tile;
            repaint();
        }
        listeners.forEach(listener -> listener.selectedTileChanged(selectedTile));
    }

    private void render(final int tile) {
        for (var y = 0; y < 8; y++) {
            for (var x = 0; x < 8; x++) {
                var shift = 7 - x;
                var index = ((bytes[tile][y + 8] >> shift) & 1) * 2
                        + ((bytes[tile][y] >> shift) & 1);
                images[tile].setRGB(x, y, palette[index]);
            }
        }
    }

    @Override
    protected void paintComponent(final Graphics g) {
        super.paintComponent(g);
        var g2 = (Graphics2D) g.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            var cell = CELL * zoom;
            var tall = mode == Mode.MODE_8X16;
            for (var row = 0; row < rows(); row++) {
                for (var column = 0; column < COLUMNS; column++) {
                    var tile = (row * COLUMNS + column) * (tall ? 2 : 1);
                    var x = column * cell;
                    var y = row * cell * (tall ? 2 : 1);
                    g2.drawImage(images[tile], x, y, 16 * zoom, 16 * zoom, null);
                    if (tall) {
                        g2.drawImage(images[tile + 1], x, y + 16 * zoom,
                                16 * zoom, 16 * zoom, null);
                    }
                    if (tile == selectedTile) {
                        g2.setColor(SELECTION);
                        g2.drawRect(x, y, cell - 2, cell * (tall ? 2 : 1) - 2);
                    }
                }
            }
        } finally {
            g2.dispose();
        }
    }
}
