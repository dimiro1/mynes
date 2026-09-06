package com.github.dimiro1.mynes.ui.cartridge;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import net.miginfocom.swing.MigLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;

/**
 * What the cartridge is showing the console, window by window.
 * <p>
 * A bank switch is the one thing a game does that changes what the processor is <em>looking at</em>
 * rather than what it holds, and it leaves no trace anywhere: the disassembly at $8000 is simply
 * different code a moment later, and a tile that was a cloud is a brick. So a listing that has gone
 * to nonsense, a screen that has gone to garbage, and a game that hangs after a level change are
 * all the same question -- which bank is in that window -- and this is where it is answered.
 * <p>
 * Normalised to 8KB of program ROM and 1KB of character memory, which is the smallest window any
 * board here switches. A board with a coarser one shows consecutive numbers, which is what it is
 * really doing: an NROM's four windows are 0 1 2 3 forever, and a UxROM's first pair moves together.
 * <p>
 * The three rows under the banks are the parts of a board that are not banks at all and are just as
 * invisible. Mirroring decides which pairs of nametables are the same memory. The RAM's two flags
 * are why a hex view of $6000 is full of zeroes on a battery game -- MMC1 and MMC3 switch the chip
 * off around anything risky. And the scanline counter is behind most of what looks like a raster
 * bug on an MMC3 game.
 */
public final class CartridgePanel extends JPanel {
    /**
     * Where the four program windows start, which is what the numbers under them are about.
     */
    private static final String[] PRG_WINDOWS = {"$8000", "$A000", "$C000", "$E000"};

    private static final String NOTHING = "—";

    private final JLabel[] prg = new JLabel[Mapper.Banks.PRG_WINDOWS];
    private final JLabel[] chr = new JLabel[Mapper.Banks.CHR_WINDOWS];
    private final JLabel mirroring = value();
    private final JLabel ram = value();
    private final JLabel irq = value();

    public CartridgePanel(final Cart cart) {
        setLayout(new MigLayout("insets 10, gapx 10, gapy 3", "[92][]", ""));

        // A local rather than a field: what a board is never changes, so nothing ever has to find
        // this label again.
        add(new JLabel(describe(cart)), "span, wrap, gapbottom 10");

        add(Theme.heading("Program"), "span, wrap");
        add(new JLabel(), "");
        add(row(PRG_WINDOWS, prg), "wrap");

        add(Theme.heading("Character"), "span, gaptop 10, wrap");
        add(new JLabel(), "");
        add(row(chrWindows(), chr), "wrap");

        add(Theme.heading("Board"), "span, gaptop 14, wrap");
        add(new JLabel("mirroring"), "");
        add(mirroring, "wrap");
        add(new JLabel("cartridge RAM"), "");
        add(ram, "wrap");
        add(new JLabel("scanline IRQ"), "");
        add(irq, "wrap");
    }

    /**
     * The machine as it stood when a frame finished.
     */
    public void show(final Readout readout) {
        var state = readout.board();

        for (var window = 0; window < prg.length; window++) {
            prg[window].setText(Integer.toString(state.banks().prg()[window]));
        }

        for (var window = 0; window < chr.length; window++) {
            chr[window].setText(Integer.toString(state.banks().chr()[window]));
        }

        mirroring.setText(name(state.mirroring()));
        ram.setText(describeRAM(state));
        irq.setText(describeIRQ(state.irq()));
    }

    /**
     * The board itself, which never changes: what it is, how much is on it, and whether the
     * character memory is a ROM to be banked or a RAM to be written.
     */
    private static String describe(final Cart cart) {
        return String.format(
                "mapper %d  ·  %dK program  ·  %s  ·  %s",
                cart.mapperNumber(),
                cart.prgROM().length / 1024,
                cart.chrROM().length == 0
                        ? "8K character RAM"
                        : cart.chrROM().length / 1024 + "K character ROM",
                cart.format());
    }

    private static String describeRAM(final Readout.Board state) {
        if (state.ramBytes() == 0) {
            return "none on the board";
        }

        var how = !state.ramEnabled() ? "switched off"
                : state.ramWritable() ? "readable and writable" : "write protected";

        return state.ramBytes() / 1024 + "K  ·  " + how;
    }

    private static String describeIRQ(final Mapper.ScanlineIRQ counter) {
        if (counter == null) {
            return NOTHING;
        }

        return String.format(
                "counter %d  ·  reloads at %d  ·  %s",
                counter.counter(),
                counter.latch(),
                counter.enabled() ? "enabled" : "disabled");
    }

    /**
     * The eight character windows, named by where they start -- which is how a game's own tables
     * name them and how the CHR viewer's bank chooser does.
     */
    private static String[] chrWindows() {
        var names = new String[Mapper.Banks.CHR_WINDOWS];

        for (var window = 0; window < names.length; window++) {
            names[window] = String.format("$%04X", window * Mapper.Banks.CHR_WINDOW);
        }

        return names;
    }

    /**
     * One window per column, with where it starts over the bank in it. A row rather than a column
     * because what somebody is comparing is the windows against each other -- two the same means a
     * board that switches in coarser lumps than this table shows.
     */
    private static JPanel row(final String[] names, final JLabel[] into) {
        var panel = new JPanel(new MigLayout("insets 0, gapx 16, wrap " + names.length, "", ""));

        for (var name : names) {
            panel.add(Theme.heading(name));
        }

        for (var window = 0; window < into.length; window++) {
            into[window] = value();
            panel.add(into[window]);
        }

        return panel;
    }

    private static String name(final com.github.dimiro1.mynes.mappers.Mirroring mirroring) {
        return switch (mirroring) {
            case HORIZONTAL -> "horizontal";
            case VERTICAL -> "vertical";
            case FOUR_SCREEN -> "four screen";
            case ONE_SCREEN_LOW -> "one screen, low";
            case ONE_SCREEN_HIGH -> "one screen, high";
        };
    }

    private static JLabel value() {
        var label = new JLabel(NOTHING);

        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        return label;
    }
}
