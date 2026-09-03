package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The five positions of the volume control, and the curve between the position and the amplitude.
 */
class VolumeTests {
    @Test
    void fullVolumeLeavesTheSamplesAlone() {
        assertEquals(1.0, Volume.FULL.gain(), 1e-9);
    }

    /**
     * The taper. Half way down the control is a quarter of the amplitude, which is where "half as
     * loud" actually is -- a control that halved the amplitude there would spend its bottom half
     * on differences nobody can hear.
     */
    @Test
    void theControlIsSquaredOnItsWayToTheAmplitude() {
        assertEquals(0.25, Volume.HALF.gain(), 1e-9);
        assertEquals(0.0625, Volume.QUARTER.gain(), 1e-9);
        assertEquals(0.01, Volume.TENTH.gain(), 1e-9);
    }

    @Test
    void everyPositionIsAudibleAndNoneIsSilence() {
        for (var volume : Volume.values()) {
            assertTrue(volume.gain() > 0.0, volume.label() + " should not be a mute");
            assertTrue(volume.gain() <= 1.0, volume.label() + " should not amplify");
        }
    }

    @Test
    void theStepsRunTheWayTheMenuReadsThem() {
        assertSame(Volume.THREE_QUARTERS, Volume.FULL.quieter());
        assertSame(Volume.HALF, Volume.THREE_QUARTERS.quieter());
        assertSame(Volume.THREE_QUARTERS, Volume.HALF.louder());
    }

    /**
     * Stopping rather than wrapping: a control that came back round to full from the bottom would
     * be one that shouted at somebody trying to make it quieter.
     */
    @Test
    void theEndsStopRatherThanWrap() {
        assertSame(Volume.FULL, Volume.FULL.louder());
        assertSame(Volume.TENTH, Volume.TENTH.quieter());
    }

    @Test
    void aVolumeIsSpelledTheSameWayInTheFileAsInTheMenu() {
        assertEquals("50", Volume.HALF.id());
        assertEquals("50%", Volume.HALF.label());
        assertSame(Volume.HALF, Volume.byId("50"));
    }

    @Test
    void anEntryThisVersionDoesNotUnderstandCostsTheSettingRatherThanTheStartup() {
        assertSame(Volume.defaultVolume(), Volume.byId("33"));
        assertSame(Volume.defaultVolume(), Volume.byId("loud"));
    }
}
