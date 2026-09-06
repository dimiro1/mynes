package com.github.dimiro1.mynes.ui.events;

import com.github.dimiro1.mynes.debug.Debugger;

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
    private static final Color PPU_WRITE = new Color(0x0550AE);
    private static final Color PPU_READ = new Color(0x6E9BD6);
    private static final Color AUDIO_WRITE = new Color(0x116329);
    private static final Color AUDIO_READ = new Color(0x74B98A);
    private static final Color CARTRIDGE_WRITE = new Color(0x8250DF);
    private static final Color NMI = new Color(0x953800);
    private static final Color IRQ = new Color(0xD73A49);

    private EventColours() {
    }

    static Color of(final Debugger.EventKind kind) {
        return switch (kind) {
            case PPU_WRITE -> PPU_WRITE;
            case PPU_READ -> PPU_READ;
            case AUDIO_WRITE -> AUDIO_WRITE;
            case AUDIO_READ -> AUDIO_READ;
            case CARTRIDGE_WRITE -> CARTRIDGE_WRITE;
            case NMI -> NMI;
            case IRQ -> IRQ;
        };
    }
}
