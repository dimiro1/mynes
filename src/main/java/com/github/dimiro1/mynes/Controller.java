package com.github.dimiro1.mynes;

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
}