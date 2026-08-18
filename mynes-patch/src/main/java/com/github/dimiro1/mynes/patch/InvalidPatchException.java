package com.github.dimiro1.mynes.patch;

/**
 * Thrown for a file that is not an IPS patch, or is one that has been cut short.
 * <p>
 * Unchecked, because every caller is a front end that has just been handed a filename by a person,
 * and the only thing any of them can do about a bad one is say so and carry on with whatever was
 * already loaded. The message is a whole sentence, meant to be printed on its own.
 */
public class InvalidPatchException extends RuntimeException {
    /**
     * @param filename the patch that could not be read.
     * @param because  what is wrong with it, as a clause: it is printed after a colon.
     */
    public InvalidPatchException(final String filename, final String because) {
        super(filename + " is not a valid IPS patch: " + because + ".");
    }
}
