package com.github.dimiro1.mynes.ui.controlpanel;

import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.debugger.Theme;
import com.github.dimiro1.mynes.ui.sound.Notes;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;

import java.awt.Font;
import java.util.ArrayList;

/**
 * Three lines across the top of the control panel saying what the machine is doing.
 * <p>
 * The instruments below answer questions somebody has gone looking for -- which tile, which sprite,
 * which byte. This answers the one nobody should have to go looking for: <em>is it doing what I
 * think it is doing</em>. A game running at 50 frames a second, a background layer switched off
 * three sessions ago, a frame counter somebody's music driver put into five step mode: every one of
 * those is invisible in a picture and obvious here.
 * <p>
 * The first line is the window's -- how the machine is being run, which only the thing running it
 * knows -- and the other two are the machine's, out of a {@link Readout} taken at a frame boundary.
 * Monospaced, because the point of it is that a number stays in the same place when it changes:
 * a proportional font makes a dashboard flicker sideways every time a digit turns over.
 */
final class Dashboard extends JPanel {
    /**
     * The separator between one fact and the next. A middle dot with a space either side, rather
     * than two spaces, because at a glance the eye needs to be told where one number ends -- and
     * rather than a pipe, which reads as a column edge in a window that has real ones below.
     */
    private static final String GAP = "  ·  ";

    private static final String NOTHING = "—";

    private final JLabel running = line();
    private final JLabel ppu = line();
    private final JLabel apu = line();

    Dashboard() {
        setLayout(new MigLayout("insets 6 12 6 12, wrap 1, gapy 1, fillx", "[grow,fill]", ""));
        setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.dim()));

        add(running);
        add(ppu);
        add(apu);

        clear();
    }

    /**
     * The first line: how the machine is being run rather than what it is doing.
     * <p>
     * Handed over already written, because every part of it belongs to the window -- whether the
     * loop is paused, what the frame rate has measured, which cartridge is in, whether a movie is
     * being recorded -- and none of it can be read off the machine at all.
     */
    void setRunning(final String text) {
        running.setText(text);
    }

    /**
     * The other two lines, from the machine as it stood when a frame finished.
     */
    void show(final Readout readout) {
        ppu.setText(describePPU(readout));
        apu.setText(describeAPU(readout));
    }

    /**
     * What there is to say about a machine that is not running: nothing, said in a way that keeps
     * the three lines the height they will be.
     */
    void clear() {
        running.setText(" ");
        ppu.setText(" ");
        apu.setText(" ");
    }

    /**
     * $2000 and $2001 decoded, where the scroll is, and the two flags a raster bug turns on.
     * <p>
     * Spelled rather than given as two hex bytes, because the whole reason to look is that a bit is
     * not what you thought: "NMI off" is an answer, and "$2000 $80" is the same question again.
     */
    private static String describePPU(final Readout readout) {
        var parts = new ArrayList<String>();

        // On the machine's line rather than up with the frame rate, because it is the machine's:
        // how fast it is being run is the window's business and which frame it has reached is not.
        parts.add("frame " + readout.frame());
        parts.add("NMI " + on(readout.control(), 0x80));
        parts.add("BG " + on(readout.mask(), 0x08));
        parts.add("SPR " + on(readout.mask(), 0x10));
        parts.add((readout.control() & 0x20) != 0 ? "8x16" : "8x8");
        parts.add(String.format(
                "BG $%04X SPR $%04X",
                (readout.control() & 0x10) != 0 ? 0x1000 : 0x0000,
                (readout.control() & 0x08) != 0 ? 0x1000 : 0x0000));
        parts.add(String.format("NT $%04X", 0x2000 + (readout.control() & 3) * 0x400));
        parts.add("scroll " + readout.scrollX() + ", " + readout.scrollY());

        var flags = new ArrayList<String>();

        if ((readout.status() & 0x40) != 0) {
            flags.add("sprite 0");
        }

        if ((readout.status() & 0x20) != 0) {
            flags.add("overflow");
        }

        if (!flags.isEmpty()) {
            parts.add(String.join(" ", flags));
        }

        if (readout.mask() != 0 && (readout.mask() & 0x18) == 0) {
            // Rendering off with something else in $2001 set is a game part way through a screen
            // change, and it is the state most often mistaken for a crash.
            parts.add("rendering off");
        }

        return "PPU  " + String.join(GAP, parts);
    }

    /**
     * Which voices have something left to play, how the frame counter is sequencing them, what is
     * being held on the pad, and how often the game has been reading it.
     * <p>
     * Out of $4015, which says whether a channel's length counter has anything left rather than
     * whether it is audible this instant -- so a voice that is silent between notes still shows.
     * That is the right answer for "is this channel doing anything at all", which is what somebody
     * looking at a silent game is asking.
     */
    private static String describeAPU(final Readout readout) {
        var voices = new ArrayList<String>();

        for (var channel : APUChannel.values()) {
            voices.add(describe(readout, channel));
        }

        var parts = new ArrayList<String>();

        // All five are always named, with a filled or hollow mark in front. A list that dropped
        // the silent ones would move every name left as the music played and be unreadable at
        // exactly the moment somebody was watching it.
        parts.add(String.join("  ", voices));
        parts.add(readout.fiveStep() ? "5-step" : "4-step");

        if (readout.frameIRQInhibited()) {
            parts.add("IRQ off");
        }

        if ((readout.apuStatus() & 0x40) != 0) {
            parts.add("frame IRQ");
        }

        if ((readout.apuStatus() & 0x80) != 0) {
            parts.add("DMC IRQ");
        }

        parts.add("PAD " + buttons(readout.pad1()));

        // Beside the pad rather than beside the frame rate, which is where it looks like it
        // belongs: what this counts is frames the game never read the pad in, which is a fact about
        // the game's main loop and not about how fast the loop around it is being run. Absent
        // rather than zero when nothing has been counting, since "no lag" and "nobody looked" are
        // different answers -- see Readout.Pads.
        var pads = readout.pads();

        if (pads.frames() > 0) {
            parts.add("lag " + pads.lagFrames() + " of " + pads.frames());
        }

        return "APU  " + String.join(GAP, parts);
    }

    /**
     * One voice: whether it has anything left to play, its name, and -- for the three that make a
     * pitch -- the note it is making. The note is the whole reason to look at this line rather than
     * at the Sound tab, which has the same thing in eight columns.
     */
    private static String describe(final Readout readout, final APUChannel channel) {
        var mark = (readout.apuStatus() & (1 << channel.ordinal())) != 0 ? "● " : "○ ";
        var voice = readout.voice(channel);

        // Only while it is playing, unlike the Sound tab: this line is a glance, and the note a
        // silent channel happens to be tuned to is detail rather than news. Through pitch() rather
        // than through the channel, so that this line and the tab cannot come to disagree about
        // which voices have a note -- the noise has one in short mode and none otherwise, and two
        // places deciding that separately is exactly how they would drift apart.
        var note = voice.playing() && voice.pitch() > 0 ? Notes.nameOf(voice.pitch()) : null;

        return mark + channel.label() + (note == null ? "" : " " + note);
    }

    /**
     * The pad as arrows and letters, in the order a controller has them, so that a held direction
     * is recognised without reading. Nothing held is a dash rather than an empty space, which would
     * read as the line having been cut off.
     */
    private static String buttons(final int held) {
        var text = new StringBuilder();

        text.append(held(held, Controller.BUTTON_LEFT, "←"));
        text.append(held(held, Controller.BUTTON_UP, "↑"));
        text.append(held(held, Controller.BUTTON_DOWN, "↓"));
        text.append(held(held, Controller.BUTTON_RIGHT, "→"));
        text.append(held(held, Controller.BUTTON_SELECT, " Sel"));
        text.append(held(held, Controller.BUTTON_START, " Start"));
        text.append(held(held, Controller.BUTTON_B, " B"));
        text.append(held(held, Controller.BUTTON_A, " A"));

        return text.isEmpty() ? NOTHING : text.toString().trim();
    }

    private static String held(final int mask, final int button, final String name) {
        return (mask & button) != 0 ? name : "";
    }

    private static String on(final int register, final int bit) {
        return (register & bit) != 0 ? "on" : "off";
    }

    private static JLabel line() {
        var label = new JLabel(" ");

        label.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));

        return label;
    }
}
