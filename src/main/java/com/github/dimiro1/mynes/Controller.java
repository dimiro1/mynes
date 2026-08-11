package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Represents a NES controller/joypad.
 */
public interface Controller {
    /**
     * Button flags for standard NES controller.
     */
    int BUTTON_A = 0x01;
    int BUTTON_B = 0x02;
    int BUTTON_SELECT = 0x04;
    int BUTTON_START = 0x08;
    int BUTTON_UP = 0x10;
    int BUTTON_DOWN = 0x20;
    int BUTTON_LEFT = 0x40;
    int BUTTON_RIGHT = 0x80;

    /**
     * Sets the strobe state. When strobe is set to 1, the controller reloads
     * the current button states. When set to 0, it shifts out button states
     * one at a time on each read.
     *
     * @param strobe the strobe state (0 or 1)
     */
    void setStrobe(int strobe);

    /**
     * Reads the next button state from the controller shift register.
     * Returns 1 if the button is pressed, 0 otherwise.
     *
     * @return the next button state (0 or 1)
     */
    int read();

    /**
     * Sets the state of the controller buttons.
     *
     * @param buttons bitmask of pressed buttons
     */
    void setButtons(int buttons);

    /**
     * Reads or writes the chip, but not the hands holding it.
     * <p>
     * The shift register and the strobe belong to the machine and are saved. Which buttons are down
     * is not: a save state cannot restore somebody's fingers, and a machine that came back with A
     * held would never see it released, because the keyboard it is not being pressed on has no
     * release to send.
     */
    void serialize(StateIO io);
}