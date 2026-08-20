package com.github.dimiro1.mynes.cheat;

import java.util.Locale;

/**
 * One Game Genie code, decoded.
 * <p>
 * Six letters or eight, from an alphabet of sixteen: {@code APZLGITYEOXUKSVN}, which are the nibbles
 * $0 to $F in that order. The letters were chosen so that a code could be read out over a playground
 * or a telephone without being misheard, which is also why there is no B, C, D or R in them.
 * <p>
 * What the nibbles hold is scrambled rather than laid out in order, and the scramble is the same for
 * both lengths as far as the address goes:
 * <pre>
 * address = $8000 + ((n3 &amp; 7) &lt;&lt; 12) | ((n5 &amp; 7) &lt;&lt; 8) | ((n4 &amp; 8) &lt;&lt; 8)
 *                 | ((n2 &amp; 7) &lt;&lt; 4)  | ((n1 &amp; 8) &lt;&lt; 4) | (n4 &amp; 7) | (n3 &amp; 8)
 * </pre>
 * The $8000 is not an offset anybody chose: the device is a pass-through on the cartridge port and
 * only sees /ROMSEL, so fifteen address bits is all there is to encode and PRG ROM is all a code can
 * ever reach. Cartridge RAM at $6000 is not addressable by one, and neither is anything below it.
 * <p>
 * <strong>Eight letters carry a compare byte, and that is what they are for.</strong> Six letters
 * replace whatever the cartridge answers with at that address; eight replace it only when the
 * cartridge answered with {@link #compare()}. On a game with no bank switching the two are the same
 * thing, and on one with bank switching only the second is any use -- $8000 is a different byte in
 * every bank, and a code that fired in all of them would corrupt the ones it was not written for.
 * <p>
 * One bit of every code is read by nothing at all: six letters is 24 bits carrying a 15 bit address
 * and an 8 bit value, and eight is 32 bits carrying those plus an 8 bit compare, so bit 3 of
 * {@code n2} is spare either way. It is not a length flag and it is not a checksum -- published codes
 * have it both ways -- so nothing here looks at it, and there is deliberately no {@code encode}: the
 * letters cannot be recovered from a decoded code, and {@code GOSSIP} would come back as
 * {@code GOISIP}. {@link #text()} is what was typed, kept for whoever has to print it again.
 *
 * @param text    the code as it was typed, upper cased.
 * @param address where on the CPU bus it fires, always $8000 to $FFFF.
 * @param value   what the console reads there instead.
 * @param compare what the cartridge must have answered with, or {@link #NO_COMPARE}.
 * @see <a href="https://www.nesdev.org/wiki/Game_Genie">NESdev: Game Genie</a>
 * @see <a href="https://tuxnes.sourceforge.net/gamegenie.html">TuxNES: Game Genie technical notes</a>
 */
public record GameGenieCode(String text, int address, int value, int compare) {

    /**
     * What {@link #compare()} answers for a six letter code, which is pinned to no bank.
     */
    public static final int NO_COMPARE = -1;

    /**
     * The sixteen letters, in nibble order: {@code A} is $0 and {@code N} is $F.
     */
    private static final String ALPHABET = "APZLGITYEOXUKSVN";

    private static final int SHORT_CODE = 6;
    private static final int LONG_CODE = 8;

    /**
     * Reads a code.
     * <p>
     * Case does not matter going in and the {@link #text()} that comes back is upper case, so that two
     * spellings of one code are one code: the front ends hold these in lists and take them out again
     * by equality, and {@code sxiopo} has to find the {@code SXIOPO} that is already there.
     *
     * @throws InvalidGameGenieCodeException if it is the wrong length, or holds a letter that is not
     *                                       one of the sixteen.
     */
    public static GameGenieCode decode(final String code) {
        if (code == null) {
            throw new InvalidGameGenieCodeException("", "there is nothing there");
        }

        var text = code.trim().toUpperCase(Locale.ROOT);

        if (text.length() != SHORT_CODE && text.length() != LONG_CODE) {
            throw new InvalidGameGenieCodeException(
                    code, "a code is six letters or eight, and this is " + text.length());
        }

        var n = new int[text.length()];

        for (var i = 0; i < text.length(); i++) {
            n[i] = ALPHABET.indexOf(text.charAt(i));

            if (n[i] < 0) {
                throw new InvalidGameGenieCodeException(
                        code, text.charAt(i) + " is not one of its sixteen letters, which are "
                                + ALPHABET);
            }
        }

        var address = 0x8000
                | ((n[3] & 7) << 12) | ((n[5] & 7) << 8) | ((n[4] & 8) << 8)
                | ((n[2] & 7) << 4) | ((n[1] & 8) << 4) | (n[4] & 7) | (n[3] & 8);

        // The top bit of the value comes from the last letter either way -- n5 on a short code and n7
        // on a long one -- which is why the two lengths cannot share one expression for it.
        if (text.length() == SHORT_CODE) {
            var value = ((n[1] & 7) << 4) | ((n[0] & 8) << 4) | (n[0] & 7) | (n[5] & 8);

            return new GameGenieCode(text, address, value, NO_COMPARE);
        }

        var value = ((n[1] & 7) << 4) | ((n[0] & 8) << 4) | (n[0] & 7) | (n[7] & 8);
        var compare = ((n[7] & 7) << 4) | ((n[6] & 8) << 4) | (n[6] & 7) | (n[5] & 8);

        return new GameGenieCode(text, address, value, compare);
    }

    /**
     * Whether this code fires only on one byte, which is what makes it safe on a banked game.
     */
    public boolean hasCompare() {
        return compare != NO_COMPARE;
    }

    /**
     * What the console reads at {@link #address()} when the cartridge answered with
     * {@code fromTheCartridge}.
     * <p>
     * The cartridge's own byte, unchanged, when this is a compare code and the comparison fails. The
     * caller has already decided the address matches.
     */
    public int substitute(final int fromTheCartridge) {
        return compare == NO_COMPARE || compare == fromTheCartridge ? value : fromTheCartridge;
    }

    /**
     * The code and what it does, for a list somebody has to read: {@code SXIOPO -> $91D9 = $AD}, and
     * with {@code if $CE} on the end when it is a compare code.
     */
    @Override
    public String toString() {
        return String.format(
                hasCompare() ? "%s -> $%04X = $%02X if $%02X" : "%s -> $%04X = $%02X",
                text, address, value, compare);
    }
}
