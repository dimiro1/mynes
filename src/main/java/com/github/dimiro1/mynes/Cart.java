package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.mappers.Mapper0;
import com.github.dimiro1.mynes.mappers.Mapper1;
import com.github.dimiro1.mynes.mappers.Mapper2;
import com.github.dimiro1.mynes.mappers.Mapper3;
import com.github.dimiro1.mynes.mappers.Mapper4;
import com.github.dimiro1.mynes.mappers.Mirroring;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Represents a NES game cartridge (ROM).
 * <p>
 * A Cart contains the program ROM (PRG-ROM), character ROM (CHR-ROM), and the
 * memory mapper implementation that controls bank switching and special hardware features.
 *
 * @param filename the original filename of the ROM
 * @param prgROM program ROM data (CPU-addressable)
 * @param chrROM character ROM data (PPU-addressable pattern tables)
 * @param mapper the memory mapper implementation
 * @param mapperNumber the iNES mapper number the header asked for, kept alongside the mapper
 *                     itself because it is what a person names a cartridge's hardware by
 * @param mirror the mirroring mode (horizontal, vertical, four-screen)
 * @param hasBattery whether this cart has battery-backed save RAM
 * @param sha256 the digest of the whole file, header included, as lowercase hex. What names this
 *               particular dump of this particular game: a report records it so two runs can be
 *               told apart, and a save state carries it so one cannot be loaded into the wrong
 *               cartridge. The header is part of it on purpose, since the header is what decides
 *               the mapper and the mirroring
 */
public record Cart(
        String filename,
        byte[] prgROM,
        byte[] chrROM,
        Mapper mapper,
        int mapperNumber,
        int mirror,
        boolean hasBattery,
        String sha256) {

    /**
     * Magic number for iNES format files.
     * Represents "NES\x1A" in ASCII (0x4E45531A when read as little-endian int).
     */
    private static final int INES_MAGIC = 0x1a53454e;

    /**
     * Size of one PRG-ROM bank in bytes (16 KB).
     */
    private static final int PRG_BANK_SIZE = 0x4000;

    /**
     * Size of one CHR-ROM bank in bytes (8 KB).
     */
    private static final int CHR_BANK_SIZE = 0x2000;

    /**
     * Size of the iNES header in bytes.
     */
    private static final int INES_HEADER_SIZE = 16;

    /**
     * Size of the optional trainer section in bytes.
     */
    private static final int TRAINER_SIZE = 512;

    /**
     * Loads an iNES format ROM file.
     * <p>
     * The iNES format is the most common NES ROM format. For specification details, see:
     * <a href="https://wiki.nesdev.org/w/index.php/INES">https://wiki.nesdev.org/w/index.php/INES</a>
     *
     * @param bytes the iNES file as an array of bytes
     * @param filename the filename (for error reporting)
     * @return a Cart instance containing the loaded ROM data
     * @throws InvalidNesFileException if the file format is invalid
     * @throws UnsupportedMapperException if the mapper is not supported
     * @throws BufferUnderflowException if the file is truncated
     */
    public static Cart load(final byte[] bytes, final String filename) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        // Read iNES header (16 bytes)
        var magic = buffer.getInt();
        var prgBankCount = Byte.toUnsignedInt(buffer.get());
        var chrBankCount = Byte.toUnsignedInt(buffer.get());
        var flags6 = Byte.toUnsignedInt(buffer.get());
        var flags7 = Byte.toUnsignedInt(buffer.get());
        var prgRAMSize = Byte.toUnsignedInt(buffer.get()); // PRG-RAM size in 8KB units (rarely used)

        // Skip remaining header bytes (bytes 9-15 are for iNES 2.0 or unused)
        buffer.position(INES_HEADER_SIZE);

        // Validate magic number ("NES\x1A")
        if (magic != INES_MAGIC) {
            throw new InvalidNesFileException(filename);
        }

        // Validate PRG-ROM exists
        if (prgBankCount == 0) {
            throw new InvalidNesFileException(filename);
        }

        // Skip 512-byte trainer if present
        boolean hasTrainer = ByteUtils.getBit(2, flags6) == 1;
        if (hasTrainer) {
            if (buffer.remaining() < TRAINER_SIZE) {
                throw new InvalidNesFileException(filename);
            }
            buffer.position(buffer.position() + TRAINER_SIZE);
        }

        // Extract flags
        boolean hasBattery = ByteUtils.getBit(1, flags6) == 1;
        int lowMapperNibble = ByteUtils.getHighNibble(flags6);
        int highMapperNibble = ByteUtils.getHighNibble(flags7);
        int mapperNumber = ByteUtils.joinNibbles(highMapperNibble, lowMapperNibble);
        int lowMirrorBit = ByteUtils.getBit(0, flags6);
        int highMirrorBit = ByteUtils.getBit(3, flags6);
        int mirror = ByteUtils.joinBits(highMirrorBit, lowMirrorBit);

        // Calculate expected data sizes
        int prgROMSize = prgBankCount * PRG_BANK_SIZE;
        int chrROMSize = chrBankCount * CHR_BANK_SIZE;

        // Validate sufficient data remains
        if (buffer.remaining() < prgROMSize + chrROMSize) {
            throw new InvalidNesFileException(filename);
        }

        // Read PRG-ROM
        var prgROM = new byte[prgROMSize];
        buffer.get(prgROM);

        // Read CHR-ROM (can be empty for CHR-RAM carts)
        byte[] chrROM;
        if (chrBankCount > 0) {
            chrROM = new byte[chrROMSize];
            buffer.get(chrROM);
        } else {
            // No CHR-ROM banks means the cart uses CHR-RAM
            chrROM = new byte[0];
        }

        // Create mapper instance
        var mapper = switch (mapperNumber) {
            case 0 -> new Mapper0(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 1 -> new Mapper1(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 2 -> new Mapper2(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 3 -> new Mapper3(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 4 -> new Mapper4(prgROM, chrROM, Mirroring.fromINES(mirror));
            default -> throw new UnsupportedMapperException(mapperNumber, filename);
        };

        return new Cart(
                filename, prgROM, chrROM, mapper, mapperNumber, mirror, hasBattery, sha256(bytes));
    }

    private static String sha256(final byte[] image) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(image);
            var hex = new StringBuilder(digest.length * 2);

            for (var b : digest) {
                hex.append(String.format("%02x", b));
            }

            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            // Every JRE has SHA-256; this is here because the API says it might not.
            throw new IllegalStateException(e);
        }
    }
}
