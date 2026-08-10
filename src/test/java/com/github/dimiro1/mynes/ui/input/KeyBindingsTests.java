package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.ui.input.KeyBindings.Button;
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
        var bindings = KeyBindings.defaults().with(Button.B, KeyEvent.VK_X);

        assertEquals(KeyEvent.VK_X, bindings.keyFor(Button.B));
        assertEquals(KeyBindings.UNBOUND, bindings.keyFor(Button.A), "A gave up X");
        assertEquals(Button.B, bindings.buttonFor(KeyEvent.VK_X));
    }

    @Test
    void theOriginalIsUntouched() {
        var defaults = KeyBindings.defaults();

        defaults.with(Button.A, KeyEvent.VK_L);

        assertEquals(KeyEvent.VK_X, defaults.keyFor(Button.A));
    }

    @Test
    void anUnboundKeyPressesNothing() {
        assertNull(KeyBindings.defaults().buttonFor(KeyEvent.VK_F1));
        assertNull(KeyBindings.defaults().buttonFor(KeyBindings.UNBOUND),
                "and neither does the code for no key at all");
    }
}
