package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * The whole console in a file.
 * <p>
 * There is no standard format for this and there never has been: cross-emulator save states have
 * been proposed on NESdev more than once and abandoned every time, because every emulator models
 * the pipeline differently and there are hundreds of mappers. Nothing else has this machine's
 * {@code evaluationStep}, or its OAM decay table keyed off an absolute dot counter. So this format
 * is ours, and the only question worth asking of it is whether it survives its own future.
 * <p>
 * It does so through length-prefixed chunks, which buy tolerance in four separate ways:
 * <ul>
 *   <li>A chunk <em>longer</em> than this build expects: the container skips to the end by the
 *       declared length rather than by what was read, so a field added later is harmless.</li>
 *   <li>A chunk <em>shorter</em> than expected: {@link StateIO} hands back the value it was given,
 *       so a field added later keeps whatever the machine already had.</li>
 *   <li>A tag this build has never heard of: skipped by its length.</li>
 *   <li>A <em>required</em> tag that is missing: refused. Half a machine from the file and half
 *       from whatever was running is the one outcome worse than not loading at all.</li>
 * </ul>
 * The tolerance is a courtesy rather than a contract. <strong>A save state is a bookmark, not an
 * archive.</strong> A field whose stale value would be harmful means bumping {@link #VERSION}, and
 * a file from a later version than this one is refused outright -- an unknown chunk can be skipped,
 * but a known chunk that has quietly changed meaning cannot be spotted.
 * <p>
 * The header is deliberately outside the compression. A slot menu wants to say "Slot 3 -- frame
 * 41,207" for nine files at the moment it opens, and refusing a state from the wrong cartridge
 * should not cost a hundred and fifty kilobytes of inflation to discover.
 */
public final class SaveState {

    /**
     * "MYNESST" and the same trailing SUB an iNES header carries, which stops {@code cat} on a
     * terminal before it has finished redecorating it.
     */
    private static final byte[] MAGIC = {'M', 'Y', 'N', 'E', 'S', 'S', 'T', 0x1A};

    /**
     * Bumped when something already in the format changes meaning -- not when something is added,
     * which the chunk lengths already carry.
     */
    public static final int VERSION = 1;

    /**
     * Everything before the body. Fixed, so the body can be found without reading the header.
     */
    static final int HEADER_BYTES = 56;

    private static final int OFFSET_VERSION = 8;
    private static final int OFFSET_SHA256 = 10;
    private static final int OFFSET_MAPPER = 42;
    private static final int OFFSET_FLAGS = 43;
    private static final int OFFSET_FRAME = 44;
    private static final int OFFSET_BODY_LENGTH = 52;

    private static final int SHA256_BYTES = 32;

    /**
     * Bit 0 of the flags byte: the body is gzipped.
     */
    private static final int FLAG_GZIPPED = 0x01;

    /**
     * Bit 1: the machine was a PAL one. Every other bit is reserved and written zero.
     * <p>
     * Taking a reserved bit does not need {@link #VERSION} bumping, because every file written
     * before this bit meant anything has it clear -- and clear is NTSC, which is what those
     * machines all were. The bit is only a lie if a file claims to be something it never could
     * have been.
     */
    private static final int FLAG_PAL = 0x02;

    private static final String TAG_CPU = "CPU ";
    private static final String TAG_PPU = "PPU ";
    private static final String TAG_APU = "APU ";
    private static final String TAG_BUS = "BUS ";
    private static final String TAG_MMU = "MMU ";
    private static final String TAG_MAPPER = "MAPR";
    private static final String TAG_CONTROLLER1 = "CTL1";
    private static final String TAG_CONTROLLER2 = "CTL2";
    private static final String TAG_FRAMEBUFFER = "VBUF";

    /**
     * The chunks whose absence cannot be recovered from. The controllers and the framebuffer are
     * not among them: a missing shift register is right again as soon as the game strobes the port,
     * and a missing picture is redrawn by the next frame.
     */
    private static final List<String> REQUIRED = List.of(
            TAG_CPU, TAG_PPU, TAG_APU, TAG_BUS, TAG_MMU, TAG_MAPPER);

    private static final int TAG_BYTES = 4;

    private SaveState() {
    }

    /**
     * What a file says about itself, without inflating it.
     *
     * @param formatVersion the format it was written in.
     * @param romSHA256     which cartridge it came from, as lowercase hex.
     * @param mapperNumber  that cartridge's mapper, for the error message rather than for identity.
     * @param frame         the frame it was taken on, so a menu can label it.
     * @param region        which machine it was taken from. Part of what a state can be refused
     *                      for: the same cartridge runs on either, and where the beam is halfway
     *                      through a 312 line frame is nonsense to a chip with 262 of them.
     */
    public record Header(
            int formatVersion, String romSHA256, int mapperNumber, long frame, Region region) {
    }

    // ==================================================================================== writing

    /**
     * Writes the machine out.
     */
    public static void write(final NES nes, final OutputStream out) throws IOException {
        var body = body(nes);
        var header = new byte[HEADER_BYTES];

        System.arraycopy(MAGIC, 0, header, 0, MAGIC.length);
        putShort(header, OFFSET_VERSION, VERSION);
        System.arraycopy(hexToBytes(nes.getCart().sha256()), 0, header, OFFSET_SHA256, SHA256_BYTES);
        header[OFFSET_MAPPER] = (byte) nes.getCart().mapperNumber();
        header[OFFSET_FLAGS] =
                (byte) (FLAG_GZIPPED | (nes.getRegion() == Region.PAL ? FLAG_PAL : 0));
        putLong(header, OFFSET_FRAME, nes.getPPU().getFrame());
        putInt(header, OFFSET_BODY_LENGTH, body.length);

        out.write(header);

        // Finished rather than closed, so a caller's try-with-resources still owns the stream.
        var gzip = new GZIPOutputStream(out);
        gzip.write(body);
        gzip.finish();
    }

    /**
     * Writes the machine to a file, and does not destroy what was there until it has worked.
     * <p>
     * Through a temporary and a move, because the alternative is that a crash halfway through
     * overwriting slot 3 loses both the new state and the old one.
     */
    public static void write(final NES nes, final Path path) throws IOException {
        var temporary = path.resolveSibling(path.getFileName() + ".tmp");

        try (var out = Files.newOutputStream(temporary)) {
            write(nes, out);
        }

        Files.move(temporary, path,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private static byte[] body(final NES nes) throws IOException {
        var body = new ByteArrayOutputStream();

        chunk(body, TAG_CPU, serialised(nes.getCPU()::serialize));
        chunk(body, TAG_PPU, serialised(nes.getPPU()::serialize));
        chunk(body, TAG_APU, serialised(nes.getAPU()::serialize));
        chunk(body, TAG_BUS, serialised(nes.getBus()::serialize));
        chunk(body, TAG_MMU, serialised(nes.getMemory()::serialize));
        chunk(body, TAG_MAPPER, serialised(nes.getBus().getMapper()::serialize));
        chunk(body, TAG_CONTROLLER1, serialised(nes.getController1()::serialize));
        chunk(body, TAG_CONTROLLER2, serialised(nes.getController2()::serialize));
        chunk(body, TAG_FRAMEBUFFER, serialised(io -> io.words(nes.getPPU().getFrameBuffer())));

        return body.toByteArray();
    }

    private static byte[] serialised(final Consumer<StateIO> component) {
        var io = StateIO.writing();

        component.accept(io);

        return io.written();
    }

    private static void chunk(
            final ByteArrayOutputStream body, final String tag, final byte[] payload)
            throws IOException {
        body.write(tag.getBytes(StandardCharsets.US_ASCII));

        var length = new byte[4];
        putInt(length, 0, payload.length);

        body.write(length);
        body.write(payload);
    }

    // ==================================================================================== reading

    /**
     * Puts a machine back.
     * <p>
     * Everything the file can be wrong about is checked before the first field is assigned, because
     * the chunks are applied to the live machine in place and there is no way to take that back. A
     * refused state leaves the machine exactly as it was.
     *
     * @throws SaveStateException if the file is not one, is from a later version, belongs to another
     *                            cartridge, is damaged, or is missing a chunk that matters.
     */
    public static void read(final NES nes, final InputStream in) throws IOException {
        var file = in.readAllBytes();

        if (file.length < HEADER_BYTES) {
            throw new SaveStateException("that file is too short to be a save state.");
        }

        if (!Arrays.equals(file, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new SaveStateException("that is not a save state.");
        }

        var header = header(file);

        if (header.formatVersion() > VERSION) {
            throw new SaveStateException(
                    "that save state is version " + header.formatVersion()
                            + " and this build only understands up to " + VERSION + ".");
        }

        var cart = nes.getCart();

        if (!header.romSHA256().equals(cart.sha256())) {
            throw new SaveStateException(
                    "that save state was taken from another cartridge. It belongs to mapper "
                            + header.mapperNumber() + " " + header.romSHA256().substring(0, 12)
                            + ", and the one in the machine is mapper " + cart.mapperNumber() + " "
                            + cart.sha256().substring(0, 12) + ".");
        }

        if (header.region() != nes.getRegion()) {
            throw new SaveStateException(
                    "that save state was taken from a " + header.region().label()
                            + " machine and this one is " + nes.getRegion().label()
                            + ". The cartridge is right, but the two chips do not agree on how big"
                            + " a frame is.");
        }

        var declared = readInt(file, OFFSET_BODY_LENGTH);
        var body = inflate(file, (file[OFFSET_FLAGS] & FLAG_GZIPPED) != 0);

        if (body.length != declared) {
            throw new SaveStateException(
                    "that save state is damaged: it says " + declared + " bytes and holds "
                            + body.length + ".");
        }

        var chunks = chunks(body);

        for (var tag : REQUIRED) {
            if (!chunks.containsKey(tag)) {
                throw new SaveStateException(
                        "that save state has no \"" + tag.trim() + "\" in it, so there is no way to"
                                + " put the machine back without inventing half of it.");
            }
        }

        // Nothing above this line has touched the machine, and nothing below it can fail.
        apply(chunks, TAG_CPU, nes.getCPU()::serialize);
        apply(chunks, TAG_PPU, nes.getPPU()::serialize);
        apply(chunks, TAG_APU, nes.getAPU()::serialize);
        apply(chunks, TAG_MMU, nes.getMemory()::serialize);
        apply(chunks, TAG_MAPPER, nes.getBus().getMapper()::serialize);
        apply(chunks, TAG_CONTROLLER1, nes.getController1()::serialize);
        apply(chunks, TAG_CONTROLLER2, nes.getController2()::serialize);
        apply(chunks, TAG_FRAMEBUFFER, io -> io.words(nes.getPPU().getFrameBuffer()));

        // Last, because it drives /IRQ from the three level bits and the sources of two of them --
        // the APU's frame counter and its DMC -- have only just arrived.
        apply(chunks, TAG_BUS, nes.getBus()::serialize);
    }

    /**
     * Puts a machine back from a file.
     */
    public static void read(final NES nes, final Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            read(nes, in);
        }
    }

    /**
     * Where a numbered slot for a ROM lives: beside it, with the extension replaced by {@code .mn}
     * and the slot number.
     * <p>
     * Beside the ROM rather than in a directory of its own, so that a game and its states travel
     * together -- the same reason a battery file goes there, and the one convention this and
     * {@link BatteryRAM} share.
     */
    public static Path slotPath(final Path rom, final int slot) {
        var name = rom.getFileName().toString();
        var dot = name.lastIndexOf('.');

        return rom.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".mn" + slot);
    }

    /**
     * What the file says about itself. Reads the header alone, which is why it is not compressed.
     */
    public static Header header(final Path path) throws IOException {
        var header = new byte[HEADER_BYTES];

        try (var in = Files.newInputStream(path)) {
            if (in.readNBytes(header, 0, HEADER_BYTES) < HEADER_BYTES) {
                throw new SaveStateException("that file is too short to be a save state.");
            }
        }

        if (!Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new SaveStateException("that is not a save state.");
        }

        return header(header);
    }

    private static Header header(final byte[] file) {
        var sha256 = new byte[SHA256_BYTES];
        System.arraycopy(file, OFFSET_SHA256, sha256, 0, SHA256_BYTES);

        return new Header(
                readShort(file, OFFSET_VERSION),
                bytesToHex(sha256),
                Byte.toUnsignedInt(file[OFFSET_MAPPER]),
                readLong(file, OFFSET_FRAME),
                (file[OFFSET_FLAGS] & FLAG_PAL) != 0 ? Region.PAL : Region.NTSC);
    }

    private static byte[] inflate(final byte[] file, final boolean gzipped) throws IOException {
        var body = Arrays.copyOfRange(file, HEADER_BYTES, file.length);

        if (!gzipped) {
            return body;
        }

        // readAllBytes checks the trailing CRC32, so a damaged file is caught here rather than
        // showing up later as a machine that behaves oddly.
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return gzip.readAllBytes();
        } catch (EOFException | ZipException e) {
            throw new SaveStateException("that save state is damaged.", e);
        }
    }

    private static Map<String, byte[]> chunks(final byte[] body) {
        var chunks = new LinkedHashMap<String, byte[]>();
        var position = 0;

        while (position < body.length) {
            if (body.length - position < TAG_BYTES + 4) {
                throw new SaveStateException("that save state is damaged: it stops mid-chunk.");
            }

            var tag = new String(body, position, TAG_BYTES, StandardCharsets.US_ASCII);
            var length = readInt(body, position + TAG_BYTES);
            var payload = position + TAG_BYTES + 4;

            if (length < 0 || length > body.length - payload) {
                throw new SaveStateException(
                        "that save state is damaged: \"" + tag.trim() + "\" claims " + length
                                + " bytes and there are only " + (body.length - payload) + " left.");
            }

            chunks.put(tag, Arrays.copyOfRange(body, payload, payload + length));

            // By the declared length rather than by what anything read, which is what lets a chunk
            // that has grown since this build was written be stepped over cleanly.
            position = payload + length;
        }

        return chunks;
    }

    private static void apply(
            final Map<String, byte[]> chunks, final String tag, final Consumer<StateIO> component) {
        var payload = chunks.get(tag);

        if (payload != null) {
            component.accept(StateIO.reading(payload));
        }
    }

    // ================================================================================= plain bytes

    private static void putShort(final byte[] target, final int offset, final int value) {
        target[offset] = (byte) (value >> 8);
        target[offset + 1] = (byte) value;
    }

    private static void putInt(final byte[] target, final int offset, final int value) {
        for (var i = 0; i < 4; i++) {
            target[offset + i] = (byte) (value >> (24 - i * 8));
        }
    }

    private static void putLong(final byte[] target, final int offset, final long value) {
        for (var i = 0; i < 8; i++) {
            target[offset + i] = (byte) (value >> (56 - i * 8));
        }
    }

    private static int readShort(final byte[] source, final int offset) {
        return Byte.toUnsignedInt(source[offset]) << 8 | Byte.toUnsignedInt(source[offset + 1]);
    }

    private static int readInt(final byte[] source, final int offset) {
        var value = 0;

        for (var i = 0; i < 4; i++) {
            value = value << 8 | Byte.toUnsignedInt(source[offset + i]);
        }

        return value;
    }

    private static long readLong(final byte[] source, final int offset) {
        var value = 0L;

        for (var i = 0; i < 8; i++) {
            value = value << 8 | Byte.toUnsignedInt(source[offset + i]);
        }

        return value;
    }

    private static String bytesToHex(final byte[] bytes) {
        var hex = new StringBuilder(bytes.length * 2);

        for (var b : bytes) {
            hex.append(String.format("%02x", b));
        }

        return hex.toString();
    }

    private static byte[] hexToBytes(final String hex) {
        var bytes = new byte[hex.length() / 2];

        for (var i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }

        return bytes;
    }
}
