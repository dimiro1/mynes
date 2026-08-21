/**
 * Freezing the machine to a file, and thawing it again.
 * <p>
 * Three different files live here, and they are not variations on one idea.
 * <p>
 * A <strong>save state</strong> ({@link com.github.dimiro1.mynes.state.SaveState}) is every field
 * in the console, this emulator's own format, loadable only by builds that still understand it.
 * There is no standard for it and no prospect of one.
 * <p>
 * A <strong>battery file</strong> ({@link com.github.dimiro1.mynes.state.BatteryRAM}) is the eight
 * kilobytes of cartridge RAM a real board kept alive with a coin cell, and it <em>is</em> standard:
 * raw bytes, no header, no version, the same {@code .sav} that FCEUX and Nestopia and Mesen read
 * and write. That interoperability is the entire specification, so nothing may be added to it --
 * not a magic number, not a checksum, however tempting.
 * <p>
 * A <strong>movie</strong> ({@link com.github.dimiro1.mynes.state.Movie}) is neither: it is a
 * session somebody played, kept as one button mask per frame rather than as any picture of the
 * machine. It can be that small only because the console is deterministic, which is a property of
 * the emulator rather than of the format -- so unlike the two above, a movie is a claim about how
 * this build behaves, and {@code MovieTests} is where the claim is checked.
 * <p>
 * The distinction is worth keeping in mind when something goes wrong with either. A save state is
 * a convenience, and losing one costs somebody a few minutes. A battery file is the player's
 * progress through the game, which is why it is the one written through a temporary and a move.
 * <p>
 * Nothing here knows about the front end. Both surfaces -- the window and the command line -- drive
 * the same two classes, and both hand in a {@link com.github.dimiro1.mynes.NES} they already have.
 */
package com.github.dimiro1.mynes.state;
