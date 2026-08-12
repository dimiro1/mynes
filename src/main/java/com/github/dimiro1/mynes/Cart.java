package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.mappers.Mapper0;
import com.github.dimiro1.mynes.mappers.Mapper1;
import com.github.dimiro1.mynes.mappers.Mapper2;
import com.github.dimiro1.mynes.mappers.Mapper3;
import com.github.dimiro1.mynes.mappers.Mapper4;
import com.github.dimiro1.mynes.mappers.Mapper7;
import com.github.dimiro1.mynes.mappers.Mapper9;
import com.github.dimiro1.mynes.mappers.Mapper10;
import com.github.dimiro1.mynes.mappers.Mapper11;
import com.github.dimiro1.mynes.mappers.Mapper66;
import com.github.dimiro1.mynes.mappers.Mapper71;
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
 * @param timing which console the header says the cartridge was made for, which is not always a
 *               region: see {@link Timing}
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
        Timing timing,
        String sha256) {

    /**
     * What the header claims about the console the cartridge was made for.
     * <p>
     * Kept apart from {@link Region} because two of these are not regions at all. A cartridge that
     * says multi-region says it works on either machine and leaves the choice open; one that says
     * Dendy names a Russian famiclone this emulator does not model. Both still have to resolve to
     * something for the machine to be built, and {@link #region()} is where that judgement is
     * recorded rather than buried in the parser.
     *
     * @see <a href="https://www.nesdev.org/wiki/NES_2.0#Byte_12">NESdev: NES 2.0 byte 12</a>
     */
    public enum Timing {
        /**
         * The header says NTSC.
         */
        NTSC("ntsc", Region.NTSC),

        /**
         * The header says PAL.
         */
        PAL("pal", Region.PAL),

        /**
         * The header says the game runs on either. NTSC, because that is the machine such a game
         * was almost always developed on, and because it is the one this emulator has test ROMs
         * for.
         */
        MULTI_REGION("multi-region", Region.NTSC),

        /**
         * The header says Dendy: 50Hz and 312 scanlines like PAL, but three dots to a CPU cycle
         * like NTSC and the NTSC audio tables. Run as PAL, which gets the picture and the frame
         * rate right and the CPU rate 6% slow -- much the closer of the two approximations, since
         * a Dendy cartridge is a PAL-region cartridge.
         */
        DENDY("dendy", Region.PAL),

        /**
         * The header says nothing, which is most of them: plain iNES leaves the byte at zero and a
         * European dump is usually indistinguishable from an American one. NTSC, and the reason
         * there is a way to say otherwise by hand.
         */
        UNSTATED("unstated", Region.NTSC);

        private final String id;
        private final Region region;

        Timing(final String id, final Region region) {
            this.id = id;
            this.region = region;
        }

        /**
         * How this is spelled in a report, which is the only place it is written down.
         */
        public String id() {
            return id;
        }

        /**
         * The machine to build when nobody has said otherwise.
         */
        public Region region() {
            return region;
        }
    }

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
        var flags9 = Byte.toUnsignedInt(buffer.get());
        var flags10 = Byte.toUnsignedInt(buffer.get());

        // Bytes 11-15, which are the NES 2.0 fields and, in iNES, padding that is sometimes not
        // padding: see timing().
        var tail = new byte[INES_HEADER_SIZE - 11];
        buffer.position(11);
        buffer.get(tail);

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
            case 7 -> new Mapper7(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 9 -> new Mapper9(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 10 -> new Mapper10(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 11 -> new Mapper11(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 66 -> new Mapper66(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 71 -> new Mapper71(prgROM, chrROM, Mirroring.fromINES(mirror));
            default -> throw new UnsupportedMapperException(mapperNumber, filename);
        };

        return new Cart(
                filename, prgROM, chrROM, mapper, mapperNumber, mirror, hasBattery,
                timing(flags7, flags9, flags10, tail), sha256(bytes));
    }

    /**
     * What the header says about the console the cartridge was made for.
     * <p>
     * Three formats have had a go at this byte and they do not agree, so they are asked in order of
     * how much they can be trusted.
     * <p>
     * NES 2.0 is the only one that is any good: it announces itself in the top half of byte 7 and
     * puts the answer in the bottom two bits of byte 12, with four values rather than two. Failing
     * that, iNES 1.0 has byte 9 bit 0 and, from a later revision nobody much implemented, byte 10
     * bits 0 and 1 -- where 2 is PAL and 1 and 3 mean the game runs on either.
     * <p>
     * Those two are only read from a header whose last four bytes are zero, and that guard is the
     * whole reason this is not two lines. Dumps from the 1990s wrote the ripper's name across the
     * end of the header, and a byte of a signature lands in byte 9 as readily as anywhere else; a
     * cartridge declared PAL by the letter "P" of somebody's handle would be a mystifying thing to
     * debug. A header with anything in bytes 12 to 15 is not answering the question.
     */
    private static Timing timing(
            final int flags7, final int flags9, final int flags10, final byte[] tail) {
        if (((flags7 >> 2) & 0b11) == 2) {
            // Byte 12 of the file, which is the second byte of the tail read from 11.
            return switch (tail[1] & 0b11) {
                case 1 -> Timing.PAL;
                case 2 -> Timing.MULTI_REGION;
                case 3 -> Timing.DENDY;
                default -> Timing.NTSC;
            };
        }

        for (var i = 1; i < tail.length; i++) {
            if (tail[i] != 0) {
                return Timing.UNSTATED;
            }
        }

        if ((flags9 & 1) == 1) {
            return Timing.PAL;
        }

        return switch (flags10 & 0b11) {
            case 2 -> Timing.PAL;
            case 1, 3 -> Timing.MULTI_REGION;
            default -> Timing.UNSTATED;
        };
    }

    /**
     * The machine to build for this cartridge when nobody has overridden it.
     */
    public Region region() {
        return timing.region();
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
