package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;

/**
 * The PPU's own address bus.
 * <p>
 * Fourteen address lines, so $0000-$3FFF, and only two things hang off it:
 * <ul>
 *   <li>$0000-$1FFF, the pattern tables, which live on the cartridge and are reached through
 *       the mapper. Whether a write lands anywhere depends on whether the cart carries CHR ROM
 *       or CHR RAM, which is the mapper's business rather than this class's.</li>
 *   <li>$2000-$3EFF, the nametables, which live in the two kilobytes of RAM inside the console.
 *       Four kilobytes of address space over two kilobytes of RAM, with the cartridge deciding
 *       how they fold onto each other -- see {@link com.github.dimiro1.mynes.mappers.Mirroring}.
 *       $3000-$3EFF is a plain mirror of $2000-$2EFF.</li>
 * </ul>
 * <p>
 * Palette RAM ($3F00-$3FFF) is deliberately <em>not</em> here. It sits inside the PPU rather than
 * on this bus: the PPU reads it on every dot of every visible scanline without a bus cycle, and
 * a $2007 read of it comes back immediately instead of through the read buffer. {@link PPU} owns
 * it, and nothing in this class ever sees a palette address.
 *
 * @see <a href="https://www.nesdev.org/wiki/PPU_memory_map">NESdev: PPU memory map</a>
 */
public class VRAM {
    /**
     * Console nametable RAM, plus the two kilobytes a four screen cartridge adds. Sized to the
     * whole nametable window so that four screen mirroring is a plain identity mapping.
     */
    private final int[] ciram = new int[0x1000];

    private final Mapper mapper;

    public VRAM(final Mapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Reads a byte from the PPU bus.
     *
     * @param address any address; only the low fourteen lines exist, so it is masked first.
     * @return the byte at that address.
     */
    public int read(final int address) {
        var addr = address & 0x3FFF;

        // Every access puts the address on the bus, wherever it is going: a mapper watching the
        // lines sees a nametable read just as clearly as a pattern table one.
        mapper.ppuAddress(addr);

        if (addr < 0x2000) {
            return mapper.charRead(addr);
        }

        return ciram[ciramIndex(addr)];
    }

    /**
     * Writes a byte to the PPU bus.
     *
     * @param address any address; only the low fourteen lines exist, so it is masked first.
     * @param data    the byte to write.
     */
    public void write(final int address, final int data) {
        var addr = address & 0x3FFF;

        mapper.ppuAddress(addr);

        if (addr < 0x2000) {
            mapper.charWrite(addr, data);
            return;
        }

        ciram[ciramIndex(addr)] = data & 0xFF;
    }

    /**
     * Folds a nametable address onto the RAM that actually backs it.
     * <p>
     * The mapper is asked every time rather than cached, because a mapper with mirroring
     * registers can change its answer between one access and the next.
     *
     * @param address an address in $2000-$3EFF.
     * @return the index into {@link #ciram} that address names.
     */
    private int ciramIndex(final int address) {
        // $3000-$3EFF is a mirror of $2000-$2EFF, so only the low twelve bits matter.
        var addr = address & 0x0FFF;

        return switch (mapper.mirroring()) {
            // A10 follows PPU A11: $2000/$2400 share, $2800/$2C00 share.
            case HORIZONTAL -> ((addr >> 1) & 0x400) | (addr & 0x3FF);
            // A10 follows PPU A10: $2000/$2800 share, $2400/$2C00 share.
            case VERTICAL -> addr & 0x7FF;
            // The cart supplies its own RAM, so nothing is shared.
            case FOUR_SCREEN -> addr;
            // A10 held low or high: every nametable is the same kilobyte.
            case ONE_SCREEN_LOW -> addr & 0x3FF;
            case ONE_SCREEN_HIGH -> 0x400 | (addr & 0x3FF);
        };
    }
}
