package com.github.dimiro1.mynes.ui.usage;

import com.github.dimiro1.mynes.debug.Usage;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JViewport;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * How much of its frame the game is using, and how much of the machine's memory.
 * <p>
 * Two gauges, one seam, and both of them answer a question no other view here can be asked.
 *
 * <h2>The processor</h2>
 *
 * A 6502 has no idle instruction and this one never runs fewer than one instruction per cycle it is
 * given, so "how busy is the CPU" has to be asked differently than it would be of a desktop. What a
 * game does with the time it has spare is turn over -- spinning on the VBlank flag, or on the byte
 * its NMI handler sets -- so the wait is the longest stretch of the frame in which it did nothing
 * new, and everything else is work. {@link Usage} is where the whole of that argument is, including
 * the three ways it can be wrong and which way each of them errs.
 * <p>
 * <b>This is the number that says whether a game has any room left.</b> The Pads tab next door says
 * when it has already run out -- a frame that went by with no $4016 read in it is a frame whose main
 * loop did not finish -- and by then it is too late to be surprised. A game sitting at ninety-five
 * per cent for a second before that happens is the same event with a warning attached, and the two
 * are drawn over the same 120 frames so they can be read against each other.
 * <p>
 * It is also the before and after for the Overclock hack in the column beside it, which is the one
 * thing in the program that changes this number without changing the game: extra scanlines are
 * extra cycles the program gets, so the same work comes out as a smaller share of a longer frame.
 *
 * <h2>The memory</h2>
 *
 * Written rather than not zero, since zero is a perfectly good thing for a variable to hold. Four
 * areas, split where the hardware splits them and no further, each drawn <b>across the whole width
 * of the tab</b> rather than reduced to a percentage -- because <em>where</em> in an area a game is
 * working is most of the answer, it is the half that averaging destroys, and at 128 cells to an area
 * every pixel of width is another byte of resolution. Which is also why this panel tracks the
 * viewport rather than sitting at its preferred size inside it.
 * <p>
 * <b>Start Again is not a nicety.</b> Nearly every cartridge clears all 2KB of work RAM before it
 * does anything else, so a map that began when the machine did is a map of that loop and of nothing
 * else -- Super Mario Bros. reads as 100% of both pages used from its first frame to its last.
 * Started again once a game is playing, the same map is 18% of the zero page and 13% of the work
 * RAM, which is the game.
 */
public final class UsagePanel extends JPanel implements Scrollable {
    private static final String NOTHING = "—";

    /**
     * What each area is called here. In the panel rather than on the enum, which is the same split
     * {@code CartridgePanel} makes for mirroring: what a range of the address map is, is the
     * console's business, and what to call it in a window is this window's.
     */
    private static final Map<Usage.Area, String> NAMES = new EnumMap<>(Map.of(
            Usage.Area.ZERO_PAGE, "zero page",
            Usage.Area.STACK, "stack",
            Usage.Area.WORK_RAM, "work RAM",
            Usage.Area.CARTRIDGE_RAM, "cartridge RAM"));

    private final JLabel load = big();
    private final LoadGraph graph = new LoadGraph(Usage.WINDOW);

    private final JLabel cycles = value();
    private final JLabel stolen = value();
    private final JLabel wait = value();
    private final JLabel writes = value();

    private final Map<Usage.Area, Area> areas = new EnumMap<>(Usage.Area.class);

    public UsagePanel(final Runnable forget) {
        setLayout(new MigLayout("insets 10, gapy 3, wrap 1, fillx", "[grow, fill]", ""));

        add(Theme.heading("Processor"));
        add(Theme.note("what a game has left over it spends turning over, so the wait is the longest"
                + " stretch of the frame in which it did nothing new"));

        add(top(), "gaptop 10");
        add(graph, "gaptop 12, h " + graph.getPreferredSize().height + "!");
        add(Theme.note("the last " + Usage.WINDOW + " frames, oldest at the left — the same window"
                + " the Pads tab draws its lag strip over"));

        add(memory(forget), "gaptop 24");
    }

    /**
     * The machine as it stood when a frame finished.
     */
    public void show(final Readout readout) {
        var usage = readout.usage();

        load.setText(usage.measured() ? percent(usage.load(), 0) : NOTHING);
        graph.show(usage.busy());

        cycles.setText(usage.measured() ? count(usage.cycles()) : NOTHING);
        stolen.setText(share(usage.stolen(), usage.cycles()));
        wait.setText(share(usage.longestWait(), usage.ran()));
        writes.setText(usage.measured() ? count(usage.writes()) : NOTHING);

        // Found by area rather than by position, because a board with no RAM chip on it has no
        // cartridge RAM entry at all -- which is a different answer from an empty one.
        var found = new EnumMap<Usage.Area, Usage.Segment>(Usage.Area.class);

        for (var segment : usage.memory()) {
            found.put(segment.area(), segment);
        }

        areas.forEach((area, row) -> row.show(found.get(area), readout.board().ramBytes()));
    }

    // ================================================================ tracking the room there is

    /**
     * The width of whatever this is scrolling inside, so that the maps get the whole of it.
     * <p>
     * A {@link JViewport} leaves a view narrower than itself at its preferred width unless the view
     * says otherwise, which is right for a listing and wrong for a gauge: every pixel of width here
     * is another byte of memory somebody can see. The height is not tracked, because a panel
     * stretched to the viewport would have its rows spread down a tall window rather than stacked at
     * the top of it.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(
            final Rectangle visible, final int orientation, final int direction) {

        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(
            final Rectangle visible, final int orientation, final int direction) {

        return orientation == SwingConstants.VERTICAL ? visible.height : visible.width;
    }

    // ================================================================================== internals

    /**
     * The share of the frame, and the four exact numbers it is made of, side by side above the
     * graph -- which spans the whole tab underneath both.
     */
    private JPanel top() {
        var row = new JPanel(new MigLayout("insets 0, gapx 40", "[][]", ""));
        var left = new JPanel(new MigLayout("insets 0, gapy 2, wrap 1", "[]", ""));

        left.add(load);
        left.add(Theme.note("of the last frame went on work"));

        var facts = new JPanel(new MigLayout("insets 0, gapx 16, gapy 3, wrap 2", "[140][]", ""));

        facts.add(new JLabel("cycles in the frame"));
        facts.add(cycles);
        facts.add(new JLabel("held off the bus"));
        facts.add(stolen);
        facts.add(new JLabel("longest wait"));
        facts.add(wait);
        facts.add(new JLabel("writes"));
        facts.add(writes);

        row.add(left, "aligny top");
        row.add(facts, "aligny top");

        return row;
    }

    /**
     * The four areas, each a line of numbers over a strip the width of the tab.
     * <p>
     * Two rows to an area rather than a strip squeezed in beside its label, because the strip is
     * the reading and the label is what it is called: giving the map every pixel there is and the
     * words a line of their own is what lets 8KB of cartridge RAM be looked at rather than
     * summarised.
     */
    private JPanel memory(final Runnable forget) {
        var panel = new JPanel(new MigLayout("insets 0, gapy 3, wrap 1, fillx", "[grow, fill]", ""));

        panel.add(Theme.heading("Memory"));
        panel.add(Theme.note("one bit per byte, set where the program has stored"));
        panel.add(legend(), "gaptop 4");

        for (var area : Usage.Area.values()) {
            var row = new Area(area);

            areas.put(area, row);
            row.addTo(panel);
        }

        var again = new JButton("Start Again");

        again.setToolTipText(
                "Forgets which bytes have ever been written. Worth doing once a game is playing: a"
                        + " cartridge clears all of work RAM before it starts, so a map taken from"
                        + " power on is a map of that.");
        again.addActionListener(e -> forget.run());

        // At the size it asks for rather than the width of the tab, which is what a column of
        // "grow, fill" would otherwise give a button sitting in it.
        panel.add(again, "gaptop 16, alignx left, w pref!");

        return panel;
    }

    /**
     * What the two colours in a column mean, beside them rather than in a sentence above them.
     * <p>
     * The stack is the whole of what makes the maps readable and it is not something anybody can be
     * expected to work out: two greys and a green in a strip 128 cells wide look like a picture
     * until somebody says which is which.
     */
    private static JPanel legend() {
        var panel = new JPanel(new MigLayout("insets 0, gapx 6", "", ""));

        panel.add(new Swatch(Theme.muted()));
        panel.add(Theme.note("ever written"), "gapright 18");
        panel.add(new Swatch(Theme.running()));
        panel.add(Theme.note("written in the last quarter second"));

        return panel;
    }

    /**
     * One colour of the legend, at the size of the text beside it.
     */
    private static final class Swatch extends JComponent {
        private final Color colour;

        Swatch(final Color colour) {
            this.colour = colour;

            setPreferredSize(new Dimension(10, 10));
            setMinimumSize(getPreferredSize());
        }

        @Override
        protected void paintComponent(final Graphics g) {
            g.setColor(colour);
            g.fillRect(0, 0, getWidth(), getHeight());
        }
    }

    /**
     * One area: what it is called and how much of it is in use, over the map itself.
     */
    private static final class Area {
        private final Usage.Area area;
        private final MemoryStrip strip = new MemoryStrip();
        private final JLabel used = value();

        Area(final Usage.Area area) {
            this.area = area;
        }

        void addTo(final JPanel parent) {
            var header = new JPanel(new MigLayout("insets 0, gapx 12", "[90][54][grow][]", ""));

            header.add(new JLabel(NAMES.get(area)));
            header.add(place());
            header.add(new JLabel());
            header.add(used);

            parent.add(header, "gaptop 14");
            parent.add(strip, "h " + strip.getPreferredSize().height + "!");
        }

        void show(final Usage.Segment segment, final int cartridgeRAM) {
            var absent = segment == null
                    && area == Usage.Area.CARTRIDGE_RAM
                    && cartridgeRAM == 0;

            // No strip at all where there is no chip, rather than an empty one: an empty strip is
            // what memory nobody has written to looks like, and this is memory that is not there.
            // The line above it stays, because the window is still in the address map.
            strip.setVisible(!absent);
            strip.show(segment);

            if (segment != null) {
                used.setText(String.format(
                        Locale.ROOT,
                        "%s of %s bytes  ·  %s",
                        count(segment.ever()),
                        count(area.size()),
                        percent(segment.everFraction(), 0)));
                return;
            }

            // Nothing in the reading for this area, which means one of two entirely different
            // things: there is no RAM on the board to write to, or nobody has been measuring.
            used.setText(absent ? "none on the board" : NOTHING);
        }

        /**
         * Where the area sits on the processor's bus, spelled the way every other address in the
         * program is.
         */
        private JLabel place() {
            var label = value();

            label.setText(String.format("$%04X", area.start()));
            label.setForeground(Theme.muted());

            return label;
        }
    }

    /**
     * A count of cycles or bytes, grouped, because most of these run to five and six figures and an
     * ungrouped one has to be counted with a finger.
     */
    private static String count(final long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    /**
     * A number and what share of something it is, or nothing at all where there is no something.
     */
    private static String share(final long value, final long of) {
        if (of <= 0) {
            return NOTHING;
        }

        return count(value) + "  ·  " + percent((double) value / of, 1);
    }

    private static String percent(final double fraction, final int places) {
        return String.format(Locale.ROOT, "%." + places + "f%%", fraction * 100);
    }

    private static JLabel big() {
        var label = new JLabel(NOTHING);

        label.setFont(new Font(Font.MONOSPACED, Font.BOLD, 30));

        return label;
    }

    private static JLabel value() {
        var label = new JLabel(NOTHING);

        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        return label;
    }
}
