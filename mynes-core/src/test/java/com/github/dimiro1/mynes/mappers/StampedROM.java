package com.github.dimiro1.mynes.mappers;

/**
 * A synthetic ROM whose every byte says which bank it is in.
 * <p>
 * Reading one byte is then enough to say which bank a mapper switched in, which is what almost
 * every banking assertion in this package comes down to.
 */
final class StampedROM {
    private StampedROM() {
    }

    /**
     * @param banks    how many banks the chip holds.
     * @param bankSize how big one bank is, in bytes.
     * @return the ROM image, every byte of bank <i>n</i> holding <i>n</i>.
     */
    static byte[] of(final int banks, final int bankSize) {
        var rom = new byte[banks * bankSize];

        for (var i = 0; i < rom.length; i++) {
            rom[i] = (byte) (i / bankSize);
        }

        return rom;
    }
}
