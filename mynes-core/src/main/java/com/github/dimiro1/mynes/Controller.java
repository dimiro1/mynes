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
     * Which buttons are held down, as the eight {@code BUTTON_} flags.
     * <p>
     * What the front end last put in rather than what the game has read out: the shift register is
     * half way through being clocked most of the time, and "what is being held" is the question
     * anybody looking at a pad is asking.
     *
     * @return the button mask.
     */
    int getButtons();

    /**
     * How many times the game has latched this pad since the machine was switched on.
     * <p>
     * One per falling edge of the strobe, which is the moment the shift register stops following
     * the buttons and starts holding them -- so it counts <em>polls</em> rather than writes: a game
     * that writes $02 to work an expansion port has not asked this pad anything.
     * <p>
     * <b>Both pads answer with the same number</b>, because one write to $4016 drives the latch
     * line of both ports. What tells them apart is {@link #getBitsRead()}, since $4016 and $4017
     * are read separately -- a game with no two player mode latches this pad every frame and never
     * reads a bit out of it.
     * <p>
     * Instrumentation rather than machine state: nothing in the console can see it, it is not in a
     * save state, and a frame in which it does not move is a frame the game never looked at the pad
     * -- which is the honest measure of a main loop that overran its frame.
     *
     * @return the count since power on, which only differences are ever taken of.
     */
    long getPolls();

    /**
     * How many bits the game has clocked out of this port since the machine was switched on.
     * <p>
     * A poll is eight of them, so a game reading both pads once a frame comes to eight here and
     * eight on the other one. Sixteen is a game reading the pad twice and comparing the two, which
     * is the usual guard against a DMC fetch corrupting a read.
     * <p>
     * Bits rather than bus cycles, which is the same distinction {@link #peek()} draws: a read that
     * finds the strobe still low from the read before it clocks nothing and answers with the bit
     * that is already on the line, so it is one bit read twice rather than two.
     *
     * @return the count since power on, which only differences are ever taken of.
     */
    long getBitsRead();

    /**
     * Reads the next button state from the controller shift register.
     * Returns 1 if the button is pressed, 0 otherwise.
     *
     * @return the next button state (0 or 1)
     */
    int read();

    /**
     * The bit {@link #read()} would return, without clocking the shift register on.
     * <p>
     * For the second and later of a run of reads of the same port: the port clocks on the falling
     * edge of the read strobe, and back to back reads never let it rise.
     *
     * @return the current button state (0 or 1)
     */
    int peek();

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