package com.github.dimiro1.mynes.cart;

import com.github.dimiro1.mynes.cart.mappers.Mapper;
import com.github.dimiro1.mynes.cart.mappers.Mapper0;
import com.github.dimiro1.mynes.utils.ByteUtils;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public record Cart(
        String filename,
        byte[] prgROM,
        byte[] chrROM,
        Mapper mapper,
        int mirror,
        boolean hasBattery) {

    /**
     * Loads the iNes cart file. <a href="https://wiki.nesdev.org/w/index.php/INES">https://wiki.nesdev.org/w/index.php/INES</a>
     *
     * @param bytes    the iNes file as an array of bytes.
     * @param filename the filename.
     * @return A Cart.
     */
    public static Cart load(final byte[] bytes, final String filename) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        var magic = buffer.getInt();
        var prgLen = buffer.get();
        var chrLen = buffer.get();
        var flags6 = buffer.get();
        var flags7 = buffer.get();
        var prgRAMLen = buffer.get();

        // Done with iNes header.
        buffer.position(16);

        if (magic != 0x1a53454e) {
            throw new BadiNesFileException(filename);
        }

        if (prgLen == 0) {
            throw new BadiNesFileException(filename);
        }

        // Skip trainer
        if (ByteUtils.getBit(2, flags6) == 1) {
            buffer.position(buffer.position() + 512);
        }

        var hasBattery = ByteUtils.getBit(1, flags6) == 1;
        var lowMapper = ByteUtils.getHighNibble(flags6);
        var higMapper = ByteUtils.getHighNibble(flags7);
        var mapperNum = ByteUtils.joinNibbles(higMapper, lowMapper);
        var lowMirror = ByteUtils.getBit(0, flags6);
        var higMirror = ByteUtils.getBit(3, flags6);
        var mirror = ByteUtils.joinBits(higMirror, lowMirror);

        var prgROM = new byte[prgLen * 0x4000];
        var chrROM = new byte[0x2000];

        buffer.get(prgROM);

        if (chrLen > 0) {
            chrROM = new byte[chrLen * 0x2000];
            buffer.get(chrROM);
        }

        var mapper = switch (mapperNum) {
            case 0 -> new Mapper0(prgROM, chrROM);
            default -> throw new IllegalStateException("Unexpected mapper: " + mapperNum);
        };

        return new Cart(filename, prgROM, chrROM, mapper, mirror, hasBattery);
    }
}
