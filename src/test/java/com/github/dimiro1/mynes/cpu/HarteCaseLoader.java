package com.github.dimiro1.mynes.cpu;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

/**
 * Locates and streams the Tom Harte SingleStepTests fixtures.
 * <p>
 * Two sources are supported, and the full set wins when it is present:
 * <ul>
 *   <li><b>full set</b> -- {@code testdata/nes6502/v1/xx.json}, 10,000 cases per opcode,
 *       fetched by {@code scripts/download-6502-tests.sh} and gitignored;</li>
 *   <li><b>subset</b> -- {@code /harte/nes6502/xx.json.gz} on the classpath, the first 500
 *       cases per opcode, committed so that a plain {@code mvn test} still covers every
 *       opcode.</li>
 * </ul>
 * <p>
 * A file holds one JSON array of ~5MB (~110MB uncompressed for the whole set), so cases are
 * parsed one at a time rather than materialised into a list.
 */
final class HarteCaseLoader {
    private static final Path FULL_SET_DIR = Path.of("testdata", "nes6502", "v1");
    private static final String SUBSET_RESOURCE_FORMAT = "/harte/nes6502/%02x.json.gz";

    private static final JsonFactory FACTORY = new JsonFactory();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private HarteCaseLoader() {
    }

    /**
     * True when the downloaded full set is available.
     */
    static boolean hasFullSet() {
        return Files.isDirectory(FULL_SET_DIR) && Files.isReadable(fullSetPath(0x00));
    }

    /**
     * A human readable description of the source in use, for test reporting.
     */
    static String sourceDescription() {
        return hasFullSet()
                ? "full set (" + FULL_SET_DIR + ", 10000 cases/opcode)"
                : "committed subset (classpath /harte/nes6502, 500 cases/opcode)";
    }

    /**
     * Opens the fixture file for an opcode. The caller owns the returned stream.
     *
     * @param opcode the opcode, 0x00..0xFF.
     * @throws IOException if neither source provides the file.
     */
    static InputStream open(final int opcode) throws IOException {
        if (hasFullSet()) {
            return new BufferedInputStream(Files.newInputStream(fullSetPath(opcode)));
        }

        var resource = String.format(SUBSET_RESOURCE_FORMAT, opcode);
        var stream = HarteCaseLoader.class.getResourceAsStream(resource);

        if (stream == null) {
            throw new IOException(
                    "No test data for opcode " + String.format("$%02X", opcode) + ": " + resource
                            + " is not on the classpath and " + FULL_SET_DIR
                            + " has not been populated (run scripts/download-6502-tests.sh)"
            );
        }

        return new GZIPInputStream(stream);
    }

    /**
     * Parses the cases of one fixture file, handing each to {@code consumer} in turn.
     * <p>
     * Only one case is held in memory at a time.
     */
    static void stream(final InputStream input, final Consumer<HarteCase> consumer) throws IOException {
        try (var parser = FACTORY.createParser(input)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new IOException("Expected the fixture file to be a JSON array");
            }

            while (parser.nextToken() == JsonToken.START_OBJECT) {
                consumer.accept(MAPPER.readValue(parser, HarteCase.class));
            }
        }
    }

    private static Path fullSetPath(final int opcode) {
        return FULL_SET_DIR.resolve(String.format("%02x.json", opcode));
    }
}
