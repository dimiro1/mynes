package com.github.dimiro1.mynes.ui;

import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The menu's percentages, and what they come to on each machine.
 * <p>
 * A percentage is remembered rather than a line count precisely so that this conversion happens
 * late: the region can change under a setting -- Machine &gt; Region builds a new console -- and
 * "half as long again" has to go on meaning that on the other side of it.
 */
class OverclockSettingTests {

    @Test
    void eachPresetResolvesAgainstTheRegionItIsRunOn() {
        assertEquals(new Overclock(131, 0), OverclockSetting.PLUS_50.resolve(Region.NTSC));
        assertEquals(new Overclock(156, 0), OverclockSetting.PLUS_50.resolve(Region.PAL));

        assertNotEquals(
                OverclockSetting.PLUS_50.resolve(Region.NTSC),
                OverclockSetting.PLUS_50.resolve(Region.PAL),
                "half of 262 lines is not half of 312, which is why this is not a line count");
    }

    @Test
    void offIsTheHardwareOnEitherMachine() {
        assertEquals(Overclock.NONE, OverclockSetting.OFF.resolve(Region.NTSC));
        assertEquals(Overclock.NONE, OverclockSetting.OFF.resolve(Region.PAL));
    }

    @Test
    void thePresetsCoverAQuarterToDoubleTheFrame() {
        assertEquals(new Overclock(66, 0), OverclockSetting.PLUS_25.resolve(Region.NTSC));
        assertEquals(new Overclock(262, 0), OverclockSetting.PLUS_100.resolve(Region.NTSC));
        assertEquals(new Overclock(524, 0), OverclockSetting.PLUS_200.resolve(Region.NTSC));
    }

    @Test
    void everyPresetPutsItsLinesBeforeTheNmi() {
        // The half that changes nothing a game can observe except that the frame is longer. Lines
        // after the NMI are reachable from the command line and the REPL and not from a menu.
        for (var setting : OverclockSetting.values()) {
            assertEquals(0, setting.resolve(Region.NTSC).afterNmi(), setting.label());
        }
    }

    @Test
    void anIdNobodyOffersFallsBackToOff() {
        assertEquals(OverclockSetting.OFF, OverclockSetting.byId("lots"));
        assertEquals(OverclockSetting.OFF, OverclockSetting.byId("33"));
        assertEquals(OverclockSetting.defaultSetting(), OverclockSetting.byId(""));
    }

    @Test
    void anIdIsWhatSomebodyEditingTheFileWouldWrite() {
        assertEquals("off", OverclockSetting.OFF.id());
        assertEquals("50", OverclockSetting.PLUS_50.id());
        assertEquals("+50%", OverclockSetting.PLUS_50.label());

        for (var setting : OverclockSetting.values()) {
            assertEquals(setting, OverclockSetting.byId(setting.id()), setting.label());
        }
    }
}
