package com.github.dimiro1.mynes.ui.events;

import com.github.dimiro1.mynes.debug.Debugger;
import com.github.dimiro1.mynes.ui.debugger.Theme;

import java.awt.Color;

/**
 * A colour per kind of thing the machine does.
 * <p>
 * The debugger's own syntax hues, reused deliberately: the window has one visual language and a
 * seventh set of colours invented here would be a seventh thing to keep in step with the other six.
 * What each one means is the same as it means over there -- blue for a place, green for arithmetic,
 * purple for something that changes where the program is, red for a stop.
 * <p>
 * Reads are the same hue as their writes, a shade lighter, because they are the same question asked
 * the other way round: a $2002 read belongs beside the $2000 write, not in a colour of its own.
 */
final class EventColours {
    private static Color pick(final int light, final int dark) {
        return new Color(Theme.isDark() ? dark : light);
    }

    private EventColours() {
    }

    static Color of(final Debugger.EventKind kind) {
        return switch (kind) {
            case PPU_WRITE -> pick(0x0550AE, 0x79C0FF);
            case PPU_READ -> pick(0x6E9BD6, 0xA5D6FF);
            case AUDIO_WRITE -> pick(0x116329, 0x7EE787);
            case AUDIO_READ -> pick(0x74B98A, 0xA7F3B3);
            case CARTRIDGE_WRITE -> pick(0x8250DF, 0xD2A8FF);
            case NMI -> pick(0x953800, 0xF2B17A);
            case IRQ -> pick(0xD73A49, 0xFF7B72);
        };
    }
}
