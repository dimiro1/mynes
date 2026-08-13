package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Region;

import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.stream.Stream;

/**
 * How fast the emulation loop is allowed to run the machine.
 * <p>
 * This belongs to the loop rather than to the machine. Nothing inside the NES knows what a second
 * is -- it counts its own cycles and nothing else -- so running at four times speed is not a
 * different console, it is the same console with the wait between frames cut to a quarter. Which is
 * why fast forward costs nothing in accuracy.
 * <p>
 * The APU is the one part that notices, and not because it is clocked any differently: a sound card
 * plays 44100 samples a second whatever the machine making them is doing, so the samples of four
 * frames cannot be handed over in the time of one. {@link AudioOutput} drops what will not fit,
 * which is why fast forward sounds chopped rather than sped up.
 * <p>
 * {@link #UNLIMITED} is the odd one out, and deliberately so: it does not wait at all, so how fast
 * it goes is a fact about the computer rather than about the setting. On the machine this was
 * written on that is around ten times, which is also the ceiling the numbered speeds sit under.
 * Asking for more than the host can manage is not an error -- the loop simply never catches up with
 * its deadline and the machine runs at whatever the host does manage.
 */
public enum EmulationSpeed {
    NORMAL("1x", "Normal", 1),
    TWO_TIMES("2x", "2x", 2),
    FOUR_TIMES("4x", "4x", 4),
    EIGHT_TIMES("8x", "8x", 8),

    /**
     * No wait at all, so as fast as the host manages. Its multiplier is zero because there is no
     * number to give: the speed is a fact about the computer rather than about the setting.
     */
    UNLIMITED("unlimited", "Unlimited", 0);

    private static final Logger logger = System.getLogger("UI");

    /**
     * Everything Fast Forward can be set to, in the order the menu lists it: every speed except
     * {@link #NORMAL}, which is what having fast forward switched off already means.
     */
    private static final List<EmulationSpeed> FAST_FORWARD_CHOICES =
            Stream.of(values()).filter(speed -> speed != NORMAL).toList();

    private final String id;
    private final String label;
    private final int multiplier;

    EmulationSpeed(final String id, final String label, final int multiplier) {
        this.id = id;
        this.label = label;
        this.multiplier = multiplier;
    }

    /**
     * How this speed is spelled in the config file. Lower case and hand typeable, since the file is
     * meant to be edited by hand as much as through the menu.
     */
    public String id() {
        return id;
    }

    /**
     * How this speed is spelled in the menu.
     */
    public String label() {
        return label;
    }

    /**
     * How long a frame is allowed to take at this speed, in nanoseconds, or zero for
     * {@link #UNLIMITED}, which is not paced and so has no answer to give.
     * <p>
     * The region is what says how long a frame is: 16.639ms on NTSC and 19.997ms on PAL, neither of
     * them the round number they are usually called. The fraction matters because the APU agrees
     * with it -- 60.0988 frames of 734 samples each is exactly the 44100 a second the sound card
     * wants -- so rounding either to 60 or 50 would leave the two clocks disagreeing by a third of
     * a percent, which is a card running dry every few minutes.
     */
    public long frameNanos(final Region region) {
        // Unlimited has no frame to budget for: the loop recognises it and never waits at all.
        return multiplier == 0 ? 0 : region.frameNanos() / multiplier;
    }

    /**
     * The speeds Fast Forward offers.
     */
    public static List<EmulationSpeed> fastForwardChoices() {
        return FAST_FORWARD_CHOICES;
    }

    /**
     * What Fast Forward runs at when nothing has said otherwise.
     * <p>
     * Four times is a long way under what the emulator manages unthrottled, so it is a speed the
     * machine actually keeps rather than a promise it might not.
     */
    public static EmulationSpeed defaultFastForward() {
        return FOUR_TIMES;
    }

    /**
     * The fast forward speed {@code id} names, or the default if nothing does.
     * <p>
     * {@link #NORMAL} is not one of the answers: an {@code emulation.fast-forward=1x} in a
     * hand-edited file is somebody asking for a fast forward that does nothing, which is what
     * leaving the menu item unticked is for. That, a misspelling and a speed dropped in a later
     * version all cost the choice rather than the startup.
     */
    public static EmulationSpeed fastForwardById(final String id) {
        for (var speed : FAST_FORWARD_CHOICES) {
            if (speed.id().equals(id)) {
                return speed;
            }
        }

        logger.log(Level.WARNING,
                id + " is not a fast forward speed, falling back to " + defaultFastForward().id());

        return defaultFastForward();
    }
}
