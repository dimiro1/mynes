package com.github.dimiro1.mynes;

/**
 * Standard NES controller implementation.
 * Buttons are read in sequence: A, B, Select, Start, Up, Down, Left, Right
 */
public class StandardController implements Controller {
    private int buttons;
    private int shiftRegister;
    private int strobe;

    public StandardController() {
        this.buttons = 0;
        this.shiftRegister = 0;
        this.strobe = 0;
    }

    @Override
    public void setStrobe(int strobe) {
        this.strobe = strobe & 1;
        if (this.strobe == 1) {
            // When strobe is high, continuously reload the button states
            reloadShiftRegister();
        }
    }

    @Override
    public int read() {
        if (strobe == 1) {
            // While strobe is high, always return A button state
            return buttons & BUTTON_A;
        }

        // Read current bit and shift
        int result = shiftRegister & 1;
        shiftRegister >>= 1;
        // Set bit 7 to 1 (open bus behavior after all 8 buttons are read)
        shiftRegister |= 0x80;

        return result;
    }

    @Override
    public void setButtons(int buttons) {
        this.buttons = buttons & 0xFF;
        if (strobe == 1) {
            reloadShiftRegister();
        }
    }

    private void reloadShiftRegister() {
        // Load buttons into shift register in the order they'll be read
        shiftRegister = buttons;
    }
}