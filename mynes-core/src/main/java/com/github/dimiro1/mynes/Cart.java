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
 * @param mapperNumber the mapper number the header asked for, kept alongside the mapper itself
 *                     because it is what a person names a cartridge's hardware by. Twelve bits
 *                     under NES 2.0, eight under iNES
 * @param submapper the NES 2.0 submapper, which names a variant of the board the mapper number
 *                  cannot tell apart on its own. Zero under iNES, and zero under NES 2.0 for the
 *                  ordinary board
 * @param mirror the mirroring mode (horizontal, vertical, four-screen)
 * @param hasBattery whether this cart has battery-backed save RAM
 * @param format which of the two header formats this was read as: see {@link Format}
 * @param ram what the header says is fitted on the board besides ROM: see {@link RAM}
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
        int submapper,
        int mirror,
        boolean hasBattery,
        Format format,
        RAM ram,
        Timing timing,
        String sha256) {

    /**
     * Which header was read.
     * <p>
     * The two formats share their first eight bytes and disagree about the rest, so which one a
     * file is decides what bytes 8 to 15 <em>mean</em> rather than merely how much of them is
     * filled in: byte 9 is the PAL flag under iNES and the top of the ROM sizes under NES 2.0.
     * Worth writing into a report for that reason -- a header field that looks wrong is usually a
     * header read as the other format.
     *
     * @see <a href="https://www.nesdev.org/wiki/NES_2.0">NESdev: NES 2.0</a>
     */
    public enum Format {
        /**
         * Plain iNES: the mapper in two nibbles, the sizes in one byte each, and a tail that is
         * padding when it is anything.
         */
        INES("ines"),

        /**
         * NES 2.0, announced by bits 2 and 3 of byte 7 reading 2. Twelve bits of mapper, a
         * submapper, sizes for every memory on the board, and the console it was made for.
         */
        NES20("nes2.0");

        private final String id;

        Format(final String id) {
            this.id = id;
        }

        /**
         * How this is spelled in a report, which is the only place it is written down.
         */
        public String id() {
            return id;
        }
    }

    /**
     * What the header says is fitted on the board besides ROM, in bytes.
     * <p>
     * NES 2.0 keeps four numbers: the RAM the CPU sees and the RAM the PPU sees, each split into the
     * part a battery keeps and the part it does not. A board that puts one chip on a battery and
     * another off it -- SOROM, with its two 8KB chips -- says so by filling both halves in. iNES
     * only ever had the one number, byte 8's count of 8KB units of PRG RAM, and it goes into
     * whichever half the battery bit says.
     * <p>
     * This is what the header <em>claims</em>. What a mapper fits is its own decision, and the
     * two differ in one direction on purpose: every board here carries at least 8KB at $6000
     * whether or not the header mentions it, because several of blargg's test ROMs report their
     * results through that window from a header that says nothing.
     *
     * @param prgRAM PRG RAM without a battery behind it
     * @param prgNVRAM PRG RAM a battery keeps
     * @param chrRAM CHR RAM without a battery behind it
     * @param chrNVRAM CHR RAM a battery keeps, which almost nothing has
     */
    public record RAM(int prgRAM, int prgNVRAM, int chrRAM, int chrNVRAM) {
        /**
         * Nothing at all, which is what a header that does not know says.
         */
        public static final RAM NONE = new RAM(0, 0, 0, 0);

        /**
         * Everything at $6000-$7FFF, battery or not. SOROM's two chips are one 16KB space to the
         * bank bit that switches between them, so this is the number a mapper sizes its array by.
         */
        public int prg() {
            return prgRAM + prgNVRAM;
        }
    }

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
     * What iNES byte 8 counts PRG RAM in, and what a zero there means: the spec says a header that
     * says nothing is to be read as one unit, for the sake of every ROM headered before the byte
     * meant anything.
     */
    private static final int INES_PRG_RAM_UNIT = 0x2000;

    /**
     * The nibble that turns a NES 2.0 ROM size from a count into an exponent: see
     * {@link #nes20ROMSize}.
     */
    private static final int NES20_EXPONENT_FORM = 0xF;

    /**
     * Loads a ROM file in either header format.
     * <p>
     * iNES is the format almost every dump is in, and NES 2.0 is the same first eight bytes with
     * the other eight finally meaning something. Both are read here rather than in two loaders
     * because the cartridge that comes out is the same kind of thing; the difference is in how much
     * the header is believed, and that is {@link #Cart} field by field.
     *
     * @param bytes the file as an array of bytes
     * @param filename the filename (for error reporting)
     * @return a Cart instance containing the loaded ROM data
     * @throws InvalidNesFileException if the file format is invalid
     * @throws UnsupportedMapperException if the mapper is not supported
     * @throws BufferUnderflowException if the file is truncated
     * @see <a href="https://www.nesdev.org/wiki/INES">NESdev: iNES</a>
     * @see <a href="https://www.nesdev.org/wiki/NES_2.0">NESdev: NES 2.0</a>
     */
    public static Cart load(final byte[] bytes, final String filename) {
        var buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        var header = new byte[INES_HEADER_SIZE];
        buffer.get(header);

        // Validate magic number ("NES\x1A")
        if (ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt() != INES_MAGIC) {
            throw new InvalidNesFileException(filename);
        }

        var flags6 = Byte.toUnsignedInt(header[6]);
        var flags7 = Byte.toUnsignedInt(header[7]);
        var format = ((flags7 >> 2) & 0b11) == 2 ? Format.NES20 : Format.INES;

        // Bytes 9 to 15 under iNES are padding that is sometimes not padding -- dumps from the
        // 1990s wrote the ripper's name across the end of the header -- so everything iNES keeps
        // past byte 8 is only believed out of a header whose last four bytes are zero. NES 2.0
        // announced itself, so its tail is read as written.
        var tailIsClean = header[12] == 0 && header[13] == 0 && header[14] == 0 && header[15] == 0;

        var prgROMSize = format == Format.NES20
                ? nes20ROMSize(header[4], header[9] & 0x0F, PRG_BANK_SIZE)
                : Byte.toUnsignedInt(header[4]) * (long) PRG_BANK_SIZE;
        var chrROMSize = format == Format.NES20
                ? nes20ROMSize(header[5], (header[9] >> 4) & 0x0F, CHR_BANK_SIZE)
                : Byte.toUnsignedInt(header[5]) * (long) CHR_BANK_SIZE;

        // Validate PRG-ROM exists, and that the file is long enough to hold what the header says
        // is in it. Long arithmetic because the exponent form can name a size no array holds, and
        // a header that does is a header the file cannot live up to rather than an overflow.
        if (prgROMSize == 0 || prgROMSize + chrROMSize > buffer.remaining() - trainerSize(flags6)) {
            throw new InvalidNesFileException(filename);
        }

        // Skip the trainer if there is one
        buffer.position(buffer.position() + trainerSize(flags6));

        // Extract flags
        boolean hasBattery = ByteUtils.getBit(1, flags6) == 1;
        int mapperNumber = ByteUtils.joinNibbles(
                ByteUtils.getHighNibble(flags7), ByteUtils.getHighNibble(flags6));
        var submapper = 0;

        if (format == Format.NES20) {
            // Byte 8 carries four more bits of mapper number below a submapper, which is how the
            // format got past 255 without touching the two nibbles every reader already knew.
            mapperNumber |= ByteUtils.getLowNibble(header[8]) << 8;
            submapper = ByteUtils.getHighNibble(header[8]);
        }

        int lowMirrorBit = ByteUtils.getBit(0, flags6);
        int highMirrorBit = ByteUtils.getBit(3, flags6);
        int mirror = ByteUtils.joinBits(highMirrorBit, lowMirrorBit);

        // Read PRG-ROM
        var prgROM = new byte[(int) prgROMSize];
        buffer.get(prgROM);

        // Read CHR-ROM (can be empty for CHR-RAM carts)
        var chrROM = new byte[(int) chrROMSize];
        buffer.get(chrROM);

        var ram = format == Format.NES20
                ? nes20RAM(header[10], header[11])
                : inesRAM(header[8], hasBattery, chrROMSize == 0, tailIsClean);

        // Create mapper instance
        var mapper = switch (mapperNumber) {
            case 0 -> new Mapper0(prgROM, chrROM, Mirroring.fromINES(mirror));
            case 1, 155 -> new Mapper1(
                    prgROM, chrROM, Mirroring.fromINES(mirror), ram.prg(),
                    mapperNumber == 155 ? Mapper1.SUBMAPPER_MMC1A : submapper);
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
                filename, prgROM, chrROM, mapper, mapperNumber, submapper, mirror, hasBattery,
                format, ram, timing(format, header, tailIsClean), sha256(bytes));
    }

    /**
     * A NES 2.0 ROM size, out of the low byte and the nibble byte 9 adds above it.
     * <p>
     * Twelve bits of 16KB banks is 64MB, which nothing needs, so a nibble of $F means the byte
     * below it is not a count: its top six bits are an exponent and its bottom two a multiplier,
     * for a size of 2^E * (MM*2+1). That is how a board whose ROM is not a whole number of banks
     * -- a 24KB one, say -- gets a header at all. A size in that form is not checked against the
     * bank, since not being a multiple of the bank is the reason for it.
     */
    private static long nes20ROMSize(final byte low, final int high, final int bankSize) {
        var lowBits = Byte.toUnsignedInt(low);

        if (high == NES20_EXPONENT_FORM) {
            var exponent = lowBits >> 2;
            var multiplier = (lowBits & 0b11) * 2 + 1;

            return (1L << exponent) * multiplier;
        }

        return (high << 8 | lowBits) * (long) bankSize;
    }

    private static int trainerSize(final int flags6) {
        return ByteUtils.getBit(2, flags6) == 1 ? TRAINER_SIZE : 0;
    }

    /**
     * NES 2.0 bytes 10 and 11: four shift counts, one nibble each, each meaning 64 bytes shifted
     * left that many places -- and zero meaning none, rather than 64.
     */
    private static RAM nes20RAM(final byte byte10, final byte byte11) {
        return new RAM(
                nes20RAMSize(byte10 & 0x0F),
                nes20RAMSize((byte10 >> 4) & 0x0F),
                nes20RAMSize(byte11 & 0x0F),
                nes20RAMSize((byte11 >> 4) & 0x0F));
    }

    private static int nes20RAMSize(final int shift) {
        return shift == 0 ? 0 : 64 << shift;
    }

    /**
     * What iNES has to say about RAM, which is one byte, and that one only half meant.
     * <p>
     * Byte 8 counts PRG RAM in 8KB units and says zero means one unit, because the byte was added
     * after most of the library had already been headered with it blank. It is read under the same
     * guard as the PAL flag beside it: a signature across the tail lands a letter in byte 8 as
     * readily as in byte 9, and a cartridge given nine hundred kilobytes of RAM by somebody's
     * handle would be a strange thing to debug. A header that is not clean gets the one unit.
     * <p>
     * The battery bit decides which half the number goes in, since the format has no way to split
     * it. CHR RAM is not in the format at all; a board with no CHR ROM has 8KB, which is what every
     * such board carried.
     */
    private static RAM inesRAM(
            final byte byte8, final boolean battery, final boolean chrIsRAM, final boolean clean) {
        var units = clean ? Byte.toUnsignedInt(byte8) : 0;
        var prg = Math.max(1, units) * INES_PRG_RAM_UNIT;
        var chr = chrIsRAM ? CHR_BANK_SIZE : 0;

        return battery ? new RAM(0, prg, chr, 0) : new RAM(prg, 0, chr, 0);
    }

    /**
     * What the header says about the console the cartridge was made for.
     * <p>
     * Three formats have had a go at this byte and they do not agree, so they are asked in order of
     * how much they can be trusted.
     * <p>
     * NES 2.0 is the only one that is any good: it puts the answer in the bottom two bits of byte
     * 12, with four values rather than two. Failing that, iNES 1.0 has byte 9 bit 0 and, from a
     * later revision nobody much implemented, byte 10 bits 0 and 1 -- where 2 is PAL and 1 and 3
     * mean the game runs on either.
     * <p>
     * Those two are only read from a header whose tail is clean, for the reason given where that
     * is decided: a cartridge declared PAL by the letter "P" of somebody's handle would be a
     * mystifying thing to debug. A header with anything in bytes 12 to 15 is not answering the
     * question.
     */
    private static Timing timing(final Format format, final byte[] header, final boolean clean) {
        if (format == Format.NES20) {
            return switch (header[12] & 0b11) {
                case 1 -> Timing.PAL;
                case 2 -> Timing.MULTI_REGION;
                case 3 -> Timing.DENDY;
                default -> Timing.NTSC;
            };
        }

        if (!clean) {
            return Timing.UNSTATED;
        }

        if ((header[9] & 1) == 1) {
            return Timing.PAL;
        }

        return switch (header[10] & 0b11) {
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
