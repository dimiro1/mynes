package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Tests for the sizes Settings &gt; Screen Size offers.
 * <p>
 * The ids go through {@code ~/.mynes/config.properties}, which is a file people edit by hand, so
 * every way of getting one wrong has to land on a size rather than on a window with no size at all.
 */
class ScreenScaleTests {
    @Test
    void everySizeIsAWholeMultiple() {
        assertEquals(1, ScreenScale.ONE_TIMES.factor());
        assertEquals(2, ScreenScale.TWO_TIMES.factor());
        assertEquals(3, ScreenScale.THREE_TIMES.factor());
        assertEquals(4, ScreenScale.FOUR_TIMES.factor());
    }

    @Test
    void aLabelIsTheFactorWithAnX() {
        assertEquals("1x", ScreenScale.ONE_TIMES.label());
        assertEquals("4x", ScreenScale.FOUR_TIMES.label());
    }

    @Test
    void anIdNamesItsSize() {
        assertSame(ScreenScale.THREE_TIMES, ScreenScale.byId("3"));
    }

    @Test
    void everySizeReadsBackAsItself() {
        // What the config file's round trip rests on: the id written out is the id that comes back
        // in.
        for (var scale : ScreenScale.values()) {
            assertSame(scale, ScreenScale.byId(scale.id()), scale.label());
        }
    }

    @Test
    void anUnknownIdFallsBackToTheDefault() {
        assertSame(ScreenScale.defaultScale(), ScreenScale.byId("huge"));
    }

    @Test
    void aSizeOutsideTheFourFallsBackToTheDefault() {
        // Somebody who wants a full screen, and a fraction, both of which this setting cannot give
        // them. Neither is worth refusing to start over.
        assertSame(ScreenScale.defaultScale(), ScreenScale.byId("8"));
        assertSame(ScreenScale.defaultScale(), ScreenScale.byId("2.5"));
        assertSame(ScreenScale.defaultScale(), ScreenScale.byId("0"));
    }
}
