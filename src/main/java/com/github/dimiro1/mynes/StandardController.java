package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.state.StateIO;

/**
 * Standard NES controller implementation.
 * Buttons are read in sequence: A, B, Select, Start, Up, Down, Left, Right
 * <p>
 * Two threads meet here. Keys go up and down on the event dispatch thread, which is the only
 * caller of {@link #setButtons(int)}; the CPU writes and reads $4016 on the emulation thread,
 * which is the only caller of {@link #setStrobe(int)} and {@link #read()}. So {@code buttons} is
 * the one field that crosses, and making it volatile is the whole of the synchronisation: the
 * shift register and the strobe stay confined to the emulation thread.
 * <p>
 * That confinement is why the reload happens on both strobe edges rather than on every change to
 * the button mask. It is also what the hardware does -- the shift register follows the buttons
 * while the strobe is high, so what a game ends up clocking out is the state at the moment the
 * strobe fell.
 */
public class StandardController implements Controller {
    private volatile int buttons;
    private int shiftRegister;
    private int strobe;

    /**
     * The bit the port is holding on its data line, which is what a read that does not clock the
     * register sees. Not the same as the bottom of the shift register: that has already moved on.
     */
    private int output;

    public StandardController() {
        this.buttons = 0;
        this.shiftRegister = 0;
        this.strobe = 0;
    }

    @Override
    public void setStrobe(int strobe) {
        var level = strobe & 1;

        // While the strobe is high the register simply follows the buttons, and the falling edge
        // is what latches them for the eight reads that follow. A write that leaves it low does
        // neither: $4016 carries three output lines and a game writing $02 to work an expansion
        // port must not find its controller reading back from the top again.
        if (level == 1 || this.strobe == 1) {
            reloadShiftRegister();
        }

        this.strobe = level;
    }

    @Override
    public int read() {
        if (strobe == 1) {
            // While strobe is high, always return A button state
            return buttons & BUTTON_A;
        }

        // Read current bit and shift
        int result = output = shiftRegister & 1;
        shiftRegister >>= 1;
        // Set bit 7 to 1 (open bus behavior after all 8 buttons are read)
        shiftRegister |= 0x80;

        return result;
    }

    @Override
    public int peek() {
        return strobe == 1 ? buttons & BUTTON_A : output;
    }

    @Override
    public void setButtons(int buttons) {
        this.buttons = buttons & 0xFF;
    }

    @Override
    public void serialize(final StateIO io) {
        shiftRegister = io.u8(shiftRegister);
        strobe = io.u8(strobe);
        output = io.u8(output);
    }

    private void reloadShiftRegister() {
        // Load buttons into shift register in the order they'll be read
        shiftRegister = buttons;
    }
}
