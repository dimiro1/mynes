package com.github.dimiro1.mynes;

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

    public StandardController() {
        this.buttons = 0;
        this.shiftRegister = 0;
        this.strobe = 0;
    }

    @Override
    public void setStrobe(int strobe) {
        this.strobe = strobe & 1;
        // Both edges: high starts the register following the buttons, and the falling edge is
        // what latches them for the eight reads that follow.
        reloadShiftRegister();
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
    }

    private void reloadShiftRegister() {
        // Load buttons into shift register in the order they'll be read
        shiftRegister = buttons;
    }
}
