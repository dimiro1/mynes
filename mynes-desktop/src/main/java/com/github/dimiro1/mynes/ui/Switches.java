package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.video.FilterStrength;
import com.github.dimiro1.mynes.video.VideoFilter;
import org.jetbrains.annotations.Nullable;

import javax.swing.AbstractAction;
import javax.swing.Action;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Every switch in the program, once: the eighteen ticks and the eight choices the menus offer.
 * <p>
 * They are here because the menu bar is about to stop being the only place they are shown. Until
 * now the menu item <em>was</em> the state -- {@code startMachine} replayed a new machine out of
 * {@code isSelected()} and {@code updateMovieItems} greyed a submenu by name -- so a second place
 * showing the same switch would have meant a second set of listeners keeping a second set of ticks
 * in step, which is a bug waiting for the day somebody adds a third way to reach one. A
 * {@link javax.swing.Action} is Swing's own answer: a menu item and a checkbox built from one share
 * its label, its state and its enabledness, and neither of them holds any.
 * <p>
 * <b>Nothing in here knows what a switch is for.</b> A {@link Toggle} carries a label and a
 * boolean; what happens when it moves is attached by whoever owns the machine, which is also the
 * only thing that knows a switch is remembered between runs. So a {@code Switches} with nothing
 * attached is a complete and harmless set of switches, which is what a test wants and what the
 * README's camera wants over a machine nobody is running.
 * <p>
 * Only switches and choices are here. A command -- Reset, Power Cycle, Game Genie... -- has no
 * state for two places to disagree about. The one thing it does share is whether it can be used,
 * which an Action carries as readily, so they will arrive here the day something other than the
 * menu bar shows one.
 */
public final class Switches {

    // ==================================================================================== ticks

    private final Toggle pause =
            new Toggle("Pause", KeyEvent.VK_P, shortcut(KeyEvent.VK_P), false);

    /**
     * No accelerator: it is a habit somebody picks once, not something reached for mid-game.
     */
    private final Toggle pauseInBackground =
            new Toggle("Pause in Background", KeyEvent.VK_B, null, false);

    private final Toggle fastForward =
            new Toggle("Fast Forward", KeyEvent.VK_F, shortcut(KeyEvent.VK_F), false);

    /**
     * No accelerator either, for a different reason. Command-M is the window manager's, and picking
     * another letter for it would mean picking one that is somewhere sensible on every keyboard
     * layout, which is not a promise this program can make.
     */
    private final Toggle mute = new Toggle("Mute", KeyEvent.VK_M, null, false);

    /**
     * The two layers and the five voices start on, because a machine draws and plays everything
     * until somebody asks it not to. They are the only switches whose default is not off, and the
     * reason is the same for all seven: what they take away is the machine's own behaviour.
     */
    private final Toggle background = new Toggle("Show Background", KeyEvent.VK_B, null, true);

    private final Toggle sprites = new Toggle("Show Sprites", KeyEvent.VK_S, null, true);

    private final Map<APUChannel, Toggle> channels = new EnumMap<>(APUChannel.class);

    private final Toggle unlimitedSprites =
            new Toggle("Unlimited Sprites", KeyEvent.VK_U, null, false);

    private final Toggle warp =
            new Toggle("Curved Glass", KeyEvent.VK_UNDEFINED, null, false);

    private final Toggle overscan = new Toggle("Show Overscan", KeyEvent.VK_O, null, false);

    private final Toggle leftEdge = new Toggle("Show Left Edge", KeyEvent.VK_L, null, false);

    private final Toggle tvAspect = new Toggle("TV Aspect Ratio", KeyEvent.VK_T, null, false);

    /**
     * F11, unmodified, which is what every browser and every emulator since ZSNES has used -- and
     * has to be unmodified here, Shift being Select.
     */
    private final Toggle fullScreen = new Toggle(
            "Full Screen", KeyEvent.VK_F, KeyStroke.getKeyStroke(KeyEvent.VK_F11, 0), false);

    private final Toggle statusBar = new Toggle("Status Bar", KeyEvent.VK_B, null, false);

    // ================================================================================== choices

    private final Choice<RegionSetting> region =
            new Choice<>(List.of(RegionSetting.values()), RegionSetting::label);

    private final Choice<EmulationSpeed> fastForwardSpeed =
            new Choice<>(EmulationSpeed.fastForwardChoices(), EmulationSpeed::label);

    private final Choice<Volume> volume = new Choice<>(List.of(Volume.values()), Volume::label);

    private final Choice<OverclockSetting> overclock =
            new Choice<>(List.of(OverclockSetting.values()), OverclockSetting::label);

    private final Choice<VideoFilter> videoFilter =
            new Choice<>(List.of(VideoFilter.values()), VideoFilter::label);

    private final Choice<FilterStrength> filterStrength =
            new Choice<>(List.of(FilterStrength.values()), FilterStrength::label);

    private final Choice<ScreenScale> screenSize =
            new Choice<>(List.of(ScreenScale.values()), ScreenScale::label);

    private final Choice<ScreenScale> screenshotSize =
            new Choice<>(List.of(ScreenScale.values()), ScreenScale::label);

    public Switches() {
        for (var channel : APUChannel.values()) {
            channels.put(channel, new Toggle(channel.label(), KeyEvent.VK_UNDEFINED, null, true));
        }
    }

    public Toggle pause() {
        return pause;
    }

    public Toggle pauseInBackground() {
        return pauseInBackground;
    }

    public Toggle fastForward() {
        return fastForward;
    }

    public Toggle mute() {
        return mute;
    }

    public Toggle background() {
        return background;
    }

    public Toggle sprites() {
        return sprites;
    }

    public Toggle channel(final APUChannel channel) {
        return channels.get(channel);
    }

    public Toggle unlimitedSprites() {
        return unlimitedSprites;
    }

    public Toggle warp() {
        return warp;
    }

    public Toggle overscan() {
        return overscan;
    }

    public Toggle leftEdge() {
        return leftEdge;
    }

    public Toggle tvAspect() {
        return tvAspect;
    }

    public Toggle fullScreen() {
        return fullScreen;
    }

    public Toggle statusBar() {
        return statusBar;
    }

    public Choice<RegionSetting> region() {
        return region;
    }

    public Choice<EmulationSpeed> fastForwardSpeed() {
        return fastForwardSpeed;
    }

    public Choice<Volume> volume() {
        return volume;
    }

    public Choice<OverclockSetting> overclock() {
        return overclock;
    }

    public Choice<VideoFilter> videoFilter() {
        return videoFilter;
    }

    public Choice<FilterStrength> filterStrength() {
        return filterStrength;
    }

    public Choice<ScreenScale> screenSize() {
        return screenSize;
    }

    public Choice<ScreenScale> screenshotSize() {
        return screenshotSize;
    }

    private static KeyStroke shortcut(final int key) {
        return KeyStroke.getKeyStroke(key, MenuKey.mask());
    }

    /**
     * One switch, wherever it is drawn.
     * <p>
     * The tick lives in {@code SELECTED_KEY}, which is where Swing looks for it: a
     * {@link javax.swing.JCheckBoxMenuItem} and a {@link javax.swing.JCheckBox} built from the same
     * Toggle show the same tick, and moving either moves the other. Which is also why
     * {@link #set(boolean)} and a click are different things -- see there.
     */
    public static final class Toggle extends AbstractAction {
        private @Nullable Consumer<Boolean> onChange;

        private Toggle(
                final String label,
                final int mnemonic,
                final @Nullable KeyStroke accelerator,
                final boolean on) {

            super(label);

            if (mnemonic != KeyEvent.VK_UNDEFINED) {
                putValue(MNEMONIC_KEY, mnemonic);
            }

            if (accelerator != null) {
                putValue(ACCELERATOR_KEY, accelerator);
            }

            putValue(SELECTED_KEY, on);
        }

        /**
         * What to do when somebody moves it. One listener, not a list: a switch that two things
         * acted on would be two things to find when it turns out to do the wrong one.
         */
        public Toggle onChange(final Consumer<Boolean> listener) {
            onChange = listener;
            return this;
        }

        public boolean isOn() {
            return Boolean.TRUE.equals(getValue(SELECTED_KEY));
        }

        /**
         * Moves the tick without telling anybody, which is how a switch follows a machine rather
         * than driving it: a new machine is running and not fast forwarding, and both ticks have to
         * say so without either of them being taken for somebody asking for it.
         */
        public void set(final boolean on) {
            putValue(SELECTED_KEY, on);
        }

        /**
         * Moves the tick and says so, which is what a click does.
         * <p>
         * For an accelerator bound straight on a window rather than on a menu item, where there is
         * no button in the way to flip the tick first. {@code doClick} on a control somewhere in
         * the window would do both, and holds the event dispatch thread for the length of the
         * keypress it is pretending to make -- which is a frame and a bit of a game not being drawn.
         */
        public void press() {
            set(!isOn());
            fire();
        }

        @Override
        public void actionPerformed(final ActionEvent e) {
            fire();
        }

        private void fire() {
            if (onChange != null) {
                onChange.accept(isOn());
            }
        }
    }

    /**
     * One choice out of a handful, wherever it is drawn.
     * <p>
     * An Action per option, and the value here rather than in any of them, which is what a
     * {@link javax.swing.ButtonGroup} cannot do: a group keeps one set of buttons honest, and there
     * are about to be two sets showing the same choice. So the buttons in each place are grouped
     * for the look of it and this is what they agree through.
     */
    public static final class Choice<E> {
        private final List<E> values;
        private final Map<E, Toggle> options = new LinkedHashMap<>();
        private final List<JComponent> holders = new ArrayList<>();

        private E chosen;
        private boolean enabled = true;
        private @Nullable Consumer<E> onChange;

        private Choice(final List<E> values, final Function<E, String> label) {
            this.values = List.copyOf(values);
            this.chosen = this.values.getFirst();

            for (var value : this.values) {
                var option = new Toggle(
                        label.apply(value), KeyEvent.VK_UNDEFINED, null, value == chosen);

                // Nothing ever arrives false: a button group turns the old option off without
                // firing anything, and the tick that did move is the one that was clicked. Clicking
                // the option already chosen arrives true again and lands on choose's own guard.
                option.onChange(on -> {
                    if (on) {
                        choose(value);
                    }
                });

                options.put(value, option);
            }
        }

        /**
         * What to do when somebody picks a different one.
         */
        public Choice<E> onChange(final Consumer<E> listener) {
            onChange = listener;
            return this;
        }

        public E get() {
            return chosen;
        }

        public List<E> values() {
            return values;
        }

        /**
         * The Action for one option, which is what a menu item or a radio button is built from.
         * Handed out as an {@link Action} rather than as the {@link Toggle} it is, because a caller
         * that gave one a listener of its own would take the one holding this choice together.
         */
        public Action option(final E value) {
            return options.get(value);
        }

        /**
         * Moves the dot without telling anybody -- the {@link Toggle#set} rule, for the same
         * reason: this is how a choice is seeded from what was remembered of the last run.
         */
        public void select(final E value) {
            chosen = value;

            options.forEach((option, action) -> action.set(option == value));
        }

        /**
         * Picks one and says so, which is what a click does and what Louder and Quieter do -- one
         * path, so that a volume reached with a keystroke ticks itself the same way one reached
         * with the mouse does.
         * <p>
         * Picking the option already picked still counts, because whether that means anything
         * depends on what the choice is for and is not this class's to decide: clicking the size a
         * maximized window is already at is how it is un-maximized, and clicking the region a
         * machine is already running is nothing at all -- so that one guards for itself.
         */
        public void choose(final E value) {
            select(value);

            if (onChange != null) {
                onChange.accept(value);
            }
        }

        /**
         * Greys the whole choice, and whatever is showing it.
         */
        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;

            options.values().forEach(option -> option.setEnabled(enabled));
            holders.forEach(holder -> holder.setEnabled(enabled));
        }

        /**
         * Greys one option, leaving the rest. The NTSC decoder on a PAL machine is the only one:
         * the tick stays where it is, because the setting is somebody's preference about American
         * cartridges and a European one should not take it away.
         */
        public void setEnabled(final E value, final boolean enabled) {
            options.get(value).setEnabled(enabled);
        }

        public boolean isEnabled() {
            return enabled;
        }

        /**
         * Says where these options are shown, so that it greys out with them. A submenu heading
         * left black over a list of items nobody can pick is a menu that opens onto nothing.
         */
        public void shownIn(final JComponent holder) {
            holders.add(holder);
            holder.setEnabled(enabled);
        }
    }
}
