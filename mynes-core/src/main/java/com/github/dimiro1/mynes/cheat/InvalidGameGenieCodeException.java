package com.github.dimiro1.mynes.cheat;

/**
 * Thrown for a string that is not a Game Genie code.
 * <p>
 * Unchecked, for the reason {@code InvalidPatchException} is: every caller is a front end that has
 * just been handed one by a person, and the only thing any of them can do about a bad one is say so
 * and carry on. The message is a whole sentence, meant to be printed on its own.
 */
public class InvalidGameGenieCodeException extends RuntimeException {
    /**
     * @param code    what was typed.
     * @param because what is wrong with it, as a clause: it is printed after a colon.
     */
    public InvalidGameGenieCodeException(final String code, final String because) {
        super("\"" + code + "\" is not a Game Genie code: " + because + ".");
    }
}
