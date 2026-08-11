package com.github.dimiro1.mynes.headless;

/**
 * Something on the command line was wrong.
 * <p>
 * The message is printed on its own, with no stack trace and no class name in front of it, so it
 * has to read as a sentence somebody can act on: which flag, what was wrong with it, and where the
 * allowed answers are.
 */
public class UsageException extends RuntimeException {
    public UsageException(final String message) {
        super(message);
    }
}
