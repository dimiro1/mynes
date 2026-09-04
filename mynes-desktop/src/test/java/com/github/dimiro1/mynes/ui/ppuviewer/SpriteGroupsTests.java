package com.github.dimiro1.mynes.ui.ppuviewer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The guess at which sprites are one thing, held to a few arrangements where the answer is not a
 * matter of opinion.
 * <p>
 * Nothing here asserts that the guess is <em>right</em> about a game, because there is no right
 * answer to be had: which sprites are one thing lives in the game's code and nowhere in OAM. What
 * it asserts is that the rule the guess follows is the rule it says it follows -- boxes that touch
 * are joined when OAM also puts them in one palette or in neighbouring slots, boxes that fail any of
 * that are not, and the numbering goes down the screen.
 */
class SpriteGroupsTests {
    private static final int SPRITES = 64;
    private static final int PARKED = 0xF0;

    /**
     * The two by two block almost everything in a game is drawn out of, at 8x8. Four sprites laid
     * edge to edge touch on their sides and on the diagonal, which is one group and not two.
     */
    @Test
    void fourSpritesInABlockAreOneThing() {
        var left = parked();
        var top = parked();

        place(left, top, 0, 64, 100);
        place(left, top, 1, 72, 100);
        place(left, top, 2, 64, 108);
        place(left, top, 3, 72, 108);

        var groups = SpriteGroups.of(left, top, onePalette(), 8);

        assertEquals(groups[0], groups[1]);
        assertEquals(groups[0], groups[2]);
        assertEquals(groups[0], groups[3], "including the one that only touches on the diagonal");
    }

    @Test
    void spritesWithDaylightBetweenThemAreNot() {
        var left = parked();
        var top = parked();

        place(left, top, 0, 64, 100);
        place(left, top, 1, 100, 100);

        var groups = SpriteGroups.of(left, top, onePalette(), 8);

        assertNotEquals(groups[0], groups[1]);
    }

    /**
     * The pixel of slack, which is what makes two sprites that meet exactly count as touching --
     * and which stops one pixel further apart from counting.
     */
    @Test
    void aSpriteIsJoinedToTheOneItMeetsAndToTheOneAPixelPastThat() {
        for (var gap = 0; gap <= 2; gap++) {
            var left = parked();
            var top = parked();

            place(left, top, 0, 64, 100);
            place(left, top, 1, 64 + 8 + gap, 100);

            var groups = SpriteGroups.of(left, top, onePalette(), 8);

            if (gap <= 1) {
                assertEquals(groups[0], groups[1], "a gap of " + gap);
            } else {
                assertNotEquals(groups[0], groups[1], "a gap of " + gap);
            }
        }
    }

    /**
     * A tall sprite reaches twice as far down, so two of them stacked are one thing where two short
     * ones at the same coordinates would be two.
     */
    @Test
    void howFarASpriteReachesDependsOnHowTallItIs() {
        var left = parked();
        var top = parked();

        place(left, top, 0, 64, 100);
        place(left, top, 1, 64, 116);

        assertNotEquals(
                SpriteGroups.of(left, top, onePalette(), 8)[0],
                SpriteGroups.of(left, top, onePalette(), 8)[1],
                "sixteen pixels apart is a gap for an 8x8");
        assertEquals(
                SpriteGroups.of(left, top, onePalette(), 16)[0],
                SpriteGroups.of(left, top, onePalette(), 16)[1],
                "and touching for an 8x16");
    }

    /**
     * Reading order, so the same picture numbers its groups the same way twice and the table reads
     * roughly the way the screen does.
     */
    @Test
    void groupsAreNumberedDownTheScreen() {
        var left = parked();
        var top = parked();

        place(left, top, 0, 200, 200);
        place(left, top, 1, 30, 40);
        place(left, top, 2, 120, 40);

        var groups = SpriteGroups.of(left, top, onePalette(), 8);

        assertEquals(0, groups[1], "highest, and leftmost of the two on that line");
        assertEquals(1, groups[2]);
        assertEquals(2, groups[0]);
    }

    /**
     * Whatever their coordinates, the sprites a game has finished with go to the bottom of the
     * list. There are usually fifty of them, and a group of fifty at the top would push everything
     * worth looking at off the end.
     */
    @Test
    void theSpritesParkedBelowTheScreenComeLast() {
        var left = parked();
        var top = parked();

        place(left, top, 0, 100, 150);

        var groups = SpriteGroups.of(left, top, onePalette(), 8);

        assertEquals(0, groups[0], "the one thing that is on the screen");
        assertEquals(1, groups[1], "and everything the game has put away");
    }

    /**
     * Sixty four sprites nowhere near each other are sixty four groups, which is the guess saying
     * it found nothing rather than the guess failing.
     */
    @Test
    void spritesSpreadOutAreEachTheirOwnGroup() {
        var left = new int[SPRITES];
        var top = new int[SPRITES];

        for (var sprite = 0; sprite < SPRITES; sprite++) {
            left[sprite] = (sprite % 8) * 32;
            top[sprite] = (sprite / 8) * 28;
        }

        var groups = SpriteGroups.of(left, top, onePalette(), 8);

        assertEquals(SPRITES, Arrays.stream(groups).distinct().count());
    }

    /**
     * The half of the rule that is not about position: two sprites drawn on top of each other are
     * two things when nothing else in OAM connects them.
     */
    @Test
    void spritesWithNothingInCommonAreNotJoinedHoweverMuchTheyOverlap() {
        var left = parked();
        var top = parked();
        var palettes = onePalette();

        // Far enough apart in OAM that only the palette can join them.
        place(left, top, 0, 64, 100);
        place(left, top, 20, 66, 102);

        assertEquals(
                SpriteGroups.of(left, top, palettes, 8)[0],
                SpriteGroups.of(left, top, palettes, 8)[20],
                "the same palette, so one thing");

        palettes[20] = 3;

        assertNotEquals(
                SpriteGroups.of(left, top, palettes, 8)[0],
                SpriteGroups.of(left, top, palettes, 8)[20],
                "a different one, and nothing else to go on, so two");
    }

    /**
     * Mega Man's face is not the colour of his armour, and his nine sprites are nine slots in a row
     * -- so the odd-coloured one in the middle is carried by the neighbours on either side of it.
     * The palette on its own would leave his face out of him.
     */
    @Test
    void anOddColouredSpriteInARunOfSlotsIsCarriedByTheOnesEitherSideOfIt() {
        var left = parked();
        var top = parked();
        var palettes = onePalette();

        place(left, top, 8, 0x80, 0xA8);
        place(left, top, 9, 0x78, 0xA8);
        place(left, top, 10, 0x7D, 0xAE);
        place(left, top, 11, 0x84, 0xB0);
        place(left, top, 12, 0x7C, 0xB0);

        palettes[10] = 1;

        var groups = SpriteGroups.of(left, top, palettes, 8);

        for (var sprite : new int[]{9, 10, 11, 12}) {
            assertEquals(groups[8], groups[sprite], "sprite " + sprite + " is part of him");
        }
    }

    /**
     * And the slot next door is not enough on its own either: two sprites that touch and sit side by
     * side in OAM are joined, and the same two moved apart on the screen are not.
     */
    @Test
    void neighbouringSlotsStillHaveToTouch() {
        var left = parked();
        var top = parked();
        var palettes = onePalette();

        palettes[1] = 3;

        place(left, top, 0, 64, 100);
        place(left, top, 1, 68, 100);

        assertEquals(
                SpriteGroups.of(left, top, palettes, 8)[0],
                SpriteGroups.of(left, top, palettes, 8)[1]);

        place(left, top, 1, 120, 100);

        assertNotEquals(
                SpriteGroups.of(left, top, palettes, 8)[0],
                SpriteGroups.of(left, top, palettes, 8)[1]);
    }

    /**
     * The worked example the palette half of the rule is for: Castlevania's Simon with a candle
     * drawn over him. He is five sprites in palette 0 that touch each other, the candle is one in
     * palette 3 sitting in the middle of them, and the slots they are in are shuffled every frame to
     * move the flicker around -- so the candle is nobody's neighbour and position alone says it is
     * part of him.
     */
    @Test
    void aCandleDrawnOverThePlayerIsNotPartOfHim() {
        var left = parked();
        var top = parked();
        var palettes = onePalette();

        place(left, top, 19, 0x28, 0x8D);
        place(left, top, 34, 0x20, 0x7D);
        place(left, top, 38, 0x18, 0x7D);
        place(left, top, 49, 0x28, 0x7D);
        place(left, top, 53, 0x20, 0x8D);

        place(left, top, 27, 0x1C, 0x80);
        palettes[27] = 3;

        var groups = SpriteGroups.of(left, top, palettes, 16);

        for (var sprite : new int[]{34, 38, 49, 53}) {
            assertEquals(groups[19], groups[sprite], "sprite " + sprite + " is part of him");
        }

        assertNotEquals(groups[19], groups[27], "and the candle is not");
    }

    private static void place(
            final int[] left, final int[] top, final int sprite, final int x, final int y) {

        left[sprite] = x;
        top[sprite] = y;
    }

    /**
     * Every sprite in the same palette, so a test about position only has to say where things are.
     */
    private static int[] onePalette() {
        return new int[SPRITES];
    }

    /**
     * Every sprite where a game leaves the ones it is not using, so a test only has to say where
     * the ones it cares about are.
     */
    private static int[] parked() {
        var bytes = new int[SPRITES];

        Arrays.fill(bytes, PARKED);

        return bytes;
    }
}
