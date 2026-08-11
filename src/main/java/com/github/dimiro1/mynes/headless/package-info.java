/**
 * The emulator with nobody watching it: a way to run a cartridge from a command line, with no
 * window and no sound card, and get back a description of what happened.
 * <p>
 * It exists because the alternative kept being rebuilt. Every time somebody needed to know what the
 * emulator actually does -- which frame a title screen lands on, whether a game makes any sound,
 * whether a mapper change broke the picture -- the answer was a throwaway program that reached into
 * the window's private fields and photographed the screen. Those programs did not survive their own
 * week, and none of them could run anywhere without a display.
 * <p>
 * <b>One shot by default.</b> A run starts at power on, plays a written down sequence of button
 * presses, writes its artifacts and exits. That works because nothing in the machine reads a clock
 * or a random number -- decay is counted in frames and in dots -- so the same ROM and the same
 * input give the same answer every time, on every computer. Restarting is free, which is why there
 * is no session to keep alive and no protocol to design. {@link
 * com.github.dimiro1.mynes.headless.Repl} is there for the times when the question is not yet known
 * well enough to write down.
 * <p>
 * <b>Nothing here touches {@code mynes.ui}, except its palettes.</b> Three separate things over
 * there would make a run depend on the computer it ran on: the look and feel loads a native
 * library, {@link com.github.dimiro1.mynes.ui.AudioOutput} opens a mixer, and
 * {@link com.github.dimiro1.mynes.ui.Config} reads whatever is in the user's home directory. A
 * headless run reads none of them -- in particular it never reads the config file, so a palette
 * chosen in the menus cannot quietly change what a screenshot looks like.
 */
package com.github.dimiro1.mynes.headless;
