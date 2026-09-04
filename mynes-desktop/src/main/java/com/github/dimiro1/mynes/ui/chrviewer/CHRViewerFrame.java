package com.github.dimiro1.mynes.ui.chrviewer;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.PPU;
import com.github.dimiro1.mynes.palette.NESPalette;
import com.github.dimiro1.mynes.ui.PauseBox;
import com.github.dimiro1.mynes.ui.PauseControl;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.Arrays;

/**
 * A window over character memory: every tile of one 4KB bank on the left, the selected tile
 * blown up on the right.
 * <p>
 * The tiles can be coloured with any of the eight palettes the game is running with, read live
 * out of the PPU's palette RAM, and a timer re-reads both palette and pattern data a few times a
 * second -- so CHR RAM games redraw as they write, and palette changes show up as they happen.
 * <p>
 * Everything here runs on the event dispatch thread and reads emulator memory while the emulation
 * thread runs. Deliberately unsynchronised: reading an array element cannot tear, so the worst
 * case is a tile or a colour a frame out of date.
 */
public class CHRViewerFrame extends JFrame {
    /**
     * How often the viewer re-reads character memory and palette RAM. Fast enough to feel live,
     * slow enough to cost nothing: a full sweep is 4KB of reads and a handful of compares.
     */
    private static final int REFRESH_MILLIS = 250;

    /**
     * A CHR RAM cart carries one 8KB chip, the whole of the PPU's pattern address space.
     */
    private static final int CHR_RAM_SIZE = 0x2000;

    private final Component parent;
    private final Cart cart;
    private final PPU ppu;

    private final JLabel selectedLabel = new JLabel();
    private final TilesViewerPanel tilesViewer;
    private final TileComponent selectedTile;
    private final PauseBox pause;
    private final Timer refreshTimer;

    private int baseAddress = 0;
    private int selectedTileNumber = 0;

    /**
     * Palette RAM offset of the palette tiles are drawn with -- $00, $04 ... $1C -- or -1 for
     * the fixed default colours.
     */
    private int paletteBase = -1;
    private int[] paletteColours = TileComponent.DEFAULT_PALETTE.clone();

    /**
     * The master palette the six bit entries in palette RAM are read through, so the tiles come
     * out in the same colours the picture does. Follows the choice in Settings &gt; Palette...
     * through {@link #setPalette}.
     */
    private NESPalette palette;

    public CHRViewerFrame(
            final Component parent,
            final Cart cart,
            final PPU ppu,
            final NESPalette palette,
            final PauseControl pauseControl) {
        super();
        this.parent = parent;
        this.cart = cart;
        this.ppu = ppu;
        this.palette = palette;

        this.tilesViewer = new TilesViewerPanel(cart);
        this.selectedTile = new TileComponent(
                selectedTileNumber, baseAddress, 272, 272, cart.mapper());
        this.pause = new PauseBox(pauseControl);
        this.refreshTimer = new Timer(REFRESH_MILLIS, e -> refresh());

        init();

        refreshTimer.start();
    }

    private void init() {
        setTitle("CHR Viewer");
        setResizable(false);
        setLayout(new BorderLayout());

        tilesViewer.addChangeListener(tile -> {
            selectedTileNumber = tile.getTileNumber();
            selectedTile.setTileNumber(selectedTileNumber);
            selectedTile.setBaseAddress(baseAddress);
            updateSelectedLabel();
        });

        var tiles = new JPanel(new MigLayout("insets 8"));
        tiles.add(tilesViewer, "top");
        tiles.add(selectedTile, "top");

        var options = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        options.add(new JLabel("Bank:"));
        options.add(getChrBankJComboBox());
        options.add(new JLabel("Palette:"));
        options.add(getPaletteJComboBox());
        options.add(getMode8x16JCheckBox());

        // Pause at the far end, away from the controls that only change what is drawn: this one
        // changes the machine, which is a different kind of thing to be clicking.
        var controls = new JPanel(new BorderLayout());
        controls.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 12));
        controls.add(options, BorderLayout.WEST);
        controls.add(pause, BorderLayout.EAST);

        selectedLabel.setBorder(BorderFactory.createEmptyBorder(8, 12, 0, 12));
        updateSelectedLabel();

        add(selectedLabel, BorderLayout.NORTH);
        add(tiles, BorderLayout.CENTER);
        add(controls, BorderLayout.SOUTH);
        pack();
        pause.installIn(getRootPane());
        setLocationRelativeTo(parent);
    }

    /**
     * Stops the refresh timer along with the window; without this the timer would keep the
     * viewer reading a dead machine's memory forever.
     */
    @Override
    public void dispose() {
        refreshTimer.stop();
        super.dispose();
    }

    /**
     * One tick of the refresh timer: pick up palette changes, then re-read every tile. Tiles
     * only repaint when their bytes actually changed, so a ROM bank settles to no work at all.
     */
    private void refresh() {
        if (!isShowing()) {
            return;
        }

        updatePaletteColours();
        tilesViewer.refreshTiles();
        selectedTile.refresh();
        pause.refresh();
    }

    /**
     * Draws the tiles in {@code palette} from now on. Called on the event dispatch thread when the
     * choice in Settings &gt; Palette... changes, so the viewer tracks the picture rather than
     * keeping whatever was chosen when it opened.
     */
    public void setPalette(final NESPalette palette) {
        this.palette = palette;
        updatePaletteColours();
    }

    private void updatePaletteColours() {
        var colours = resolvePaletteColours();

        if (!Arrays.equals(colours, paletteColours)) {
            paletteColours = colours;
            tilesViewer.setPalette(colours);
            selectedTile.setPalette(colours);
        }
    }

    /**
     * Turns the chosen palette into four colours on screen. Entry 0 of every palette falls
     * through to the backdrop at $3F00, which is what the PPU itself draws there.
     */
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

    private void updateSelectedLabel() {
        selectedLabel.setText(String.format(
                "Tile $%02X ($%04X)", selectedTileNumber, baseAddress + selectedTileNumber * 16));
    }

    private @NotNull JComboBox<CHRBank> getChrBankJComboBox() {
        var bankSelector = new JComboBox<CHRBank>();

        // A cart with no CHR ROM has CHR RAM instead, which is always the same 8KB.
        var chrSize = cart.chrROM().length == 0 ? CHR_RAM_SIZE : cart.chrROM().length;
        for (int i = 0; i < chrSize / 0x1000; i++) {
            bankSelector.addItem(new CHRBank(i * 0x1000));
        }

        bankSelector.addActionListener(e -> {
            var selected = (CHRBank) bankSelector.getSelectedItem();
            if (selected != null) {
                baseAddress = selected.address;

                tilesViewer.setBaseAddress(baseAddress);
                selectedTile.setBaseAddress(baseAddress);
                updateSelectedLabel();
            }
        });
        return bankSelector;
    }

    private @NotNull JComboBox<PaletteChoice> getPaletteJComboBox() {
        var paletteSelector = new JComboBox<PaletteChoice>();
        paletteSelector.addItem(new PaletteChoice("Default", -1));

        for (var i = 0; i < 4; i++) {
            paletteSelector.addItem(new PaletteChoice("Background " + i, i * 4));
        }

        for (var i = 0; i < 4; i++) {
            paletteSelector.addItem(new PaletteChoice("Sprite " + i, 0x10 + i * 4));
        }

        paletteSelector.addActionListener(e -> {
            var selected = (PaletteChoice) paletteSelector.getSelectedItem();
            if (selected != null) {
                paletteBase = selected.base;
                updatePaletteColours();
            }
        });
        return paletteSelector;
    }

    private @NotNull JCheckBox getMode8x16JCheckBox() {
        var mode8x16 = new JCheckBox("8x16 sprites");
        mode8x16.setSelected(tilesViewer.getMode() == TilesViewerPanel.Mode.MODE_8X16);
        mode8x16.addActionListener(e -> {
            tilesViewer.setMode(mode8x16.isSelected()
                    ? TilesViewerPanel.Mode.MODE_8X16
                    : TilesViewerPanel.Mode.MODE_8X8);
            tilesViewer.repaint();
        });
        return mode8x16;
    }

    private record CHRBank(int address) {
        @Override
        public @NotNull String toString() {
            return String.format("$%04X", address);
        }
    }

    private record PaletteChoice(String label, int base) {
        @Override
        public @NotNull String toString() {
            return label;
        }
    }
}
