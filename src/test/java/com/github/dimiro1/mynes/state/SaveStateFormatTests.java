package com.github.dimiro1.mynes.state;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The container, as opposed to what is in it.
 * <p>
 * Two things are being pinned down. One is that the tolerance is real: a file from a build that had
 * more fields, or fewer, or a chunk this one has never heard of, still loads. The other is that
 * everything the file can be wrong about is found out <em>before</em> the machine is touched -- the
 * chunks are applied in place, so a refusal discovered halfway through would leave a console that is
 * half one game and half another, which is worse than not loading at all.
 */
class SaveStateFormatTests {
    private static final String ROM = "src/test/resources/nestest/nestest.nes";
    private static final String OTHER_ROM = "src/test/resources/mmc3-test-2/1-clocking.nes";

    /**
     * The header is fixed at this, and outside the compression, so a slot menu can label nine files
     * without inflating any of them.
     */
    private static final int HEADER_BYTES = 56;

    @TempDir
    private Path directory;

    @Test
    void aStateWrittenAndReadPutsTheMachineBackWhereItWas() throws IOException {
        var original = load(ROM);
        run(original, 30);

        var state = save(original);
        var other = load(ROM);
        run(other, 90);

        assertNotEquals(30, other.getPPU().getFrame());

        SaveState.read(other, new ByteArrayInputStream(state));

        assertEquals(30, other.getPPU().getFrame());
    }

    @Test
    void theHeaderCanBeReadWithoutInflatingTheRest() throws IOException {
        var nes = load(ROM);
        run(nes, 42);

        var path = directory.resolve("slot.mn");
        SaveState.write(nes, path);

        var header = SaveState.header(path);

        assertEquals(SaveState.VERSION, header.formatVersion());
        assertEquals(42, header.frame(), "which is what a slot menu labels a file with");
        assertEquals(0, header.mapperNumber());
        assertEquals(nes.getCart().sha256(), header.romSHA256());
    }

    @Test
    void aChunkThisVersionHasNeverHeardOfIsSteppedOver() throws IOException {
        var nes = load(ROM);
        run(nes, 20);

        var state = withExtraChunk(save(nes), "ZZZZ", new byte[]{9, 9, 9, 9, 9});

        var other = load(ROM);
        run(other, 60);

        SaveState.read(other, new ByteArrayInputStream(state));

        assertEquals(20, other.getPPU().getFrame(), "the chunks it does know still landed");
    }

    /**
     * The forward half of the tolerance: a later build appends a field to a chunk, and this one reads
     * as far as it understands and skips the rest by the declared length.
     */
    @Test
    void aChunkWithFieldsAddedSinceIsReadAsFarAsItGoes() throws IOException {
        var nes = load(ROM);
        run(nes, 25);

        var state = withLongerChunk(save(nes), "CPU ", 16);

        var other = load(ROM);
        run(other, 70);

        SaveState.read(other, new ByteArrayInputStream(state));

        assertEquals(25, other.getPPU().getFrame());
        assertEquals(
                nes.getCPU().getState().pc(),
                other.getCPU().getState().pc(),
                "the fields it did understand arrived intact");
    }

    /**
     * The backward half: an earlier build wrote a chunk without a field this one has. Truncating a
     * chunk is exactly what that looks like from here, and the missing fields have to keep what the
     * machine already held rather than becoming zero.
     */
    @Test
    void aChunkFromBeforeAFieldExistedLoadsWhatItHas() throws IOException {
        var nes = load(ROM);
        run(nes, 25);

        var state = withTruncatedChunk(save(nes), "APU ", 8);

        var other = load(ROM);
        run(other, 70);

        SaveState.read(other, new ByteArrayInputStream(state));

        assertEquals(25, other.getPPU().getFrame(), "everything else still loaded");
    }

    @Test
    void aStateWithARequiredChunkMissingIsRefusedByName() throws IOException {
        var nes = load(ROM);
        run(nes, 10);

        var state = withoutChunk(save(nes), "PPU ");
        var other = load(ROM);

        var refused = assertThrows(
                SaveStateException.class,
                () -> SaveState.read(other, new ByteArrayInputStream(state)));

        assertTrue(refused.getMessage().contains("PPU"), refused.getMessage());
    }

    /**
     * And the machine has to be exactly as it was, which is the property that makes every other
     * refusal in this file safe: the chunks are applied in place, so anything detectable has to be
     * detected before the first field is assigned.
     */
    @Test
    void aStateFromAnotherCartridgeIsRefusedAndChangesNothing() throws IOException {
        var nes = load(ROM);
        run(nes, 10);

        var state = save(nes);

        var other = load(OTHER_ROM);
        run(other, 40);

        var frame = other.getPPU().getFrame();
        var registers = other.getCPU().getState();

        var refused = assertThrows(
                SaveStateException.class,
                () -> SaveState.read(other, new ByteArrayInputStream(state)));

        assertTrue(refused.getMessage().contains("another cartridge"), refused.getMessage());
        assertEquals(frame, other.getPPU().getFrame(), "the machine kept running its own game");
        assertEquals(registers, other.getCPU().getState());
    }

    @Test
    void aFileThatIsNotAStateIsRefused() throws IOException {
        var other = load(ROM);
        var rubbish = "this is not a save state, it is a shopping list".getBytes(
                StandardCharsets.UTF_8);

        assertThrows(
                SaveStateException.class,
                () -> SaveState.read(other, new ByteArrayInputStream(rubbish)));
    }

    @Test
    void aFileTooShortToHoldAHeaderIsRefused() throws IOException {
        var other = load(ROM);

        assertThrows(
                SaveStateException.class,
                () -> SaveState.read(other, new ByteArrayInputStream(new byte[10])));
    }

    @Test
    void aTruncatedStateIsRefusedRatherThanHalfApplied() throws IOException {
        var nes = load(ROM);
        run(nes, 10);

        var state = save(nes);
        var cut = Arrays.copyOf(state, state.length - 40);

        var other = load(ROM);
        var frame = other.getPPU().getFrame();

        assertThrows(
                SaveStateException.class,
                () -> SaveState.read(other, new ByteArrayInputStream(cut)));
        assertEquals(frame, other.getPPU().getFrame());
    }

    @Test
    void aStateFromALaterFormatVersionIsRefused() throws IOException {
        var nes = load(ROM);
        run(nes, 10);

        var state = save(nes);

        // The version sits at offset 8, big-endian, outside the compression.
        state[8] = 0;
        state[9] = (byte) (SaveState.VERSION + 1);

        var other = load(ROM);

        var refused = assertThrows(
                SaveStateException.class,
                () -> SaveState.read(other, new ByteArrayInputStream(state)));

        assertTrue(refused.getMessage().contains("version"), refused.getMessage());
    }

    @Test
    void aStateWithADamagedBodyIsRefusedRatherThanLoadedWrong() throws IOException {
        var nes = load(ROM);
        run(nes, 10);

        var state = save(nes);

        // Well inside the gzip stream, so the CRC32 at the end of it stops disagreeing quietly.
        state[HEADER_BYTES + 30] ^= (byte) 0xFF;

        var other = load(ROM);

        assertThrows(
                SaveStateException.class,
                () -> SaveState.read(other, new ByteArrayInputStream(state)));
    }

    @Test
    void writingToAFileDoesNotLeaveATemporaryBehind() throws IOException {
        var nes = load(ROM);
        run(nes, 5);

        SaveState.write(nes, directory.resolve("slot.mn"));

        try (var files = Files.list(directory)) {
            assertEquals(1, files.count(), "written through a temporary and moved into place");
        }
    }

    @Test
    void aStateIsSmallerThanTheMachineItDescribes() throws IOException {
        var nes = load(ROM);
        run(nes, 30);

        var state = save(nes);

        assertTrue(state.length > HEADER_BYTES, "there is a body");
        assertTrue(
                state.length < 200_000,
                "the body is compressed: " + state.length + " bytes for a console with 150KB in it");
    }

    // ================================================================================== internals

    /**
     * Takes a state apart, gives the body to {@code change}, and puts it back together with the
     * length in the header corrected. Which is what a different build of this emulator would have
     * produced, and the only honest way to test the tolerance.
     */
    private static byte[] rebuild(final byte[] state, final BodyChange change) throws IOException {
        var header = Arrays.copyOf(state, HEADER_BYTES);
        final byte[] body;

        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(
                Arrays.copyOfRange(state, HEADER_BYTES, state.length)))) {
            body = gzip.readAllBytes();
        }

        var changed = change.apply(body);

        for (var i = 0; i < 4; i++) {
            header[52 + i] = (byte) (changed.length >> (24 - i * 8));
        }

        var out = new ByteArrayOutputStream();
        out.write(header);

        try (var gzip = new GZIPOutputStream(out)) {
            gzip.write(changed);
        }

        return out.toByteArray();
    }

    private interface BodyChange {
        byte[] apply(byte[] body) throws IOException;
    }

    private static byte[] withExtraChunk(
            final byte[] state, final String tag, final byte[] payload) throws IOException {
        return rebuild(state, body -> {
            var out = new ByteArrayOutputStream();

            out.write(body);
            out.write(tag.getBytes(StandardCharsets.US_ASCII));
            out.write(new byte[]{0, 0, 0, (byte) payload.length});
            out.write(payload);

            return out.toByteArray();
        });
    }

    private static byte[] withoutChunk(final byte[] state, final String tag) throws IOException {
        return rebuild(state, body -> {
            var out = new ByteArrayOutputStream();

            walk(body, (name, payload) -> {
                if (!name.equals(tag)) {
                    writeChunk(out, name, payload);
                }
            });

            return out.toByteArray();
        });
    }

    private static byte[] withLongerChunk(
            final byte[] state, final String tag, final int extra) throws IOException {
        return rebuild(state, body -> {
            var out = new ByteArrayOutputStream();

            walk(body, (name, payload) -> writeChunk(
                    out, name, name.equals(tag) ? grown(payload, extra) : payload));

            return out.toByteArray();
        });
    }

    private static byte[] withTruncatedChunk(
            final byte[] state, final String tag, final int keep) throws IOException {
        return rebuild(state, body -> {
            var out = new ByteArrayOutputStream();

            walk(body, (name, payload) -> writeChunk(
                    out, name, name.equals(tag) ? Arrays.copyOf(payload, keep) : payload));

            return out.toByteArray();
        });
    }

    private static byte[] grown(final byte[] payload, final int extra) {
        var grown = Arrays.copyOf(payload, payload.length + extra);

        Arrays.fill(grown, payload.length, grown.length, (byte) 0x5A);

        return grown;
    }

    private interface Chunk {
        void accept(String tag, byte[] payload) throws IOException;
    }

    private static void walk(final byte[] body, final Chunk each) throws IOException {
        var position = 0;

        while (position < body.length) {
            var tag = new String(body, position, 4, StandardCharsets.US_ASCII);
            var length = 0;

            for (var i = 0; i < 4; i++) {
                length = length << 8 | Byte.toUnsignedInt(body[position + 4 + i]);
            }

            each.accept(tag, Arrays.copyOfRange(body, position + 8, position + 8 + length));
            position += 8 + length;
        }
    }

    private static void writeChunk(
            final ByteArrayOutputStream out, final String tag, final byte[] payload)
            throws IOException {
        out.write(tag.getBytes(StandardCharsets.US_ASCII));

        for (var i = 0; i < 4; i++) {
            out.write((payload.length >> (24 - i * 8)) & 0xFF);
        }

        out.write(payload);
    }

    private static NES load(final String rom) throws IOException {
        return new NES(Cart.load(Files.readAllBytes(Path.of(rom)), rom));
    }

    private static byte[] save(final NES nes) throws IOException {
        var out = new ByteArrayOutputStream();

        SaveState.write(nes, out);

        return out.toByteArray();
    }

    private static void run(final NES nes, final int frames) {
        var ppu = nes.getPPU();

        for (var i = 0; i < frames; i++) {
            var frame = ppu.getFrame();

            do {
                nes.tick();
            } while (ppu.getFrame() == frame);
        }
    }
}
