package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.Condition;

/**
 * How an address typed into this window is read.
 * <p>
 * A bare number here is <em>hexadecimal</em>, unlike the REPL's, where it is decimal. Every address
 * in this window is written in hex, so one typed into it is meant in hex; somebody typing
 * {@code 6000} into a box beside a grid of hex means $6000, and being taken to $1770 instead would
 * be a small daily annoyance. A leading {@code $} or {@code 0x} is accepted for the people who type
 * one without thinking.
 * <p>
 * One class rather than the same eleven lines in each panel, because the memory view, the listing
 * and the points all take an address and a rule that lived in three places would drift.
 */
final class Addresses {
    /**
     * An address and, optionally, the condition typed after {@code if}. What the points panel's
     * entry field holds.
     */
    record Entry(int address, Condition condition) {
    }

    private Addresses() {
    }

    /**
     * @throws IllegalArgumentException with something worth showing when the word is not an
     *                                  address, because a silently ignored entry on a debugger is
     *                                  the worst possible answer.
     */
    static int parse(final String word) {
        var trimmed = word.trim();

        try {
            if (trimmed.startsWith("$")) {
                return Integer.parseInt(trimmed.substring(1), 16) & 0xFFFF;
            }

            if (trimmed.startsWith("0x") || trimmed.startsWith("0X")) {
                return Integer.parseInt(trimmed.substring(2), 16) & 0xFFFF;
            }

            return Integer.parseInt(trimmed, 16) & 0xFFFF;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("\"" + trimmed + "\" is not an address.");
        }
    }

    /**
     * Takes an address and, after {@code if}, a condition.
     * <p>
     * The condition keeps its own rule -- decimal unless it says otherwise -- because it is a little
     * expression rather than an address, and it is the same one the interactive session keeps.
     */
    static Entry parseEntry(final String text) {
        var words = text.trim().split("\\s+", 3);
        var address = parse(words[0]);

        if (words.length == 1) {
            return new Entry(address, null);
        }

        if (!words[1].equalsIgnoreCase("if") || words.length < 3) {
            throw new IllegalArgumentException("expected \"if\" and a condition after the address.");
        }

        return new Entry(address, Condition.parse(words[2]));
    }
}
