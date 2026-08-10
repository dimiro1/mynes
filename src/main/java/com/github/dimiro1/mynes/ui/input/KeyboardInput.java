package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.Controller;
import org.jetbrains.annotations.Nullable;

import javax.swing.MenuSelectionManager;
import java.awt.KeyEventDispatcher;
import java.awt.Window;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

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
     * Lets go of everything. Wired to the game window losing focus, so that cmd-tabbing away in
     * the middle of a jump does not leave the button held down for as long as the window is gone.
     */
    public void releaseAll() {
        pressed = 0;

        if (controller != null) {
            controller.setButtons(0);
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
            return false;
        }

        switch (e.getID()) {
            case KeyEvent.KEY_PRESSED -> {
                if ((e.getModifiersEx() & SHORTCUT_MODIFIERS) != 0) {
                    // Cmd-O stays Cmd-O even for someone who has put a button on that key.
                    return false;
                }

                // Setting a bit that is already set is what makes the key repeat a non-event.
                pressed |= button.mask();
            }
            // Releases are taken whatever else is held down, so a key let go of after reaching for
            // a modifier cannot leave its button stuck.
            case KeyEvent.KEY_RELEASED -> pressed &= ~button.mask();
            // KEY_TYPED carries a character and no key code.
            default -> {
                return false;
            }
        }

        target.setButtons(withoutOpposingDirections(pressed));

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
