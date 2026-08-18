package com.github.dimiro1.mynes.headless;

import com.github.dimiro1.mynes.Controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What to press, and when.
 * <p>
 * A headless run needs this more than it might seem. Left alone, most cartridges never start:
 * Super Mario Bros., Super Mario Bros. 3 and Tetris all sit on their title screens for as long as
 * anyone cares to wait, drawing the same picture and writing nothing to the sound registers, and
 * only Super Mario Bros. 2 plays untouched. A run with no input is not a run of the game, it is a
 * run of the game's menu -- which has cost real time to work out more than once.
 * <p>
 * So the grammar is built around the shape of the answer to that: {@code 60/40:start} presses Start
 * at frame 60 and again every forty frames after it, which is enough to get all four of those
 * cartridges into their first level without knowing anything about how many menus each one has.
 * <p>
 * Frames count from power on. No spec contains a space, which is what lets a whole schedule survive
 * being passed through {@code -Dexec.args} without a second layer of quoting.
 */
public final class InputSchedule {
    /**
     * The button names the specs use, lower case and spelled the way the console's own manual
     * spells them. Ordered, because this is also the list an error message offers and the order a
     * report names them in.
     */
    private static final Map<String, Integer> BUTTONS = new LinkedHashMap<>();

    static {
        BUTTONS.put("a", Controller.BUTTON_A);
        BUTTONS.put("b", Controller.BUTTON_B);
        BUTTONS.put("select", Controller.BUTTON_SELECT);
        BUTTONS.put("start", Controller.BUTTON_START);
        BUTTONS.put("up", Controller.BUTTON_UP);
        BUTTONS.put("down", Controller.BUTTON_DOWN);
        BUTTONS.put("left", Controller.BUTTON_LEFT);
        BUTTONS.put("right", Controller.BUTTON_RIGHT);
    }

    /**
     * What a spec asks for.
     */
    public enum Kind {
        /**
         * Held for the press length from one frame. {@code 60:start}
         */
        PRESS,

        /**
         * Held from one frame until another. {@code 200-400:right}
         */
        HOLD,

        /**
         * Pressed over and over at a fixed spacing. {@code 60/40:start}
         */
        PULSE
    }

    /**
     * One spec, parsed.
     *
     * @param kind    which of the three shapes it is.
     * @param from    the first frame it is live on.
     * @param to      the frame a {@link Kind#HOLD} stops before; unused otherwise.
     * @param every   how far apart a {@link Kind#PULSE}'s presses are; unused otherwise.
     * @param count   how many times a {@link Kind#PULSE} presses, or 0 for as long as the run
     *                lasts.
     * @param buttons the mask of {@link Controller} bits it presses.
     */
    public record Event(Kind kind, long from, long to, long every, long count, int buttons) {
    }

    private final List<Event> events;
    private final int pressFrames;

    private InputSchedule(final List<Event> events, final int pressFrames) {
        this.events = List.copyOf(events);
        this.pressFrames = pressFrames;
    }

    /**
     * Reads a schedule.
     *
     * @param specs       specs, in any of the four forms. A spec holding commas is several specs,
     *                    so that one {@code --input} can carry a whole schedule.
     * @param pressFrames how many frames a press is held for. One frame is enough for a game that
     *                    reads the pad in its NMI handler and not enough for one that does not.
     * @throws UsageException if a spec cannot be read.
     */
    public static InputSchedule parse(final List<String> specs, final int pressFrames) {
        var events = new ArrayList<Event>();

        for (var spec : specs) {
            for (var one : spec.split(",")) {
                var trimmed = one.trim();

                if (!trimmed.isEmpty()) {
                    events.add(parseOne(trimmed, pressFrames));
                }
            }
        }

        return new InputSchedule(events, pressFrames);
    }

    /**
     * An empty schedule, which presses nothing. What the interactive mode starts with, since there
     * the presses arrive as commands instead.
     */
    public static InputSchedule empty(final int pressFrames) {
        return new InputSchedule(List.of(), pressFrames);
    }

    public List<Event> events() {
        return events;
    }

    public boolean isEmpty() {
        return events.isEmpty();
    }

    /**
     * What is held down on a given frame.
     * <p>
     * Two specs live on the same frame press together rather than one winning, so a schedule can be
     * written a button at a time: {@code --input 100-200:right --input 150:a} is a run and a jump
     * out of it.
     *
     * @param frame frames since power on.
     * @return the mask of {@link Controller} bits, or zero.
     */
    public int buttonsAt(final long frame) {
        var mask = 0;

        for (var event : events) {
            if (isLive(event, frame)) {
                mask |= event.buttons();
            }
        }

        return mask;
    }

    private boolean isLive(final Event event, final long frame) {
        var since = frame - event.from();

        if (since < 0) {
            return false;
        }

        return switch (event.kind()) {
            case PRESS -> since < pressFrames;
            case HOLD -> frame < event.to();
            case PULSE -> {
                var press = since / event.every();

                if (event.count() > 0 && press >= event.count()) {
                    yield false;
                }

                yield since % event.every() < pressFrames;
            }
        };
    }

    private static Event parseOne(final String spec, final int pressFrames) {
        var colon = spec.indexOf(':');

        if (colon < 0) {
            throw new UsageException(
                    "input \"" + spec + "\" has no colon; it should look like 60:start, "
                            + "200-400:right or 60/40:start");
        }

        var when = spec.substring(0, colon);
        var buttons = parseButtons(spec.substring(colon + 1), spec);

        if (when.indexOf('-') >= 0) {
            var dash = when.indexOf('-');
            var from = parseFrame(when.substring(0, dash), spec);
            var to = parseFrame(when.substring(dash + 1), spec);

            if (to <= from) {
                throw new UsageException(
                        "input \"" + spec + "\" holds until frame " + to + ", which is not after "
                                + from);
            }

            return new Event(Kind.HOLD, from, to, 0, 0, buttons);
        }

        if (when.indexOf('/') >= 0) {
            var slash = when.indexOf('/');
            var from = parseFrame(when.substring(0, slash), spec);
            var rest = when.substring(slash + 1);
            var count = 0L;
            var times = rest.indexOf('x');

            if (times >= 0) {
                count = parseFrame(rest.substring(times + 1), spec);
                rest = rest.substring(0, times);

                if (count == 0) {
                    throw new UsageException("input \"" + spec + "\" presses zero times");
                }
            }

            var every = parseFrame(rest, spec);

            if (every == 0) {
                throw new UsageException("input \"" + spec + "\" repeats every zero frames");
            }

            return new Event(Kind.PULSE, from, 0, every, count, buttons);
        }

        var from = parseFrame(when, spec);

        return new Event(Kind.PRESS, from, from + pressFrames, 0, 0, buttons);
    }

    private static long parseFrame(final String text, final String spec) {
        try {
            var frame = Long.parseLong(text);

            if (frame < 0) {
                throw new UsageException("input \"" + spec + "\" names a frame before power on");
            }

            return frame;
        } catch (NumberFormatException e) {
            throw new UsageException("input \"" + spec + "\": \"" + text + "\" is not a frame");
        }
    }

    private static int parseButtons(final String text, final String spec) {
        var mask = 0;

        for (var name : text.split("\\+")) {
            var button = BUTTONS.get(name.trim().toLowerCase());

            if (button == null) {
                throw new UsageException(
                        "input \"" + spec + "\": \"" + name + "\" is not a button. They are "
                                + String.join(", ", BUTTONS.keySet()) + ".");
            }

            mask |= button;
        }

        return mask;
    }

    /**
     * Names the buttons in a mask, for a report that has to say what it understood.
     */
    public static List<String> describe(final int mask) {
        var names = new ArrayList<String>();

        for (var button : BUTTONS.entrySet()) {
            if ((mask & button.getValue()) != 0) {
                names.add(button.getKey());
            }
        }

        return names;
    }

    /**
     * Reads the buttons in a spec like {@code a+right}, for the interactive mode's press command.
     *
     * @throws UsageException if a name is not a button.
     */
    public static int parseButtonList(final String text) {
        return parseButtons(text, text);
    }
}
