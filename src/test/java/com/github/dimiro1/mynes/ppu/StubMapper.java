package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.mappers.Mirroring;

/**
 * A cartridge that is nothing but memory.
 * <p>
 * Eight kilobytes of writable pattern table so a test can put a tile where it wants one without
 * building a ROM image, and a mirroring mode that can be changed between assertions.
 */
class StubMapper implements Mapper {
    private final int[] prg = new int[0x8000];
    private final int[] chr = new int[0x2000];

    private Mirroring mirroring;

    StubMapper() {
        this(Mirroring.HORIZONTAL);
    }

    StubMapper(final Mirroring mirroring) {
        this.mirroring = mirroring;
    }

    void setMirroring(final Mirroring mirroring) {
        this.mirroring = mirroring;
    }

    @Override
    public int prgRead(final int address) {
        return prg[address & 0x7FFF];
    }

    @Override
    public void prgWrite(final int address, final int data) {
        prg[address & 0x7FFF] = data & 0xFF;
    }

    @Override
    public int charRead(final int address) {
        return chr[address & 0x1FFF];
    }

    @Override
    public void charWrite(final int address, final int data) {
        chr[address & 0x1FFF] = data & 0xFF;
    }

    @Override
    public Mirroring mirroring() {
        return mirroring;
    }
}
