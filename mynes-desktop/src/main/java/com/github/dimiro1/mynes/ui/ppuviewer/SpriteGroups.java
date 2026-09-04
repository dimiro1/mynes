package com.github.dimiro1.mynes.ui.ppuviewer;

import com.github.dimiro1.mynes.PPU;

import java.util.Arrays;
import java.util.Comparator;

/**
 * A guess at which sprites are one thing.
 * <p>
 * The hardware has no idea. A NES sprite is eight pixels wide and almost nothing in a game is, so
 * everything a player would name -- Mario, a Hammer Brother, the word PAUSED -- is several of them
 * laid edge to edge, and which several is a fact about the game's code rather than about anything
 * in OAM. What is in OAM is where they all are, and sprites that make up one thing are touching.
 * So: <b>two sprites are together when their boxes touch, and a group is whatever that joins up
 * into.</b>
 * <p>
 * Touching on its own is not enough, though, because things on a NES screen overlap constantly:
 * Castlevania draws a candle straight through Simon, and that candle is as much inside his box as
 * his own head is. So the rule has a second half, and it is <b>either</b> of the two other things
 * OAM knows that track what a sprite is part of:
 * <ul>
 * <li><b>The palette.</b> Two bits, and they separate a great deal -- the player, the thing he is
 * standing in front of, and the status bar almost never share one.</li>
 * <li><b>The slot next door.</b> A game builds its OAM one object at a time, so a character is
 * usually a contiguous run of slots.</li>
 * </ul>
 * Either, rather than both, because each one carries the case the other drops. Castlevania shuffles
 * its slots every frame to move the flicker around, so Simon comes out as 19, 34, 38, 49 and 53 with
 * a candle at 27 sitting in the middle of them -- there the palette is the whole of the answer. Mega
 * Man is nine slots in a row and his face is not the colour of his armour, so slot 10 is a palette
 * of its own between slot 9 and slot 11 -- there the run is.
 * <p>
 * The candidates that did not survive: <b>which tiles a sprite uses</b> works where a character's
 * tiles are one block of the pattern table and fails where its head and its feet are not, and
 * <b>requiring a harder overlap</b> separates nothing at all, because the candle really is drawn on
 * top of the player.
 * <p>
 * It is a guess and it is still wrong sometimes, in both directions. Two enemies in the same colours
 * standing shoulder to shoulder come out as one group; a character whose parts are neither the same
 * colour nor next to each other in OAM comes out as several. What it no longer does is quietly put
 * somebody else's candle inside the player, which was the mistake worth spending a rule on.
 * <p>
 * Free of Swing so it can be tested where there is no display, like {@link PaletteCells} and
 * {@link PaletteUse}, and free of any window's geometry: it is given the sixty four positions and
 * hands back which group each one is in.
 */
final class SpriteGroups {
    private static final int TILE = 8;

    /**
     * How much each box is grown before they are compared, on every side.
     * <p>
     * Sprites in one thing are laid edge to edge -- one at X=$40 and the next at $48 -- and two
     * boxes that meet exactly do not overlap. A pixel of slack turns meeting into overlapping, and
     * it takes the diagonal neighbour in a two by two block with it, which is the shape most of
     * these things are.
     */
    private static final int SLACK = 1;

    /**
     * Which group each of the sixty four sprites is in.
     * <p>
     * Groups are numbered in reading order -- down the screen, then across -- so the same picture
     * numbers them the same way twice, and so the table they end up in reads roughly the way the
     * screen does. <b>Groups that are entirely below the picture come last</b> whatever their
     * coordinates: a game parks the sprites it is not using at Y=$F0 or beyond, and those are
     * usually most of OAM.
     *
     * @param left    each sprite's X byte.
     * @param top     each sprite's Y byte, one less than the line it lands on.
     * @param palette each sprite's palette, 0 to 3 -- the low two bits of its attribute byte.
     * @param height  8 or 16.
     * @return one group number per sprite, 0 upwards.
     */
    static int[] of(
            final int[] left, final int[] top, final int[] palette, final int height) {

        var count = left.length;
        var root = new int[count];

        for (var i = 0; i < count; i++) {
            root[i] = i;
        }

        for (var a = 0; a < count; a++) {
            for (var b = a + 1; b < count; b++) {
                if (related(palette, a, b) && touching(left, top, height, a, b)) {
                    join(root, a, b);
                }
            }
        }

        return number(root, left, top, height);
    }

    /**
     * Whether anything in OAM but their positions says these two might be one thing: the same
     * palette, or the slot next door.
     * <p>
     * Neighbouring slots are enough on their own because a run of them chains: Mega Man's nine are
     * 8 to 16, so the odd-coloured face at 10 is carried by 9 on one side and 11 on the other
     * without either of them having to be its colour.
     */
    private static boolean related(final int[] palette, final int a, final int b) {
        return palette[a] == palette[b] || Math.abs(a - b) == 1;
    }

    /**
     * Whether two sprites' boxes overlap once each has been given its pixel of slack.
     */
    private static boolean touching(
            final int[] left,
            final int[] top,
            final int height,
            final int a,
            final int b) {

        return Math.abs(left[a] - left[b]) < TILE + SLACK * 2
                && Math.abs(top[a] - top[b]) < height + SLACK * 2;
    }

    private static int find(final int[] root, final int sprite) {
        var at = sprite;

        while (root[at] != at) {
            root[at] = root[root[at]];
            at = root[at];
        }

        return at;
    }

    private static void join(final int[] root, final int a, final int b) {
        var rootA = find(root, a);
        var rootB = find(root, b);

        if (rootA != rootB) {
            root[rootB] = rootA;
        }
    }

    /**
     * Turns the roots the joining left behind into group numbers in reading order.
     */
    private static int[] number(
            final int[] root, final int[] left, final int[] top, final int height) {

        var count = root.length;
        var roots = Arrays.stream(root).map(r -> find(root, r)).distinct().boxed().toList();

        // Each group stands where its top left corner is, which is the corner a person's eye goes
        // to and the one that does not move when the group gains a sprite below it.
        var corners = new int[count][];

        for (var group : roots) {
            var x = Integer.MAX_VALUE;
            var y = Integer.MAX_VALUE;
            var visible = false;

            for (var sprite = 0; sprite < count; sprite++) {
                if (find(root, sprite) == group) {
                    x = Math.min(x, left[sprite]);
                    y = Math.min(y, top[sprite]);
                    visible |= top[sprite] + 1 < PPU.SCREEN_HEIGHT;
                }
            }

            corners[group] = new int[]{visible ? 0 : 1, y, x};
        }

        var ordered = roots.stream()
                .sorted(Comparator
                        .comparingInt((Integer group) -> corners[group][0])
                        .thenComparingInt(group -> corners[group][1])
                        .thenComparingInt(group -> corners[group][2]))
                .toList();

        var numbers = new int[count];

        for (var sprite = 0; sprite < count; sprite++) {
            numbers[sprite] = ordered.indexOf(find(root, sprite));
        }

        return numbers;
    }

    private SpriteGroups() {
    }
}
