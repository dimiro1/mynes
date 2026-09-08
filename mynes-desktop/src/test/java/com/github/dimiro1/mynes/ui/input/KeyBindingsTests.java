package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.ui.input.KeyBindings.Button;
import com.github.dimiro1.mynes.ui.input.KeyBindings.Port;
import com.github.dimiro1.mynes.ui.input.KeyBindings.Press;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for remapping itself.
 * <p>
 * The bindings are one section of a file they do not own, so everything about reading and writing
 * that file -- including all the ways a hand-edited one can be wrong -- is in
 * {@code com.github.dimiro1.mynes.ui.ConfigTests}.
 */
class KeyBindingsTests {
    @Test
    void bindingAKeyTakesItOffTheButtonThatHadIt() {
        var bindings = KeyBindings.defaults().with(Port.ONE, Button.B, KeyEvent.VK_X);

        assertEquals(KeyEvent.VK_X, bindings.keyFor(Port.ONE, Button.B));
        assertEquals(KeyBindings.UNBOUND, bindings.keyFor(Port.ONE, Button.A), "A gave up X");
        assertEquals(new Press(Port.ONE, Button.B), bindings.pressFor(KeyEvent.VK_X));
    }

    /**
     * The same theft across the two pads, which is the half that matters most: a key on both would
     * walk both players at once, and there is no game in which that is what somebody meant.
     */
    @Test
    void bindingAKeyTakesItOffTheOtherPadToo() {
        var bindings = KeyBindings.defaults().with(Port.TWO, Button.A, KeyEvent.VK_X);

        assertEquals(KeyEvent.VK_X, bindings.keyFor(Port.TWO, Button.A));
        assertEquals(KeyBindings.UNBOUND, bindings.keyFor(Port.ONE, Button.A),
                "player one gave up X");
        assertEquals(new Press(Port.TWO, Button.A), bindings.pressFor(KeyEvent.VK_X));
    }

    /**
     * Player two arrives empty. Any default for it would be keys taken off somebody playing alone,
     * and there is nowhere to put them that is the same place on every keyboard.
     */
    @Test
    void playerTwoStartsWithNothingOnIt() {
        var defaults = KeyBindings.defaults();

        for (var button : Button.values()) {
            assertEquals(KeyBindings.UNBOUND, defaults.keyFor(Port.TWO, button), button.label());
        }
    }

    @Test
    void theOriginalIsUntouched() {
        var defaults = KeyBindings.defaults();

        defaults.with(Port.ONE, Button.A, KeyEvent.VK_L);
        defaults.with(Port.TWO, Button.A, KeyEvent.VK_G);

        assertEquals(KeyEvent.VK_X, defaults.keyFor(Port.ONE, Button.A));
        assertEquals(KeyBindings.UNBOUND, defaults.keyFor(Port.TWO, Button.A));
    }

    @Test
    void anUnboundKeyPressesNothing() {
        assertNull(KeyBindings.defaults().pressFor(KeyEvent.VK_F1));
        assertNull(KeyBindings.defaults().pressFor(KeyBindings.UNBOUND),
                "and neither does the code for no key at all");
    }
}
