package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.Sweep;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextArea;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.Arrays;
import java.util.function.IntConsumer;

/** The two PPU pattern tables and the CHR memory currently mapped into them. */
public final class CHRViewerPanel extends JPanel {
    private static final int REFRESH_MILLIS = 250;

    private final Cart cart;
    private final PPU ppu;
    private final TilesViewerPanel tilesViewer;
    private final SelectedTilePreview preview;
    private final JLabel selectedLabel = new JLabel();
    private final JLabel mappingLabel = new JLabel();
    private final JLabel pixelLabel = new JLabel(" ");
    private final JLabel useLabel = new JLabel();
    private final JButton findBackground = new JButton("Show in Nametables");
    private final JButton findSprites = new JButton("Show in Sprites");
    private final JTextArea bytes = new JTextArea(5, 35);

    private int baseAddress;
    private int selectedTileNumber;
    private int paletteBase = -1;
    private int[] paletteColours = TileComponent.DEFAULT_PALETTE.clone();
    private NESPalette palette;
    private IntConsumer onBackground = address -> { };
    private IntConsumer onSprites = address -> { };

    public CHRViewerPanel(final Cart cart, final PPU ppu, final NESPalette palette) {
        this.cart = cart;
        this.ppu = ppu;
        this.palette = palette;
        tilesViewer = new TilesViewerPanel(cart);
        preview = new SelectedTilePreview(cart.mapper());
        init();
        refresh();
        Sweep.every(REFRESH_MILLIS, this, this::refresh);
    }

    private void init() {
        setLayout(new BorderLayout(0, 8));
        tilesViewer.addChangeListener(tile -> {
            selectedTileNumber = tile;
            describeSelection();
        });

        selectedLabel.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
        mappingLabel.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        mappingLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        var header = new JPanel(new GridLayout(2, 1));
        header.add(selectedLabel);
        header.add(mappingLabel);

        bytes.setEditable(false);
        bytes.setFocusable(false);
        bytes.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        bytes.setOpaque(false);
        bytes.setAlignmentX(Component.LEFT_ALIGNMENT);
        bytes.setMaximumSize(new Dimension(Integer.MAX_VALUE, bytes.getPreferredSize().height));
        pixelLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        pixelLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        useLabel.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        useLabel.setAlignmentX(Component.LEFT_ALIGNMENT);

        preview.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(final MouseEvent e) {
                var x = preview.pixelX(e.getX());
                var y = preview.pixelY(e.getY());
                var pixel = preview.pixelAt(x, y);
                pixelLabel.setText(pixel < 0 ? " "
                        : String.format("Pixel (%d,%d): colour %d", x, y, pixel));
            }
        });
        preview.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseExited(final MouseEvent e) {
                pixelLabel.setText(" ");
            }
        });

        var detail = new JPanel(new BorderLayout(12, 0));
        // The control panel centres fixed-size instruments. Keep this width independent of
        // the selected tile's numbers and text, or the entire tab jumps left and right.
        detail.setPreferredSize(new Dimension(540, 272));
        detail.add(preview, BorderLayout.WEST);
        var info = new JPanel();
        info.setLayout(new BoxLayout(info, BoxLayout.Y_AXIS));
        info.add(pixelLabel);
        info.add(Box.createVerticalStrut(8));
        info.add(useLabel);
        info.add(Box.createVerticalStrut(12));
        info.add(bytes);
        info.add(Box.createVerticalStrut(10));
        var links = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        links.setAlignmentX(Component.LEFT_ALIGNMENT);
        findBackground.addActionListener(e -> onBackground.accept(address()));
        findSprites.addActionListener(e -> onSprites.accept(address()));
        links.add(findBackground);
        links.add(findSprites);
        links.setMaximumSize(new Dimension(Integer.MAX_VALUE, links.getPreferredSize().height));
        info.add(links);
        info.add(Box.createVerticalGlue());
        detail.add(info, BorderLayout.CENTER);

        var body = new JPanel(new BorderLayout(14, 0));
        body.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        body.add(tilesViewer, BorderLayout.WEST);
        body.add(detail, BorderLayout.CENTER);

        var options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        options.add(new JLabel("Pattern table:"));
        options.add(patternTableSelector());
        options.add(new JLabel("Palette:"));
        options.add(paletteSelector());
        options.add(modeSelector());
        options.add(new JLabel("Zoom:"));
        options.add(zoomSelector());
        var grid = new JCheckBox("Pixel grid");
        grid.addActionListener(e -> preview.setGrid(grid.isSelected()));
        options.add(grid);

        add(header, BorderLayout.NORTH);
        add(body, BorderLayout.CENTER);
        add(options, BorderLayout.SOUTH);
    }

    /** Re-read live CHR RAM, mapper windows, palette RAM and places using the selected tile. */
    public void refresh() {
        updatePaletteColours();
        tilesViewer.refreshTiles();
        describeSelection();
    }

    public void setPalette(final NESPalette palette) {
        this.palette = palette;
        updatePaletteColours();
    }

    /** Navigation is installed by the control panel, which owns the tabs. */
    public void setUseNavigation(final IntConsumer background, final IntConsumer sprites) {
        onBackground = background;
        onSprites = sprites;
    }

    private void updatePaletteColours() {
        var colours = resolvePaletteColours();
        if (!Arrays.equals(colours, paletteColours)) {
            paletteColours = colours;
            tilesViewer.setPalette(colours);
            preview.show(address(), tall(), colours);
        }
    }

    private int[] resolvePaletteColours() {
        if (paletteBase < 0) {
            return TileComponent.DEFAULT_PALETTE.clone();
        }
        var colours = new int[4];
        colours[0] = palette.colour(ppu.peekPalette(0));
        for (var i = 1; i < 4; i++) {
            colours[i] = palette.colour(ppu.peekPalette(paletteBase + i));
        }
        return colours;
    }

    private boolean tall() {
        return tilesViewer.getMode() == TilesViewerPanel.Mode.MODE_8X16;
    }

    private int address() {
        return baseAddress + selectedTileNumber * 16;
    }

    private void describeSelection() {
        var mapper = cart.mapper();
        var address = address();
        var offset = mapper.charOffset(address);
        selectedLabel.setText(String.format("Tile $%02X%s  PPU $%04X  CHR %s $%05X",
                selectedTileNumber,
                tall() ? String.format("–$%02X (8×16 pair)", selectedTileNumber + 1) : "",
                address, cart.chrROM().length == 0 ? "RAM" : "ROM", offset));

        var bank = mapper.banks().chr();
        var first = baseAddress / 0x400;
        mappingLabel.setText(String.format("$%04X–$%04X maps 1 KB CHR banks %02X %02X %02X %02X",
                baseAddress, baseAddress + 0x0FFF,
                bank[first], bank[first + 1], bank[first + 2], bank[first + 3]));

        var text = new StringBuilder("Pattern bytes\n");
        for (var half = 0; half < (tall() ? 2 : 1); half++) {
            text.append(tall() ? (half == 0 ? "Top low:    " : "Bottom low: ")
                    : "Low:        ");
            for (var i = 0; i < 8; i++) {
                text.append(String.format("%02X ", mapper.charRead(address + half * 16 + i)));
            }
            text.append('\n');
            text.append(tall() ? (half == 0 ? "Top high:   " : "Bottom high:")
                    : "High:       ");
            for (var i = 8; i < 16; i++) {
                text.append(String.format("%02X ", mapper.charRead(address + half * 16 + i)));
            }
            text.append('\n');
        }
        bytes.setText(text.toString());
        preview.show(address, tall(), paletteColours);
        var backgrounds = nametableUses();
        var sprites = spriteUses();
        useLabel.setText(String.format("Nametables: %d cells   OAM: %d sprites",
                backgrounds, sprites));
        findBackground.setEnabled(backgrounds > 0);
        findSprites.setEnabled(sprites > 0);
    }

    private int nametableUses() {
        if (baseAddress != ppu.getBackgroundPatternTable()) {
            return 0;
        }
        var count = 0;
        for (var table = 0; table < 4; table++) {
            var base = 0x2000 + table * 0x400;
            for (var i = 0; i < 960; i++) {
                if (ppu.peekVRAM(base + i) == selectedTileNumber) {
                    count++;
                }
            }
        }
        return count;
    }

    private int spriteUses() {
        var count = 0;
        for (var sprite = 0; sprite < 64; sprite++) {
            var tile = ppu.peekOAM(sprite * 4 + 1);
            if (ppu.getSpriteHeight() == 16) {
                var spriteAddress = ((tile & 1) << 12) | ((tile & 0xFE) << 4);
                if (address() == spriteAddress || (!tall() && address() == spriteAddress + 16)) {
                    count++;
                }
            } else if (!tall() && address() == ppu.getSpritePatternTable() + tile * 16) {
                count++;
            }
        }
        return count;
    }

    private JComboBox<PatternTable> patternTableSelector() {
        var selector = new JComboBox<PatternTable>();
        selector.addItem(new PatternTable(0));
        selector.addItem(new PatternTable(0x1000));
        selector.addActionListener(e -> {
            var table = (PatternTable) selector.getSelectedItem();
            if (table != null) {
                baseAddress = table.address();
                tilesViewer.setBaseAddress(baseAddress);
                describeSelection();
            }
        });
        return selector;
    }

    private JComboBox<PaletteChoice> paletteSelector() {
        var selector = new JComboBox<PaletteChoice>();
        selector.addItem(new PaletteChoice("Default", -1));
        for (var i = 0; i < 4; i++) {
            selector.addItem(new PaletteChoice("Background " + i, i * 4));
        }
        for (var i = 0; i < 4; i++) {
            selector.addItem(new PaletteChoice("Sprite " + i, 0x10 + i * 4));
        }
        selector.addActionListener(e -> {
            var choice = (PaletteChoice) selector.getSelectedItem();
            if (choice != null) {
                paletteBase = choice.base();
                updatePaletteColours();
            }
        });
        return selector;
    }

    private JCheckBox modeSelector() {
        var checkbox = new JCheckBox("8×16 sprites");
        checkbox.addActionListener(e -> {
            tilesViewer.setMode(checkbox.isSelected()
                    ? TilesViewerPanel.Mode.MODE_8X16 : TilesViewerPanel.Mode.MODE_8X8);
            describeSelection();
        });
        return checkbox;
    }

    private JComboBox<Integer> zoomSelector() {
        var selector = new JComboBox<>(new Integer[] {1, 2, 3});
        selector.setSelectedItem(2);
        selector.addActionListener(e -> tilesViewer.setZoom((Integer) selector.getSelectedItem()));
        return selector;
    }

    private record PatternTable(int address) {
        @Override
        public String toString() {
            return String.format("$%04X", address);
        }
    }

    private record PaletteChoice(String label, int base) {
        @Override
        public String toString() {
            return label;
        }
    }
}
