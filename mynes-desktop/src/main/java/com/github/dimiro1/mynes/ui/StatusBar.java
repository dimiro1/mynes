package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The row along the bottom of the window: how fast the machine is running, what it is, and what it
 * is doing.
 * <p>
 * Everything on it is state with nowhere else to show itself. The frame rate cannot be seen at all
 * -- a game at 45 frames a second looks like a game whose animation is slow -- and the rest is
 * spread over three menus that have to be opened one at a time, which is no way to answer "why does
 * this sound wrong" or "am I still recording".
 *
 * <h2>As much as fits, and the rest one hover away</h2>
 *
 * The line on screen is not a chosen subset. {@link #parts} puts everything worth saying in order
 * of how badly it wants saying -- the console first, then what changes what the game does, then
 * what changes only what you see -- and {@link #doLayout} takes as many of them as the window is
 * wide enough for. Nothing is dropped while there is room for it, so at 4x the whole list is on
 * screen and at 1x it may be the console and a count.
 * <p>
 * A count, because a bar that quietly showed less would be worse than one that showed nothing: the
 * line always ends with an information mark, and with <strong>+3</strong> in front of it when three
 * things did not fit. That mark is there even when everything fits, since the tooltip is a longer
 * answer than the line ever is -- {@link #detail} names every setting including the ones sitting at
 * their default, which is the difference between a warning and an inventory.
 *
 * <h2>Where it gets what it says</h2>
 *
 * It describes the <em>machine</em> rather than the menus, which is a distinction with one real
 * case behind it: a movie pins its own overclock and its own Game Genie codes when it starts, so
 * while one is playing the menus and the machine disagree and this follows the machine. That is why
 * {@link Machine} carries an {@link Overclock} in scanlines rather than the
 * {@link OverclockSetting} the menu remembers.
 * <p>
 * Nothing here reads the emulator. {@link GameUIFrame} works out what to say on the event dispatch
 * thread and hands it over, the way it does for the title bar -- which carries a shorter version
 * still of the same sentence, for the window list rather than for the player.
 */
final class StatusBar extends JPanel {

    /**
     * What goes between two things the bar has to say. A middle dot rather than a comma or a pipe:
     * the parts are not a list and not columns, and at this size a dot separates them without
     * drawing a line down the window.
     */
    private static final String SEPARATOR = " · ";

    /**
     * The information mark, and what to write where a font has not got one.
     * <p>
     * Asked of the font rather than of the platform. This runs wherever Java does and the glyph is
     * in every system font worth the name, but a machine whose default font has not got it would
     * otherwise draw the one thing on the bar that says "there is more here" as an empty box.
     */
    private static final char INFO = 'ⓘ';
    private static final String ASCII_INFO = "(i)";

    /**
     * Everything the bar can say about what the machine <em>is</em>, as against what it is doing.
     * <p>
     * Taken in one go rather than set one field at a time, because the two renderings both read all
     * of it: a bar told these separately would have to keep a copy of every one to write either
     * sentence from. Most of them only ever appear in {@link #detail}, which is the point of them
     * being here -- the tooltip is meant to be the complete answer.
     *
     * @param region           the console, which is the one thing always named on screen: it is
     *                         invisible from the picture until something is wrong, and when it is
     *                         wrong it is the answer.
     * @param regionSetting    how that was arrived at -- believed from the header, or insisted on.
     * @param overclock        the extra scanlines on the frame, {@link Overclock#NONE} for a
     *                         machine running the hardware's own timing.
     * @param genieCodes       how many codes are in the cartridge slot.
     * @param unlimitedSprites whether the chip is drawing the sprites it would have dropped.
     * @param filter           how the picture is being drawn. What is actually in force rather
     *                         than what the menu has ticked, which are two different things on a
     *                         PAL machine.
     * @param strength         how hard that filter is applied, which the filter can make
     *                         irrelevant by being none at all.
     * @param warp             whether the tube's glass is curved, which only the tube has.
     * @param palette          the name of the table the picture is drawn through, which the
     *                         decoder can make irrelevant.
     * @param screenScale      how big the window's picture is.
     * @param screenshotScale  how big the next screenshot will be, which is the setting with the
     *                         least to show for itself: it changes nothing on screen and nothing at
     *                         all until a file is written.
     * @param fastForward      the speed Fast Forward runs at, whether or not it is switched on.
     * @param rewindSeconds    how much history is kept, or 0 for none. One of the two settings in
     *                         the program with no menu item at all, so this tooltip is the one place
     *                         in the window it can be read.
     * @param muted            whether the sound is switched off, which is the one question on this
     *                         list somebody asks out loud.
     * @param volume           how loud it is when it is not, which the mute can make irrelevant.
     * @param audioLatencyMs   how much sound is kept queued at the card. The other setting with no
     *                         menu item, and the one with the least to look at: what it buys is a
     *                         click that does not happen.
     */
    record Machine(
            Region region,
            RegionSetting regionSetting,
            Overclock overclock,
            int genieCodes,
            boolean unlimitedSprites,
            VideoFilter filter,
            FilterStrength strength,
            boolean warp,
            String palette,
            ScreenScale screenScale,
            ScreenScale screenshotScale,
            EmulationSpeed fastForward,
            int rewindSeconds,
            boolean muted,
            Volume volume,
            int audioLatencyMs) {
    }

    /**
     * How fast it is going, what it is, and what it is doing. Three labels rather than one, because
     * the three want to be in three places: the rate at the left where the eye rests, the machine
     * beside it, and whatever it is doing pushed over to the right where a recording light belongs.
     */
    private final JLabel rate = new JLabel();
    private final JLabel machine = new JLabel();
    private final JLabel activity = new JLabel();

    /**
     * Everything there is to say about the machine, in the order it wants saying. Kept rather than
     * turned straight into text, because how much of it fits is not known until the window has been
     * laid out -- and is a different answer after every resize.
     */
    private List<String> parts = List.of();

    /**
     * The mark that says there is more under the pointer.
     */
    private final String info;

    StatusBar() {
        // The description's column is the one that grows, which is what makes the fitting in
        // doLayout terminate: its width is decided by the window and the other two columns, never
        // by its own text, so putting different text in it cannot change how much room it has.
        super(new MigLayout("insets 2 8 2 8, hidemode 3", "[][grow,fill][]", "[]"));

        // Cells by number rather than by the order they are added, because hidemode 3 makes a
        // hidden component vanish from the grid entirely -- and a bar with no rate to show would
        // otherwise slide the description into the rate's column, which is the one that does not
        // grow. The gaps ride on the components rather than on the columns for the same reason: a
        // gap on a column stays behind when the thing it was separating has gone.
        //
        // wmin 0 so the growing column may be narrower than the text in it -- below that width a
        // JLabel puts an ellipsis on the end, which is the backstop for a window too narrow even
        // for the console's name. grow because the description has to fill the row it is given
        // rather than the space its own text asks for, which on the pass that decides the layout is
        // none: doLayout writes the text after super.doLayout() has already handed out the bounds.
        add(rate, "cell 0 0, gapright 16");
        add(machine, "cell 1 0, wmin 0, grow");
        add(activity, "cell 2 0, gapleft 16");

        var font = getFont().deriveFont(getFont().getSize2D() - 1f);

        rate.setFont(font);
        machine.setFont(font);
        activity.setFont(font);

        info = font.canDisplay(INFO) ? String.valueOf(INFO) : ASCII_INFO;

        // And the row is as tall as that font rather than as tall as whatever happens to be written
        // in it. Same reason as the grow above and the other half of it: the description is empty
        // when the height is settled, and a bar that took its height from that would have none at
        // all until a machine was running.
        ((MigLayout) getLayout()).setRowConstraints(
                "[" + getFontMetrics(font).getHeight() + "!]");

        // The description recedes and the other two do not. It is the longest thing here and the
        // least urgent -- it says what was set up before the game started, where the rate and the
        // activity are about the machine right now.
        machine.setForeground(colour("Label.disabledForeground", Color.GRAY));

        setBorder(BorderFactory.createMatteBorder(
                1, 0, 0, 0, colour("Separator.foreground", Color.GRAY)));
    }

    /**
     * As wide as it is given, and no wider.
     * <p>
     * {@link java.awt.Window#pack} sizes a window from what is inside it, and a label's preferred
     * width is the width of its text -- so a bar with a lot to say would drag the window out wider
     * than the picture and letterbox the game to make room for a sentence about it. Asking for
     * nothing across leaves the width to the screen and the menu bar, which is what decided it
     * before this existed. The height is the real one, because that is a row the window does have
     * to find space for.
     */
    @Override
    public Dimension getPreferredSize() {
        return new Dimension(0, super.getPreferredSize().height);
    }

    /**
     * How many frames a second the machine is getting through, or {@link FrameRate#UNKNOWN} for
     * nothing measured yet -- which shows as nothing at all rather than as a zero, since zero is
     * what a paused machine honestly reads.
     */
    void setFrameRate(final int fps) {
        set(rate, describeRate(fps));

        rate.setVisible(fps != FrameRate.UNKNOWN);
    }

    /**
     * The rate as the bar spells it. Package-private and static for the reason {@link #parts}
     * is: what the bar says is worth checking without a window to say it in.
     */
    static String describeRate(final int fps) {
        return fps == FrameRate.UNKNOWN ? "" : fps + " fps";
    }

    /**
     * The short answer onto the bar, and the long one under the pointer.
     * <p>
     * The tooltip goes on all three labels as well as on the panel. Swing looks for one on the
     * deepest component under the pointer and does not walk up from a child that has none, so a bar
     * that set it only on itself would answer over its own margins and nowhere else -- which is
     * most of the width covered by the very labels somebody is pointing at.
     */
    void setMachine(final Machine description) {
        parts = parts(description);

        // Which of them fit is doLayout's answer, and it is a different one at every window width.
        revalidate();

        var detail = detail(description);

        setToolTipText(detail);
        rate.setToolTipText(detail);
        machine.setToolTipText(detail);
        activity.setToolTipText(detail);
    }

    /**
     * Lays the three labels out and then fills the middle one with as much as it turned out to have
     * room for.
     * <p>
     * Here rather than in {@link #setMachine} because the answer depends on the width, which is
     * this method's own output -- and on the other two labels, which are three characters wide with
     * no machine running and twelve with one fast forwarding.
     * <p>
     * Putting text into a label asks for another layout, so this has to settle. It does, because
     * the description's column grows rather than sizing to its contents: the second pass measures
     * the same width, fits the same parts, finds {@link #set} has nothing to change and stops. What
     * would not settle is a column sized from the text, where a shorter line frees the room that
     * would have made it longer.
     */
    @Override
    public void doLayout() {
        super.doLayout();

        var metrics = machine.getFontMetrics(machine.getFont());

        set(machine, fitted(parts, info, machine.getWidth(), metrics::stringWidth));
    }

    /**
     * The longest prefix of {@link #parts} that will draw inside {@code width}, and a count of what
     * had to be left out.
     * <p>
     * From the end rather than adding one at a time, so that a part is only dropped once everything
     * after it has been -- the order in {@link #parts} is what decides, and it is decided there
     * rather than here. The console survives whatever happens: a window too narrow even for that is
     * left to the label's own ellipsis.
     * <p>
     * Handed a way to measure text rather than a font, which is the whole of what makes the rule
     * testable: the window passes {@code FontMetrics::stringWidth} and a test passes something it
     * can do arithmetic with.
     */
    static String fitted(
            final List<String> parts,
            final String info,
            final int width,
            final ToIntFunction<String> widthOf) {
        if (parts.isEmpty()) {
            return "";
        }

        for (var kept = parts.size(); kept > 1; kept--) {
            var text = line(parts, kept, info);

            if (widthOf.applyAsInt(text) <= width) {
                return text;
            }
        }

        return line(parts, 1, info);
    }

    /**
     * The first {@code kept} of them as one line, with the mark on the end -- and with the number
     * that did not fit in front of it, because a bar that quietly showed less than it knew would be
     * worse than one that showed nothing at all.
     */
    static String line(final List<String> parts, final int kept, final String info) {
        var text = String.join(SEPARATOR, parts.subList(0, kept));
        var hidden = parts.size() - kept;

        return hidden == 0 ? text + "  " + info : text + SEPARATOR + "+" + hidden + " " + info;
    }

    /**
     * What the machine is doing, when it is doing anything other than simply running: the word
     * {@link GameUIFrame} also puts in the title bar. Empty for a machine that is just playing the
     * game.
     */
    void setActivity(final String what) {
        set(activity, what);
    }

    /**
     * Everything worth putting on the line, in the order it wants saying.
     * <p>
     * The order is the whole of the design, since the window decides where to stop reading it. The
     * console first, because it is the one thing always named and the one thing invisible from the
     * picture until something is wrong. Then the two that mean this is not the game as it shipped:
     * a frame with extra scanlines on it and a cartridge being answered with somebody else's bytes.
     * Then silence, which is not a change to the game at all but is the question a status bar is
     * asked most often. Then the three that change what you see and nothing the game can observe.
     * <p>
     * Only what is not the ordinary case, apart from the console -- so the usual list is one long,
     * and the settings that always have a value, like the palette and the screen size, are left to
     * {@link #detail} rather than filling the bar with things nobody changed.
     * <p>
     * Package-private and static so that what the bar says can be tested as a list, without a
     * window to put it in.
     */
    static List<String> parts(final Machine machine) {
        var parts = new ArrayList<String>();

        parts.add(machine.region().label());

        if (!machine.overclock().isNone()) {
            parts.add("Overclock +"
                    + overclockPercent(machine.region(), machine.overclock()) + "%");
        }

        if (machine.genieCodes() > 0) {
            parts.add(codes(machine.genieCodes()));
        }

        if (machine.muted()) {
            parts.add("Muted");
        } else if (machine.volume() != Volume.defaultVolume()) {
            // Only when it is not muted, because a mute is the whole answer: a bar saying "Muted"
            // and "Volume 25%" would be offering two of them.
            parts.add("Volume " + machine.volume().label());
        }

        if (machine.unlimitedSprites()) {
            parts.add("Unlimited sprites");
        }

        if (machine.filter() != VideoFilter.NONE) {
            // The strength rides on the filter's own part rather than taking one of its own. It is
            // a setting on something already named, and an item of its own would push the console
            // off the end of a narrow window to say how hard a filter is applied.
            var qualifiers = new ArrayList<String>();

            if (machine.strength() != FilterStrength.defaultStrength()) {
                qualifiers.add(machine.strength().label());
            }

            if (machine.filter() == VideoFilter.CRT && machine.warp()) {
                qualifiers.add("Curved");
            }

            parts.add(qualifiers.isEmpty()
                    ? machine.filter().label() + " filter"
                    : machine.filter().label() + " filter ("
                            + String.join(", ", qualifiers) + ")");
        }

        // Last, because it changes nothing until a file is written -- which also makes it the one
        // somebody is most likely to have forgotten about.
        if (machine.screenshotScale() != ScreenScale.defaultScreenshotScale()) {
            parts.add("Screenshot " + machine.screenshotScale().label());
        }

        return parts;
    }

    /**
     * The whole answer, as the HTML table a tooltip takes.
     * <p>
     * Every setting, including the ones sitting at their default, which is the difference between
     * this and the line on screen: that one is a warning and this one is an inventory. A row that
     * said nothing when its setting was ordinary would leave somebody wondering whether the setting
     * exists at all, which is the question a hover is being asked.
     */
    static String detail(final Machine machine) {
        var rows = new StringBuilder("<html><table cellpadding='0' cellspacing='0'>");

        row(rows, "Console", machine.region().label());
        row(rows, "Region setting", machine.regionSetting().label());
        row(rows, "Overclock", machine.overclock().isNone()
                ? "Off"
                : "+" + overclockPercent(machine.region(), machine.overclock()) + "%");
        row(rows, "Game Genie", machine.genieCodes() == 0 ? "None" : codes(machine.genieCodes()));
        row(rows, "Unlimited sprites", machine.unlimitedSprites() ? "On" : "Off");
        row(rows, "Video filter", machine.filter().label());

        // The same news the greyed-out Strength submenu carries: a strength is how much of what a
        // filter does to do, and a bare lookup table does nothing for it to be a fraction of.
        row(rows, "Filter strength", machine.filter() == VideoFilter.NONE
                ? "No filter"
                : machine.strength().label());

        // Said rather than left blank, and it is the same news the greyed-out Palette item carries:
        // a decoder works its colours out of the signal and never opens the table at all. Only the
        // decoder -- the tube is drawn through the table like everything else.
        row(rows, "Palette", machine.filter() == VideoFilter.NTSC
                ? "Not consulted"
                : machine.palette());

        // The same news the greyed-out item carries: there is no glass in front of a lookup table
        // or a decoder, so this is not a setting either of them is at.
        row(rows, "Curved glass", machine.filter() != VideoFilter.CRT
                ? "No tube"
                : machine.warp() ? "On" : "Off");

        row(rows, "Screen size", machine.screenScale().label());
        row(rows, "Screenshot size", machine.screenshotScale().label());
        row(rows, "Fast forward speed", machine.fastForward().label());
        row(rows, "Rewind history", machine.rewindSeconds() == 0
                ? "Off"
                : machine.rewindSeconds() + " seconds");
        row(rows, "Sound", machine.muted() ? "Muted" : "On");

        // Said even while muted, unlike on the line above, because this is the inventory: what the
        // volume is set to is a fact about the emulator whether or not it can be heard right now.
        row(rows, "Volume", machine.volume().label());
        row(rows, "Audio latency", machine.audioLatencyMs() + " ms");

        return rows.append("</table></html>").toString();
    }

    private static void row(final StringBuilder rows, final String name, final String value) {
        rows.append("<tr><td>")
                .append(escaped(name))
                .append("&nbsp;&nbsp;&nbsp;</td><td>")
                .append(escaped(value))
                .append("</td></tr>");
    }

    /**
     * A palette is named in a file somebody else wrote, so it is the one value here that is not
     * this program's own words. Nothing under {@code /palettes} has a bracket in its name today;
     * a table that arrives with one should show it rather than break the tooltip.
     */
    private static String escaped(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;");
    }

    private static String codes(final int count) {
        return count + (count == 1 ? " Genie code" : " Genie codes");
    }

    /**
     * How much longer an overclock makes a frame, as a percentage of the region's own.
     * <p>
     * A percentage because that is the unit the menu offers and the unit the setting is remembered
     * in -- and worked back out of the scanlines rather than read off the menu, because the menu is
     * not always what is running. Both halves are counted: a line after the NMI is as much extra
     * time as one before it, and what differs between them is what the game can observe rather than
     * how much of it there is.
     * <p>
     * Never rounds down to nothing. A machine with extra lines on it must not report that it has
     * none, and a percentage under a half only arrives from a movie recorded with a handful of
     * lines from the command line.
     */
    private static int overclockPercent(final Region region, final Overclock overclock) {
        var lines = overclock.beforeNmi() + overclock.afterNmi();

        return Math.max(1, (int) Math.round(lines * 100.0 / region.scanlinesPerFrame()));
    }

    /**
     * A colour from the look and feel, or something plain when it has none. FlatLaf has both of
     * these; a machine that fell back to Metal should still get a bar it can read.
     */
    private static Color colour(final String key, final Color fallback) {
        var colour = UIManager.getColor(key);

        return colour == null ? fallback : colour;
    }

    /**
     * Only when it has changed. {@link JLabel#setText} repaints whether or not the text is new, and
     * this is called once a second for as long as the emulator is open, on top of every time
     * something actually moves.
     */
    private static void set(final JLabel label, final String text) {
        if (!label.getText().equals(text)) {
            label.setText(text);
        }
    }
}
