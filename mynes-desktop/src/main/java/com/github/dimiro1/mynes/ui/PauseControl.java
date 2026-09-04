package com.github.dimiro1.mynes.ui;

/**
 * A handle on whether the machine is running, for the windows that do not have the Machine menu in
 * them.
 * <p>
 * The debug windows are watching a machine that is moving, and the thing anybody watching one wants
 * most is for it to stop. Reaching the {@code EmulatorRunner} from a viewer would be the short way
 * to arrange that and the wrong one: pausing properly also means releasing whatever buttons were
 * held when the game froze, bringing the Machine menu's tick into line, and telling the debugger
 * that whatever it was waiting for is off. All of that lives in the one window that owns the
 * machine, so a viewer asks rather than does.
 * <p>
 * Never null -- {@link #NONE} is how to say that nobody is clocking the machine, which is the case
 * a test and the README's camera are in.
 */
public interface PauseControl {
    /**
     * A control over a machine nothing is running, which cannot be paused because it is not going.
     * The windows that are handed this draw their Pause tick greyed out, which is the truth about
     * them.
     */
    PauseControl NONE = new PauseControl() {
        @Override
        public boolean isPaused() {
            return false;
        }

        @Override
        public void setPaused(final boolean paused) {
        }

        @Override
        public boolean isReal() {
            return false;
        }
    };

    boolean isPaused();

    /**
     * Stops the machine where it is, or lets it go again. Called on the event dispatch thread.
     */
    void setPaused(boolean paused);

    /**
     * Whether there is a machine behind this at all. Only {@link #NONE} says no.
     */
    default boolean isReal() {
        return true;
    }
}
