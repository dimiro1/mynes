package com.github.dimiro1.mynes.headless;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The two shapes a headless document comes in.
 * <p>
 * A report is {@linkplain #pretty indented}, because a person reads it about as often as
 * {@code jq} does. An interactive reply is {@linkplain #compact compact}, because there each
 * document is one line of a stream and the reader on the other end takes them a line at a time.
 */
final class Json {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    static ObjectNode object() {
        return MAPPER.createObjectNode();
    }

    static String pretty(final JsonNode node) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (JsonProcessingException e) {
            // A tree that was built here rather than parsed from anywhere, so this cannot happen
            // without the tree being wrong, which is a bug rather than a bad input.
            throw new IllegalStateException(e);
        }
    }

    static String compact(final JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
