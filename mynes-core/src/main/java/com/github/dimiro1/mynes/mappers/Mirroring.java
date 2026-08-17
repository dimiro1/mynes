package com.github.dimiro1.mynes.mappers;

/**
 * How the cartridge wires the two kilobytes of nametable RAM inside the console into the four
 * kilobyte window the PPU addresses.
 * <p>
 * The console only ever contains two nametables. Which of them a given address lands on is
 * decided by the cartridge, not by the PPU: the cart drives the CIRAM A10 line, usually by
 * hard wiring it to one of the two address lines the PPU puts out. That is why mirroring lives
 * on the {@link Mapper} rather than in {@link com.github.dimiro1.mynes.VRAM}, and why a mapper
 * with bank switching registers can change it at run time.
 *
 * @see <a href="https://www.nesdev.org/wiki/Mirroring">NESdev: Mirroring</a>
 */
public enum Mirroring {
    /**
     * A10 follows PPU A11: the two nametables sit one above the other, so the screen scrolls
     * seamlessly left and right.
     */
    HORIZONTAL,

    /**
     * A10 follows PPU A10: the two nametables sit side by side, so the screen scrolls seamlessly
     * up and down.
     */
    VERTICAL,

    /**
     * The cartridge carries two more kilobytes of its own, so all four nametables are distinct
     * and nothing is mirrored.
     */
    FOUR_SCREEN,

    /**
     * A10 is held low: all four nametables are the same first kilobyte of CIRAM, and the second
     * kilobyte cannot be reached at all. A mapper with a mirroring register drives the line
     * itself rather than following an address line, which is how it can do this.
     */
    ONE_SCREEN_LOW,

    /**
     * A10 is held high: all four nametables are the same second kilobyte of CIRAM. Games switch
     * between this and {@link #ONE_SCREEN_LOW} to flip between two screens' worth of nametable
     * without redrawing either.
     */
    ONE_SCREEN_HIGH;

    /**
     * Maps the mirroring field of an iNES header onto this enum.
     *
     * @param flags the two mirroring bits of flags 6, as {@link com.github.dimiro1.mynes.Cart}
     *              joins them: bit 3 (four screen) above bit 0 (vertical).
     * @return the mirroring the header asks for. The four screen bit outranks the vertical bit,
     * which is what makes both $02 and $03 four screen.
     */
    public static Mirroring fromINES(final int flags) {
        return switch (flags) {
            case 0 -> HORIZONTAL;
            case 1 -> VERTICAL;
            default -> FOUR_SCREEN;
        };
    }
}
