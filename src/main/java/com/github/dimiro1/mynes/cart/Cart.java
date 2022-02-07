package com.github.dimiro1.mynes.cart;

import com.github.dimiro1.mynes.memory.Memory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public record Cart(
        String filename,
        byte[] prgROM,
        byte[] chrROM,
        int mapperNo,
        int mirror,
        boolean hasBattery) implements Memory {

    /**
     * Loads the iNes cart file.
     *
     * @param bytes    the iNes file as an array of bytes.
     * @param filename the filename.
     * @return A Cart.
     */
    public static Cart load(final byte[] bytes, final String filename) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        // Constant $4E $45 $53 $1A ("NES" followed by MS-DOS end-of-file)
        var magic = buffer.getInt();

        // Size of PRG ROM in 16 KB units
        var prgLen = buffer.get();

        // Size of CHR ROM in 8 KB units (Value 0 means the board uses CHR RAM)
        var chrLen = buffer.get();

        // Mapper, mirroring, battery, trainer
        // 76543210
        // ||||||||
        // |||||||+- Mirroring: 0: horizontal (vertical arrangement) (CI-RAM A10 = PPU A11)
        // |||||||              1: vertical (horizontal arrangement) (CI-RAM A10 = PPU A10)
        // ||||||+-- 1: Cartridge contains battery-backed PRG RAM ($6000-7FFF) or other
        // persistent memory
        // |||||+--- 1: 512-byte trainer at $7000-$71FF (stored before PRG data)
        // ||||+---- 1: Ignore mirroring control or above mirroring bit; instead provide
        // four-screen VRAM
        // ++++----- Lower nybble of mapper number
        var flags6 = buffer.get();

        // Mapper, VS/Play-choice, NES 2.0
        // 76543210
        // ||||||||
        // |||||||+- VS Uni-system
        // ||||||+-- PlayChoice-10 (8KB of Hint Screen data stored after CHR data)
        // ||||++--- If equal to 2, flags 8-15 are in NES 2.0 format
        // ++++----- Upper nybble of mapper number
        var flags7 = buffer.get();

        // PRG-RAM size (rarely used extension)
        var prgRAMLen = buffer.get();

        // skip 7 - Flags9, Flags10 and unused Padding
        buffer.position(buffer.position() + 7);

        if (magic != 0x1a53454e) {
            throw new InvalidINesFileException(filename);
        }

        if (prgLen == 0) {
            throw new InvalidINesFileException(filename);
        }

        // Skip trainer
        if ((flags6 & 4) == 4) {
            buffer.position(buffer.position() + 512);
        }

        var hasBattery = (flags6 >> 1 & 1) == 1;
        var lowerMapper = flags6 >> 4;
        var upperMapper = flags7 >> 4;
        var mapperNo = lowerMapper | upperMapper << 4;
        var lowerMirror = flags6 & 1;
        var upperMirror = flags6 >> 3 & 1;
        var mirror = lowerMirror | upperMirror << 1;

        byte[] prgROM = new byte[prgLen * 16384];
        byte[] chrROM = new byte[8192];

        buffer.get(prgROM);

        if (chrLen > 0) {
            chrROM = new byte[chrLen * 8192];
            buffer.get(chrROM);
        }

        return new Cart(filename, prgROM, chrROM, mapperNo, mirror, hasBattery);
    }

    @Override
    public int read(int address) {
        // TODO: Delegate to mapper
        if (this.prgROM.length == 0x4000 && address >= 0x4000) {
            return this.prgROM[address % 0x4000];
        }

        return this.prgROM[address];
    }

    @Override
    public void write(int address, int data) {
        // TODO: Delegate to mapper
    }

    @Override
    public int getLength() {
        return 0x8000; // fixed
    }
}
