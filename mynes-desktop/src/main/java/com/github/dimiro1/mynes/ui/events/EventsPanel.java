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
     * The separator between one fact and the next, as the dashboard spells it.
     */
    private static final String GAP = "  ·  ";

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

        // The panel is as wide as its raster, and the two lines whose text changes four times a
        // second get no vote in it. Without the cap the summary was the widest thing here, so
        // every readout resized the panel -- and a panel centred in its tab that resizes is a tab
        // whose whole contents jump sideways while somebody is trying to read them. Capping is
        // insurance rather than the fix: both lines are written to fit, see describe below.
        var width = "wmax " + raster.getPreferredSize().width;

        add(header(), "gapbottom 2");
        add(controls(onReadsChanged), "gapbottom 4");
        add(raster);
        add(hovered, "gaptop 6, " + width);
        add(summary, width);

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
        summary.setText(describe(frame, showing.size(), reads.isSelected()));
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
     * <p>
     * <b>Written to fit under the raster, and written to keep its shape.</b> It said things like
     * "24 audio read · 11 audio write" and dropped whichever kinds were absent, which made it both
     * the widest thing in the panel and a different width every readout -- so the panel resized
     * four times a second and everything in the tab jumped sideways. All five groups are always
     * named now, in short form, so what moves is a digit rather than a column; and the worst case
     * fits, because the counts sum to at most what the log holds and so only one of them can ever
     * be four digits.
     * <p>
     * The read halves appear only while reads are being recorded. That is a change of shape, but
     * it happens when somebody clicks the tick rather than on its own -- the same rule the Sound
     * tab's Split keeps -- and a pair of zeroes that can never be anything else is worse than a
     * line that answers to one click.
     */
    private static String describe(
            final Readout.Events frame, final int showing, final boolean reads) {

        if (frame.events().isEmpty() && frame.dropped() == 0) {
            // Which covers both "nothing is recording" and "a frame in which nothing happened",
            // and they are the same picture: on a running game the interrupt alone is an event, so
            // an empty frame means nobody is watching.
            return "nothing recorded";
        }

        var parts = new ArrayList<String>();

        parts.add(showing + " of " + frame.events().size() + " shown");
        parts.add(group("PPU", frame, reads,
                Debugger.EventKind.PPU_READ, Debugger.EventKind.PPU_WRITE));
        parts.add(group("audio", frame, reads,
                Debugger.EventKind.AUDIO_READ, Debugger.EventKind.AUDIO_WRITE));
        parts.add(group("mapper", frame, reads, null, Debugger.EventKind.CARTRIDGE_WRITE));
        parts.add("NMI " + frame.count(Debugger.EventKind.NMI));
        parts.add("IRQ " + frame.count(Debugger.EventKind.IRQ));

        if (frame.dropped() > 0) {
            parts.add(frame.dropped() + " lost");
        }

        return String.join(GAP, parts);
    }

    /**
     * One group of the summary: its reads and its writes, or only its writes while reads are not
     * being recorded, in which case the {@code r}/{@code w} suffixes say nothing worth the room.
     */
    private static String group(
            final String name,
            final Readout.Events frame,
            final boolean reads,
            final @Nullable Debugger.EventKind read,
            final Debugger.EventKind write) {

        if (!reads) {
            return name + " " + frame.count(write);
        }

        // The cartridge has no read of its own -- a read above $8000 is the program being fetched
        // -- but it still says w beside the others, since a column that dropped its unit would read
        // as a different kind of number.
        if (read == null) {
            return name + " " + frame.count(write) + "w";
        }

        return name + " " + frame.count(read) + "r " + frame.count(write) + "w";
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
                where + GAP + what + value + String.format(GAP + "pc $%04X", event.pc()));
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
