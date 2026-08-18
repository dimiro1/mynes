package com.github.dimiro1.mynes.state;

/**
 * A file that cannot be loaded, and why.
 * <p>
 * The message is shown on its own -- in a dialog, or on stderr with no stack trace in front of it
 * -- so it has to read as a sentence somebody can act on: what was wrong with the file, and where
 * that leaves them.
 */
public class SaveStateException extends RuntimeException {
    public SaveStateException(final String message) {
        super(message);
    }

    public SaveStateException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
