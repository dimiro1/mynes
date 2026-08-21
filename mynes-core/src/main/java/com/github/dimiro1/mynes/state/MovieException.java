package com.github.dimiro1.mynes.state;

/**
 * A movie that cannot be played, and why.
 * <p>
 * The mirror of {@link SaveStateException}, and separate from it for the reason the two files are
 * separate: a state that will not load and a movie that will not play are different mistakes, and a
 * front end that wanted to answer only one of them could not tell them apart otherwise. The message
 * is shown on its own -- in a dialog, or on stderr with no stack trace in front of it -- so it has
 * to read as a sentence somebody can act on.
 */
public class MovieException extends RuntimeException {
    public MovieException(final String message) {
        super(message);
    }

    public MovieException(final String message, final Throwable cause) {
        super(message, cause);
    }
}
