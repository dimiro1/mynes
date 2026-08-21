package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Region;
import com.github.dimiro1.mynes.cheat.GameGenieCode;
import com.github.dimiro1.mynes.cheat.InvalidGameGenieCodeException;

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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipException;

/**
 * A session somebody played, small enough to hand to anybody with the same cartridge.
 *
 * <h2>Why this is a list of buttons and not a list of frames</h2>
 *
 * The machine is deterministic: nothing in it reads a clock or a random number, so the same
 * cartridge given the same buttons on the same frames arrives at the same bytes every time. That is
 * what {@code SaveStateDivergenceTests} and {@code RewindTests} are already built on, and it is what
 * lets a movie be four things and nothing else -- <strong>where it started, one button mask per
 * finished frame, which frames the Reset button was pressed at the start of, and the facts that
 * change how a cartridge runs but live outside a save state</strong>. Ninety seconds of play is five
 * and a half thousand bytes before the gzip gets to it, and a replay reproduces the run byte for
 * byte rather than approximately.
 * <p>
 * Those outside facts are the ones worth naming, because getting any of them wrong is a replay that
 * quietly diverges rather than one that refuses: the ROM's digest (of the <em>patched</em> image, so
 * a romhack is pinned for free), the region, and the Game Genie codes -- which are the sharp case,
 * since a cheated cartridge is byte for byte the honest one and nothing else in a file would say so.
 *
 * <h2>Rewinds are not in it</h2>
 *
 * Rewinding while recording truncates the log rather than appending to it, so a movie holds the
 * timeline that was finally played and never the one that was taken back. This is not an
 * approximation to be apologised for: a rewound machine <em>is</em> the machine that never went
 * forward, byte for byte, which {@code RewindTests.rewindingGoesBackToTheFrameItLeft} proves
 * separately. So replaying the truncated log lands exactly where the session ended up.
 *
 * <h2>Instruction-level fidelity is out of scope</h2>
 *
 * Buttons are latched once per frame and a reset is applied at a frame's start. A session that
 * stepped instructions and pressed Reset half way through a frame records that reset at the next
 * frame boundary, and the replay applies it a fraction of a frame earlier than it happened. Every
 * ordinary way of driving the machine -- a window, a one-shot run, {@code run N} in the REPL --
 * changes the pad only at frame boundaries, so this costs nothing outside a debugging session.
 *
 * <h2>The file</h2>
 *
 * {@code .mnm}, and {@link SaveState}'s discipline exactly: big endian, a fixed header outside the
 * gzip so {@link #header(Path)} can label a file chooser without inflating it, length-prefixed
 * chunks that a later version can add to and this one steps over, and a version that is bumped only
 * when something already in the format changes meaning. Everything the file can be wrong about is
 * checked in {@link #read}, before there is a machine to touch.
 * <p>
 * The anchor is a whole {@link SaveState} file nested inside the body rather than unpacked into it.
 * That double-gzips a few kilobytes, and buys the thing worth having: there is exactly one tested
 * way of putting a machine back, and a movie uses it.
 * <p>
 * There is no player class. The accessors below are pure decision functions in the shape of the
 * headless {@code InputSchedule}, and the cursor lives in whichever loop is driving -- which is what
 * lets the same file be played by a one-shot run, by a REPL and by the window's emulation thread
 * without any of them sharing a mutable object.
 */
public final class Movie {

    /**
     * "MYNESMV" and the same trailing SUB an iNES header carries, which stops {@code cat} on a
     * terminal before it has finished redecorating it.
     */
    private static final byte[] MAGIC = {'M', 'Y', 'N', 'E', 'S', 'M', 'V', 0x1A};

    /**
     * Bumped when something already in the format changes meaning -- not when something is added,
     * which the chunk lengths already carry.
     */
    public static final int VERSION = 1;

    /**
     * Everything before the body. Fixed, so the body can be found without reading the header.
     */
    static final int HEADER_BYTES = 68;

    private static final int OFFSET_VERSION = 8;
    private static final int OFFSET_SHA256 = 10;
    private static final int OFFSET_MAPPER = 42;
    private static final int OFFSET_FLAGS = 43;
    private static final int OFFSET_ANCHOR_FRAME = 44;
    private static final int OFFSET_FRAME_COUNT = 52;
    private static final int OFFSET_PORTS = 60;
    private static final int OFFSET_BODY_LENGTH = 64;

    private static final int SHA256_BYTES = 32;

    /**
     * Bit 0 of the flags byte: the body is gzipped.
     */
    private static final int FLAG_GZIPPED = 0x01;

    /**
     * Bit 1: the machine was a PAL one, which is the {@link SaveState} bit and means the same thing.
     */
    private static final int FLAG_PAL = 0x02;

    /**
     * Bit 2: there is a save state in the body and the movie starts from it rather than from power
     * on. Every other bit is reserved and written zero.
     */
    private static final int FLAG_ANCHORED = 0x04;

    /**
     * A complete {@link SaveState} file, byte for byte. Present exactly when {@link #FLAG_ANCHORED}
     * is set.
     */
    private static final String TAG_ANCHOR = "ANCH";

    /**
     * One button mask per frame, raw. Mask <i>i</i> is the mask in force from the anchor's frame
     * plus <i>i</i> to the frame after it. Raw rather than run-length encoded because the gzip
     * crushes the runs and a second encoding is a second thing to get wrong.
     */
    private static final String TAG_CONTROLLER1 = "CTL1";

    /**
     * The same shape for player two, which nothing wires up today. Never written by this version and
     * applied by a reader only when the header says there are two ports, so a movie recorded by a
     * later build that does wire it will still play its first player here.
     */
    private static final String TAG_CONTROLLER2 = "CTL2";

    /**
     * Frame indices, each a u64, strictly increasing: the Reset button was pressed at the start of
     * that frame. Sparse because resets are rare, and absent altogether when there were none.
     */
    private static final String TAG_RESETS = "RSET";

    /**
     * The Game Genie codes that were in when the recording started, each a length byte and its ASCII
     * letters. The one thing in a movie that a cartridge digest could never stand in for.
     */
    private static final String TAG_GENIE = "GENI";

    private static final int TAG_BYTES = 4;

    /**
     * How many controller lanes a movie written by this build carries.
     */
    public static final int PORTS = 1;

    /**
     * How long a chunk this will inflate before deciding the file is lying to it. Generous -- a
     * movie of a whole evening is a few megabytes of masks, and the anchor is a state -- and there
     * only so that a corrupt length cannot ask for an array the size of the heap.
     */
    private static final int MAX_CHUNK_BYTES = 256 * 1024 * 1024;

    private final Header header;

    /**
     * The whole save state the movie starts from, or null when it starts at power on.
     */
    private final byte[] anchor;

    private final byte[] player1;

    /**
     * Null unless a later build wrote a second lane and this file has one.
     */
    private final byte[] player2;

    private final long[] resets;

    private final List<GameGenieCode> genie;

    /**
     * What a file says about itself, without inflating it.
     *
     * @param formatVersion the format it was written in.
     * @param romSHA256     which cartridge it was recorded on, as lowercase hex. Of the patched
     *                      image when there was a patch, since that is what actually ran.
     * @param mapperNumber  that cartridge's mapper, for the error message rather than for identity.
     * @param region        which machine it was recorded on. A movie is a count of frames and a PAL
     *                      frame is not an NTSC one, so this is refused rather than converted.
     * @param anchored      whether it starts from a save state carried inside it.
     * @param anchorFrame   the PPU frame the recording started on. 0 for a movie from power on.
     * @param frameCount    how many frames it holds.
     * @param ports         how many controller lanes are in the body. 1 in this version.
     */
    public record Header(
            int formatVersion,
            String romSHA256,
            int mapperNumber,
            Region region,
            boolean anchored,
            long anchorFrame,
            long frameCount,
            int ports) {
    }

    /**
     * Package-private: a movie arrives either from {@link #read} or from a {@link MovieRecorder},
     * and both are here. The arrays are taken as given rather than copied, since neither caller
     * keeps a reference to one.
     */
    Movie(
            final Header header,
            final byte[] anchor,
            final byte[] player1,
            final byte[] player2,
            final long[] resets,
            final List<GameGenieCode> genie
    ) {
        this.header = header;
        this.anchor = anchor;
        this.player1 = player1;
        this.player2 = player2;
        this.resets = resets;
        this.genie = List.copyOf(genie);
    }

    public Header header() {
        return header;
    }

    /**
     * How many frames were recorded.
     */
    public long frameCount() {
        return header.frameCount();
    }

    /**
     * Whether it starts from a state carried inside it rather than from power on.
     */
    public boolean anchored() {
        return header.anchored();
    }

    /**
     * The frame the recording started on, which is 0 for a movie from power on.
     */
    public long anchorFrame() {
        return header.anchorFrame();
    }

    /**
     * What player one was holding for the {@code index}th frame of the movie, counting from zero.
     * <p>
     * <strong>Zero past the end</strong>, and past either end. Running a replay longer than the
     * movie is a legitimate thing to want -- watch what the game does when the player stops playing
     * -- and the honest answer for a frame nobody recorded is that nobody was touching the pad.
     */
    public int buttonsAt(final long index) {
        return index >= 0 && index < player1.length ? Byte.toUnsignedInt(player1[(int) index]) : 0;
    }

    /**
     * The same for player two, which is always 0 for a movie this build recorded.
     */
    public int buttons2At(final long index) {
        return player2 != null && index >= 0 && index < player2.length
                ? Byte.toUnsignedInt(player2[(int) index])
                : 0;
    }

    /**
     * Whether the Reset button was pressed at the start of the {@code index}th frame.
     */
    public boolean resetsAt(final long index) {
        return Arrays.binarySearch(resets, index) >= 0;
    }

    /**
     * The frames Reset was pressed at the start of, as movie-relative indices.
     */
    public long[] resets() {
        return resets.clone();
    }

    /**
     * The Game Genie codes that were in the cartridge slot, decoded when the file was read. A replay
     * has to put these back, since the cartridge is untouched by them and nothing else in the file
     * would tell a cheated recording from an honest one.
     */
    public List<GameGenieCode> genie() {
        return genie;
    }

    /**
     * Puts the machine where the recording started.
     * <p>
     * Everything that could be wrong is checked before the machine is touched, exactly as
     * {@link SaveState#read} does it and for the same reason: an anchor applied halfway would leave
     * a console that is half one game and half another.
     *
     * @throws MovieException if it was recorded on another cartridge or another machine, or if it
     *                        starts at power on and this machine has already run.
     */
    public void applyAnchor(final NES nes) {
        var cart = nes.getCart();

        if (!header.romSHA256().equals(cart.sha256())) {
            throw new MovieException(
                    "that movie was recorded on another cartridge. It belongs to mapper "
                            + header.mapperNumber() + " " + header.romSHA256().substring(0, 12)
                            + ", and the one in the machine is mapper " + cart.mapperNumber() + " "
                            + cart.sha256().substring(0, 12) + ".");
        }

        if (header.region() != nes.getRegion()) {
            throw new MovieException(
                    "that movie was recorded on a " + header.region().label()
                            + " machine and this one is " + nes.getRegion().label()
                            + ". The cartridge is right, but a frame is not the same length on the"
                            + " two, so playing the buttons back would drift apart immediately.");
        }

        if (anchor == null) {
            if (nes.getPPU().getFrame() != 0) {
                throw new MovieException(
                        "that movie starts at power on and this machine is already at frame "
                                + nes.getPPU().getFrame() + ". Start it again from the beginning.");
            }

            return;
        }

        try {
            SaveState.read(nes, new ByteArrayInputStream(anchor));
        } catch (IOException e) {
            throw new AssertionError("a state read from memory cannot fail", e);
        }
    }

    // ==================================================================================== writing

    /**
     * Writes the movie out.
     */
    public void write(final OutputStream out) throws IOException {
        var body = body();
        var bytes = new byte[HEADER_BYTES];

        System.arraycopy(MAGIC, 0, bytes, 0, MAGIC.length);
        SaveState.putShort(bytes, OFFSET_VERSION, VERSION);
        System.arraycopy(
                SaveState.hexToBytes(header.romSHA256()), 0, bytes, OFFSET_SHA256, SHA256_BYTES);
        bytes[OFFSET_MAPPER] = (byte) header.mapperNumber();
        bytes[OFFSET_FLAGS] = (byte) (FLAG_GZIPPED
                | (header.region() == Region.PAL ? FLAG_PAL : 0)
                | (anchor != null ? FLAG_ANCHORED : 0));
        SaveState.putLong(bytes, OFFSET_ANCHOR_FRAME, header.anchorFrame());
        SaveState.putLong(bytes, OFFSET_FRAME_COUNT, header.frameCount());
        bytes[OFFSET_PORTS] = (byte) header.ports();
        SaveState.putInt(bytes, OFFSET_BODY_LENGTH, body.length);

        out.write(bytes);

        // Finished rather than closed, so a caller's try-with-resources still owns the stream.
        var gzip = new GZIPOutputStream(out);
        gzip.write(body);
        gzip.finish();
    }

    /**
     * Writes the movie to a file, and does not destroy what was there until it has worked.
     * <p>
     * Through a temporary and a move, for the reason {@link SaveState#write(NES, Path)} is: a take
     * somebody has just played is worth more than a partially overwritten file.
     */
    public void write(final Path path) throws IOException {
        var temporary = path.resolveSibling(path.getFileName() + ".tmp");
        var parent = path.getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        try (var out = Files.newOutputStream(temporary)) {
            write(out);
        }

        Files.move(temporary, path,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    private byte[] body() throws IOException {
        var body = new ByteArrayOutputStream();

        if (anchor != null) {
            chunk(body, TAG_ANCHOR, anchor);
        }

        chunk(body, TAG_CONTROLLER1, player1);

        if (resets.length > 0) {
            var bytes = new byte[resets.length * 8];

            for (var i = 0; i < resets.length; i++) {
                SaveState.putLong(bytes, i * 8, resets[i]);
            }

            chunk(body, TAG_RESETS, bytes);
        }

        if (!genie.isEmpty()) {
            var codes = new ByteArrayOutputStream();

            for (var code : genie) {
                var letters = code.text().getBytes(StandardCharsets.US_ASCII);

                codes.write(letters.length);
                codes.write(letters);
            }

            chunk(body, TAG_GENIE, codes.toByteArray());
        }

        return body.toByteArray();
    }

    private static void chunk(
            final ByteArrayOutputStream body, final String tag, final byte[] payload)
            throws IOException {
        body.write(tag.getBytes(StandardCharsets.US_ASCII));

        var length = new byte[4];
        SaveState.putInt(length, 0, payload.length);

        body.write(length);
        body.write(payload);
    }

    // ==================================================================================== reading

    /**
     * Reads a movie, checking everything about it.
     * <p>
     * Nothing here needs a machine, and that is deliberate: a front end can refuse a file, name what
     * is wrong with it and carry on playing whatever it was playing. {@link #applyAnchor} is where a
     * machine first comes into it.
     *
     * @throws MovieException if it is not a movie, is from a later version, or is damaged in any of
     *                        the ways the structure can be damaged.
     */
    public static Movie read(final InputStream in) throws IOException {
        var file = in.readAllBytes();

        if (file.length < HEADER_BYTES) {
            throw new MovieException("that file is too short to be a movie.");
        }

        if (!Arrays.equals(file, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new MovieException("that is not a movie.");
        }

        var header = header(file);

        if (header.formatVersion() > VERSION) {
            throw new MovieException(
                    "that movie is version " + header.formatVersion()
                            + " and this build only understands up to " + VERSION + ".");
        }

        var declared = SaveState.readInt(file, OFFSET_BODY_LENGTH);
        var body = inflate(file, (file[OFFSET_FLAGS] & FLAG_GZIPPED) != 0);

        if (body.length != declared) {
            throw new MovieException(
                    "that movie is damaged: it says " + declared + " bytes and holds "
                            + body.length + ".");
        }

        var chunks = chunks(body);
        var player1 = chunks.get(TAG_CONTROLLER1);

        if (player1 == null) {
            throw new MovieException(
                    "that movie has no \"CTL1\" in it, so there are no buttons to play back.");
        }

        if (header.frameCount() != player1.length) {
            throw new MovieException(
                    "that movie is damaged: it says " + header.frameCount() + " frames and holds "
                            + player1.length + " button masks.");
        }

        var anchor = chunks.get(TAG_ANCHOR);

        if (header.anchored() != (anchor != null)) {
            throw new MovieException(header.anchored()
                    ? "that movie says it starts from a save state and has none in it."
                    : "that movie says it starts at power on and has a save state in it.");
        }

        if (anchor != null) {
            // Checked here rather than left to SaveState.read, so that a movie whose anchor came
            // from somewhere else is refused before any machine is touched -- and refused as a
            // damaged movie, which is what it is, rather than as an unloadable state.
            var inside = SaveState.headerOf(anchor);

            if (!inside.romSHA256().equals(header.romSHA256())) {
                throw new MovieException(
                        "that movie is damaged: the save state it starts from was taken from"
                                + " another cartridge.");
            }

            if (inside.region() != header.region()) {
                throw new MovieException(
                        "that movie is damaged: the save state it starts from was taken from a "
                                + inside.region().label() + " machine and the movie says "
                                + header.region().label() + ".");
            }
        }

        return new Movie(
                header,
                anchor,
                player1,
                header.ports() >= 2 ? chunks.get(TAG_CONTROLLER2) : null,
                resets(chunks.get(TAG_RESETS), header.frameCount()),
                codes(chunks.get(TAG_GENIE)));
    }

    /**
     * Reads a movie from a file.
     */
    public static Movie read(final Path path) throws IOException {
        try (var in = Files.newInputStream(path)) {
            return read(in);
        }
    }

    /**
     * What the file says about itself. Reads the header alone, which is why it is not compressed --
     * so a chooser can say "1,203 frames, from a state at frame 4,201" without inflating anything,
     * and a movie from the wrong cartridge can be refused before it is opened properly.
     */
    public static Header header(final Path path) throws IOException {
        var header = new byte[HEADER_BYTES];

        try (var in = Files.newInputStream(path)) {
            if (in.readNBytes(header, 0, HEADER_BYTES) < HEADER_BYTES) {
                throw new MovieException("that file is too short to be a movie.");
            }
        }

        if (!Arrays.equals(header, 0, MAGIC.length, MAGIC, 0, MAGIC.length)) {
            throw new MovieException("that is not a movie.");
        }

        return header(header);
    }

    private static Header header(final byte[] file) {
        var sha256 = new byte[SHA256_BYTES];
        System.arraycopy(file, OFFSET_SHA256, sha256, 0, SHA256_BYTES);

        var frameCount = SaveState.readLong(file, OFFSET_FRAME_COUNT);

        if (frameCount < 0 || frameCount > Integer.MAX_VALUE) {
            throw new MovieException(
                    "that movie says it holds " + frameCount + " frames, which is not a number of"
                            + " frames anybody played.");
        }

        return new Header(
                SaveState.readShort(file, OFFSET_VERSION),
                SaveState.bytesToHex(sha256),
                Byte.toUnsignedInt(file[OFFSET_MAPPER]),
                (file[OFFSET_FLAGS] & FLAG_PAL) != 0 ? Region.PAL : Region.NTSC,
                (file[OFFSET_FLAGS] & FLAG_ANCHORED) != 0,
                SaveState.readLong(file, OFFSET_ANCHOR_FRAME),
                frameCount,
                Byte.toUnsignedInt(file[OFFSET_PORTS]));
    }

    private static byte[] inflate(final byte[] file, final boolean gzipped) throws IOException {
        var body = Arrays.copyOfRange(file, HEADER_BYTES, file.length);

        if (!gzipped) {
            return body;
        }

        // readAllBytes checks the trailing CRC32, so a damaged file is caught here rather than
        // showing up later as a replay that mysteriously diverges.
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(body))) {
            return gzip.readAllBytes();
        } catch (EOFException | ZipException e) {
            throw new MovieException("that movie is damaged.", e);
        }
    }

    private static Map<String, byte[]> chunks(final byte[] body) {
        var chunks = new LinkedHashMap<String, byte[]>();
        var position = 0;

        while (position < body.length) {
            if (body.length - position < TAG_BYTES + 4) {
                throw new MovieException("that movie is damaged: it stops mid-chunk.");
            }

            var tag = new String(body, position, TAG_BYTES, StandardCharsets.US_ASCII);
            var length = SaveState.readInt(body, position + TAG_BYTES);
            var payload = position + TAG_BYTES + 4;

            if (length < 0 || length > MAX_CHUNK_BYTES || length > body.length - payload) {
                throw new MovieException(
                        "that movie is damaged: \"" + tag.trim() + "\" claims " + length
                                + " bytes and there are only " + (body.length - payload) + " left.");
            }

            chunks.put(tag, Arrays.copyOfRange(body, payload, payload + length));

            // By the declared length rather than by what anything read, which is what lets a chunk
            // that has grown since this build was written be stepped over cleanly.
            position = payload + length;
        }

        return chunks;
    }

    /**
     * The reset list, checked to be exactly what a replay may binary-search: sorted, unique, and
     * pointing at frames the movie actually holds.
     */
    private static long[] resets(final byte[] payload, final long frameCount) {
        if (payload == null || payload.length == 0) {
            return new long[0];
        }

        if (payload.length % 8 != 0) {
            throw new MovieException(
                    "that movie is damaged: its reset list is " + payload.length
                            + " bytes, which is not a whole number of frame numbers.");
        }

        var resets = new long[payload.length / 8];

        for (var i = 0; i < resets.length; i++) {
            resets[i] = SaveState.readLong(payload, i * 8);

            if (i > 0 && resets[i] <= resets[i - 1]) {
                throw new MovieException(
                        "that movie is damaged: its reset list is out of order at frame "
                                + resets[i] + ".");
            }

            if (resets[i] < 0 || resets[i] >= frameCount) {
                throw new MovieException(
                        "that movie is damaged: it says Reset was pressed at frame " + resets[i]
                                + ", and it only holds " + frameCount + " frames.");
            }
        }

        return resets;
    }

    /**
     * The pinned codes, decoded here rather than on the way into a machine -- so a movie carrying
     * something that is not a code is refused as a damaged file rather than as a run that quietly
     * played without the cheat it was recorded with.
     */
    private static List<GameGenieCode> codes(final byte[] payload) {
        if (payload == null || payload.length == 0) {
            return List.of();
        }

        var codes = new ArrayList<GameGenieCode>();
        var position = 0;

        while (position < payload.length) {
            var length = Byte.toUnsignedInt(payload[position++]);

            if (length == 0 || payload.length - position < length) {
                throw new MovieException(
                        "that movie is damaged: its Game Genie list stops mid-code.");
            }

            var text = new String(payload, position, length, StandardCharsets.US_ASCII);
            position += length;

            try {
                codes.add(GameGenieCode.decode(text));
            } catch (InvalidGameGenieCodeException e) {
                throw new MovieException(
                        "that movie is damaged: " + e.getMessage(), e);
            }
        }

        return List.copyOf(codes);
    }
}
