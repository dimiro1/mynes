package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.Controller;
import org.jetbrains.annotations.Nullable;

import javax.swing.MenuSelectionManager;
import java.awt.KeyEventDispatcher;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * The keyboard, wired to player one's controller.
 * <p>
 * This sits on the {@link java.awt.KeyboardFocusManager} rather than on the screen component,
 * because the screen component is not focusable and the frame carries a menu bar that wants the
 * arrow keys for itself. A dispatcher sees every key event in the application before anything else
 * does, which is the position needed to answer both questions here -- is this keystroke the game's
 * at all, and which button is it -- for arbitrary rebindable keys, which an InputMap of press and
 * release pairs would make hard work of.
 * <p>
 * Everything below runs on the event dispatch thread: key events arrive on it, and the frame calls
 * {@link #setController(Controller)}, {@link #setBindings(KeyBindings)} and {@link #releaseAll()}
 * from it. So none of the state here is shared, and the only thing that crosses to the emulation
 * thread is the button mask handed to {@link Controller#setButtons(int)}, which is that class's
 * problem.
 * <p>
 * Two exceptions to that, and both belong to movies. {@link #heldMask()} is read from the emulation
 * thread once a frame, which is why the mask has a field of its own and is {@code volatile}. And
 * {@link #setLatching(boolean)} switches off the immediate hand-off above: while a movie is being
 * recorded or played, what the game sees has to change exactly once per frame, on the thread that
 * clocks it -- a press that landed half way through a frame would be written down as belonging to a
 * frame it was only half of, and a replay of it would be a different game. When neither is
 * happening the immediate path is left exactly as it was, because that is the one a player feels.
 */
public final class KeyboardInput implements KeyEventDispatcher {
    /**
     * Modifiers that mean the keystroke belongs to a menu shortcut. Shift is deliberately not one
     * of them: Select is bound to it by default, and games use it while playing.
     */
    private static final int SHORTCUT_MODIFIERS =
            InputEvent.CTRL_DOWN_MASK | InputEvent.META_DOWN_MASK | InputEvent.ALT_DOWN_MASK;

    private static final int LEFT_AND_RIGHT = Controller.BUTTON_LEFT | Controller.BUTTON_RIGHT;
    private static final int UP_AND_DOWN = Controller.BUTTON_UP | Controller.BUTTON_DOWN;

    private final Window gameWindow;

    private KeyBindings bindings;
    private @Nullable Controller controller;

    /**
     * The keys held down, before the opposing directions are taken out. Kept raw so that letting
     * go of one of two opposing directions leaves the other one pressed.
     */
    private int pressed;

    /**
     * What {@link #pressed} comes to once the opposing directions are taken out, which is the mask
     * the game actually sees.
     * <p>
     * A field rather than a local because the emulation thread reads it once a frame while a movie
     * is being recorded. {@code volatile} for that one reader; every writer is the event dispatch
     * thread.
     */
    private volatile int mask;

    /**
     * Whether the emulation thread is latching the mask itself, once a frame, instead of taking it
     * from here the moment a key moves. True exactly while a movie is being recorded or played.
     */
    private boolean latching;

    /**
     * Whether the keyboard is being kept away from the game entirely, which is what a replay wants:
     * a bumped key must not reach a machine that is playing somebody else's session back.
     * <p>
     * Not the same thing as {@link #latching}. A recording wants the keys -- they are what is being
     * recorded -- and only wants them delivered on a frame boundary.
     */
    private boolean playbackMuted;

    /**
     * The key that runs the game backwards while it is held, or {@link KeyBindings#UNBOUND}.
     * <p>
     * Beside the bindings rather than in them, because it is not a button: the controller port has
     * no wire for it and no game can be told it was pressed. What it drives is the loop that clocks
     * the machine, which is why it goes somewhere else entirely.
     */
    private int rewindKey = KeyBindings.UNBOUND;

    /**
     * Where "the rewind key is down" is sent, which is the emulation thread's own switch. Null
     * whenever there is no machine to rewind.
     */
    private @Nullable Consumer<Boolean> rewind;

    /**
     * Whether {@link #rewind} was last told true. The guard on a key that auto-repeats, and the
     * thing that lets {@link #releaseAll()} know whether it has anything to let go of.
     */
    private boolean rewinding;

    public KeyboardInput(final Window gameWindow, final KeyBindings bindings) {
        this.gameWindow = gameWindow;
        this.bindings = bindings;
    }

    /**
     * Points the keyboard at a controller, or at nothing when {@code controller} is null. Called
     * every time a ROM is loaded, since each machine brings its own controllers.
     */
    public void setController(final @Nullable Controller controller) {
        this.controller = controller;
    }

    /**
     * Takes a new set of bindings, from the settings dialog. Held keys are dropped: a button whose
     * key just moved would otherwise never see the release that clears it.
     */
    public void setBindings(final KeyBindings bindings) {
        this.bindings = bindings;
        releaseAll();
    }

    /**
     * Which key runs the game backwards. Read from the config file once at startup, since there is
     * no dialog that can change it.
     */
    public void setRewindKey(final int keyCode) {
        rewindKey = keyCode;
        releaseAll();
    }

    /**
     * Points rewind at a machine's emulation loop, or at nothing when {@code rewind} is null. Called
     * every time a ROM is loaded, since each machine brings its own loop and its own history.
     */
    public void setRewind(final @Nullable Consumer<Boolean> rewind) {
        releaseAll();
        this.rewind = rewind;
    }

    /**
     * What the player is holding down right now, ready for the emulation thread to latch at a frame
     * boundary. The one thing here that another thread may call.
     */
    public int heldMask() {
        return mask;
    }

    /**
     * Hands the timing of the pad over to the emulation thread, or takes it back.
     * <p>
     * Taking it back pushes whatever is held down straight away, because the last thing the game was
     * told is whatever the last latch happened to catch -- and a button that stuck down when a
     * recording stopped would be a button held for as long as the game ran.
     */
    public void setLatching(final boolean latching) {
        this.latching = latching;

        if (!latching && controller != null) {
            controller.setButtons(mask);
        }
    }

    /**
     * Keeps the keyboard away from the game, which is what a replay wants. Rewind still works: it is
     * not a button, and it is the gesture that stops a playback.
     * <p>
     * The buttons are dropped either way, since neither entering nor leaving a replay should leave
     * one held. Deliberately not {@link #releaseAll()}, which would let go of rewind as well -- and
     * a playback is most often ended by the rewind key, which is still down at the moment this is
     * called.
     */
    public void setPlaybackMuted(final boolean muted) {
        playbackMuted = muted;
        pressed = 0;
        mask = 0;

        if (controller != null) {
            controller.setButtons(0);
        }
    }

    /**
     * Lets go of everything. Wired to the game window losing focus, so that cmd-tabbing away in
     * the middle of a jump does not leave the button held down for as long as the window is gone.
     * <p>
     * Rewind goes with the buttons, and for a sharper version of the same reason: a held button
     * costs a life, where a rewind key stuck down empties the whole history and leaves the game
     * sitting half a minute in the past.
     */
    public void releaseAll() {
        pressed = 0;
        mask = 0;

        if (controller != null) {
            controller.setButtons(0);
        }

        if (rewinding) {
            rewinding = false;

            if (rewind != null) {
                rewind.accept(false);
            }
        }
    }

    @Override
    public boolean dispatchKeyEvent(final KeyEvent e) {
        var target = controller;

        if (target == null || !gameWindow.isActive()) {
            // Either nothing is running or the keystroke belongs to another window. The second
            // half is also what keeps the settings dialog, the file chooser and the CHR viewer
            // from playing the game while they are up.
            return false;
        }

        if (MenuSelectionManager.defaultManager().getSelectedPath().length != 0) {
            // A menu is open and the arrow keys are walking it.
            return false;
        }

        var button = bindings.buttonFor(e.getKeyCode());
        if (button == null) {
            // Asked second, so a key somebody has put a controller button on stays that button.
            // Rewind is the emulator's key rather than the game's, and the game wins.
            return dispatchRewind(e);
        }

        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> {
                if ((e.getModifiersEx() & SHORTCUT_MODIFIERS) != 0) {
                    // Cmd-O stays Cmd-O even for someone who has put a button on that key.
                    return false;
                }

                if (playbackMuted) {
                    // Swallowed rather than merely not passed on: the whole point of a replay is
                    // that it is the recorded session and nothing else, and a key with a menu item
                    // on it as well would otherwise still act.
                    return true;
                }

                // Setting a bit that is already set is what makes the key repeat a non-event.
                pressed |= button.mask();
            }
            // Releases are taken whatever else is held down, so a key let go of after reaching for
            // a modifier cannot leave its button stuck.
            case KeyEvent.KEY_RELEASED -> {
                if (playbackMuted) {
                    return true;
                }

                pressed &= ~button.mask();
            }
            // KEY_TYPED carries a character and no key code.
            default -> {
                return false;
            }
        }

        mask = withoutOpposingDirections(pressed);

        // Left to the emulation thread while a movie is involved, which is the whole of the
        // difference: it takes this same mask at the next frame boundary instead.
        if (!latching) {
            target.setButtons(mask);
        }

        return true;
    }

    /**
     * The rewind key, which is held down rather than pressed.
     * <p>
     * Told only on the edges. The key repeats while it is down and the switch on the far side is a
     * {@code volatile boolean}, so the repeats would be harmless -- but the flag has to be kept
     * anyway for {@link #releaseAll()}, and once it is kept there is nothing to gain from telling
     * the emulation thread the same thing thirty times a second.
     *
     * @return whether the keystroke was rewind's, and so must go no further.
     */
    private boolean dispatchRewind(final KeyEvent e) {
        var sink = rewind;

        if (sink == null || rewindKey == KeyBindings.UNBOUND || e.getKeyCode() != rewindKey) {
            return false;
        }

        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> {
                if ((e.getModifiersEx() & SHORTCUT_MODIFIERS) != 0) {
                    // Cmd-Backspace stays Cmd-Backspace, the same as it would for a button.
                    return false;
                }

                if (!rewinding) {
                    rewinding = true;
                    sink.accept(true);
                }
            }
            // Taken whatever else is held down, so reaching for Fast Forward mid-rewind -- which is
            // how the game runs backwards at speed -- cannot leave the key stuck on the way out.
            case KeyEvent.KEY_RELEASED -> {
                if (rewinding) {
                    rewinding = false;
                    sink.accept(false);
                }
            }
            // KEY_TYPED carries a character and no key code.
            default -> {
                return false;
            }
        }

        return true;
    }

    /**
     * Drops both of a pair of opposing directions when both are held.
     * <p>
     * A d-pad cannot physically do left and right at once, and games that were written knowing
     * that do strange things when it happens -- walking through walls in the good cases. Filtering
     * it belongs here rather than in {@link com.github.dimiro1.mynes.StandardController}, which
     * models a chip that would happily report it.
     */
    private static int withoutOpposingDirections(final int mask) {
        var filtered = mask;

        if ((filtered & LEFT_AND_RIGHT) == LEFT_AND_RIGHT) {
            filtered &= ~LEFT_AND_RIGHT;
        }

        if ((filtered & UP_AND_DOWN) == UP_AND_DOWN) {
            filtered &= ~UP_AND_DOWN;
        }

        return filtered;
    }
}
