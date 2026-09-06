package com.github.dimiro1.mynes.ui.events;

import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.Nullable;

import javax.swing.JCheckBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Font;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Everything the machine did to its hardware in one frame, put where in the frame it did it.
 * <p>
 * Every other instrument here answers <em>what</em>: which bank, which sprite, which byte. This one
 * answers <b>when</b>, and it is the only thing in the program that can. A write to $2005 is a
 * scroll; a write to $2005 a hundred and fourteen lines down is a status bar, and the difference
 * between those two is invisible in memory, in the picture and in a disassembly alike -- by the
 * time the frame is over there is nothing left anywhere to say which line it landed on.
 * <p>
 * So the marks are the answer to most raster questions at a glance. A split on the wrong line, a
 * bank switched in the middle of the picture instead of in the blanking, an MMC3 interrupt firing
 * three lines late, an NMI a game switched off half way down: all of them are a mark in the wrong
 * place, and none of them is anything at all in any other view.
 * <p>
 * <b>Writes always, reads only when asked.</b> That is not a filter but a decision made at the
 * machine: recording reads means putting a hook on the line every instruction fetch comes past,
 * so it is the one thing here a game can feel. What it buys is the polls -- $2002 while a program
 * waits for vblank, $4016 while it reads the pad -- which are worth seeing exactly when the
 * question is why a game is waiting.
 * <p>
 * The five ticks beside it are filters and cost nothing: they hide marks that have already been
 * recorded. Sound is the one worth switching off, since a music driver writes twenty or thirty
 * registers a frame and none of them is where a raster bug lives.
 */
public final class EventsPanel extends JPanel {
    private static final String NOTHING = "—";

    /**
     * The filters, and which kinds each one covers.
     * <p>
     * Five rather than seven, because a filter answers a question: what is the picture doing, what
     * is the sound doing, what is the cartridge doing, and -- separately, because they are not the
     * same news -- where the frame was interrupted by each of the two things that can. A read sits
     * with its own writes rather than under a filter of its own, since whether to have reads at all
     * is settled before any of them is recorded.
     * <p>
     * The ticks are coloured, which makes them the legend as well: there is nowhere else the marks
     * are named, and a key drawn separately would be a sixth thing saying what the four already
     * say.
     */
    private enum Filter {
        PPU("Picture", Debugger.EventKind.PPU_WRITE, Debugger.EventKind.PPU_READ),
        AUDIO("Sound", Debugger.EventKind.AUDIO_WRITE, Debugger.EventKind.AUDIO_READ),
        CARTRIDGE("Cartridge", Debugger.EventKind.CARTRIDGE_WRITE),
        NMI("NMI", Debugger.EventKind.NMI),
        IRQ("IRQ", Debugger.EventKind.IRQ);

        private final String label;
        private final Debugger.EventKind[] kinds;

        Filter(final String label, final Debugger.EventKind... kinds) {
            this.label = label;
            this.kinds = kinds;
        }

        boolean covers(final Debugger.EventKind kind) {
            for (var mine : kinds) {
                if (mine == kind) {
                    return true;
                }
            }

            return false;
        }
    }

    private final RasterView raster;
    private final JCheckBox reads = new JCheckBox("Record reads");
    private final Map<Filter, JCheckBox> filters = new EnumMap<>(Filter.class);

    private final JLabel summary = value();
    private final JLabel hovered = value();

    /**
     * The last frame handed over, kept so that a filter switched on a machine somebody has
     * stopped in order to look at redraws from it. Without it the raster would empty until the
     * next readout, which on a stopped machine is never -- the trap the Sound tab's Split fell
     * into.
     */
    private @Nullable Readout last;

    /**
     * @param region what shape a frame is on this console, which is the only thing about the
     *               machine this needs: 262 scanlines or 312.
     * @param onReadsChanged told when the reads tick moves, so that the hook can be put on the bus
     *                       or taken off it. Nothing here can reach the machine itself.
     */
    public EventsPanel(final Region region, final Consumer<Boolean> onReadsChanged) {
        raster = new RasterView(region, this::describe);

        setLayout(new MigLayout("insets 10, gapy 4, wrap 1", "[]", ""));

        add(header(), "gapbottom 2");
        add(controls(onReadsChanged), "gapbottom 4");
        add(raster);
        add(hovered, "gaptop 6");
        add(summary);

        reads.setToolTipText(
                "Also record what the game reads. The hook this needs sees every instruction the"
                        + " processor fetches, so it is the one switch here the game can feel.");

        clear();
    }

    /**
     * The frame that has just finished.
     */
    public void show(final Readout readout) {
        last = readout;
        redraw();
    }

    // ================================================================================== internals

    private static JPanel header() {
        var row = new JPanel(new MigLayout("insets 0, gapx 14", "", ""));
        var note = new JLabel(
                "a mark wherever the machine was touched, at the line and dot it was");

        note.setForeground(Theme.muted());

        row.add(Theme.heading("One frame"));
        row.add(note);

        return row;
    }

    private JPanel controls(final Consumer<Boolean> onReadsChanged) {
        var row = new JPanel(new MigLayout("insets 0, gapx 12", "", ""));

        for (var filter : Filter.values()) {
            var box = new JCheckBox(filter.label, true);

            box.setForeground(EventColours.of(filter.kinds[0]));
            box.addActionListener(e -> redraw());

            filters.put(filter, box);
            row.add(box);
        }

        reads.addActionListener(e -> onReadsChanged.accept(reads.isSelected()));
        row.add(reads, "gapleft 24");

        return row;
    }

    private void redraw() {
        if (last == null) {
            clear();
            return;
        }

        var frame = last.events();
        var showing = new ArrayList<Debugger.Event>(frame.events().size());

        for (var event : frame.events()) {
            if (isShown(event.kind())) {
                showing.add(event);
            }
        }

        raster.show(showing);
        summary.setText(describe(frame, showing.size()));
    }

    private boolean isShown(final Debugger.EventKind kind) {
        for (var filter : Filter.values()) {
            if (filter.covers(kind)) {
                return filters.get(filter).isSelected();
            }
        }

        return true;
    }

    /**
     * What the frame was made of, which is the half of this a screenshot keeps: hovering says what
     * one mark is and a picture of the window cannot show that.
     */
    private static String describe(final Readout.Events frame, final int showing) {
        if (frame.events().isEmpty()) {
            return frame.dropped() > 0
                    ? "nothing shown, and " + frame.dropped() + " more than the log holds"
                    : "nothing touched this frame";
        }

        var parts = new ArrayList<String>();

        for (var kind : Debugger.EventKind.values()) {
            var count = frame.count(kind);

            if (count > 0) {
                parts.add(count + " " + kind.label());
            }
        }

        var text = showing + " of " + frame.events().size() + " shown  ·  "
                + String.join("  ·  ", parts);

        return frame.dropped() > 0
                ? text + "  ·  " + frame.dropped() + " more than the log holds"
                : text;
    }

    /**
     * One mark, named. Null when the pointer is not over one, which is most of the time and is why
     * the line keeps its height rather than disappearing.
     */
    private void describe(final @Nullable Debugger.Event event) {
        if (event == null) {
            hovered.setText(" ");
            return;
        }

        var where = String.format("line %d dot %d", event.scanline(), event.dot());
        var what = event.kind().label() + String.format(" $%04X", event.address());
        var value = event.value() < 0 ? "" : String.format(" = $%02X", event.value());

        hovered.setText(
                where + "  ·  " + what + value + String.format("  ·  pc $%04X", event.pc()));
    }

    private void clear() {
        raster.show(List.of());
        summary.setText(NOTHING);
        hovered.setText(" ");
    }

    private static JLabel value() {
        var label = new JLabel(" ");

        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        return label;
    }
}
