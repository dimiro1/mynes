package com.github.dimiro1.mynes.headless;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two shapes a headless document comes in, and just enough of a tree to build them.
 * <p>
 * A report is {@linkplain #pretty indented}, because a person reads it about as often as
 * {@code jq} does. An interactive reply is {@linkplain #compact compact}, because there each
 * document is one line of a stream and the reader on the other end takes them a line at a time.
 * <p>
 * Written out by hand rather than by Jackson, which used to do it. The emulator emits two document
 * shapes and never reads a document back, so the whole of what a JSON library was here for is the
 * two writers below -- against 2.3MB and three jars, which was sixty per cent of everything the
 * distributed jar contained. Jackson is still a test dependency, where it parses a gigabyte and a
 * half of Tom Harte fixtures and earns its place twice over.
 * <p>
 * The indented form is Jackson's {@code DefaultPrettyPrinter} to the byte, because a report written
 * before this change and one written after it get diffed against each other, and because the header
 * of every report in anybody's notes is that shape. Two spaces per level of object, a space either
 * side of the colon, {@code { }} and {@code [ ]} when empty -- and arrays held on one line, which is
 * what stops a 32 entry palette from becoming 32 lines of report.
 * <p>
 * Nothing here is a general JSON library and it should not grow into one. It writes; it does not
 * parse, and the tests that read a report back use Jackson to do it.
 */
final class Json {
    /**
     * How wide one level of indentation is, in spaces.
     */
    private static final int INDENT = 2;

    private Json() {
    }

    static Object object() {
        return new Object();
    }

    static String pretty(final Object root) {
        var out = new StringBuilder();
        root.write(out, 0, true);

        // No trailing newline: both callers end the document themselves, one with a line separator
        // and the other with println.
        return out.toString();
    }

    static String compact(final Object root) {
        var out = new StringBuilder();
        root.write(out, 0, false);

        return out.toString();
    }

    /**
     * The same tree, rendered for a person at a terminal rather than for a machine reading a
     * stream. Objects become aligned {@code key: value} lines, nested objects are indented under
     * their key, arrays stay inline, and strings print as themselves -- no surrounding quotes and
     * no {@code \n}/{@code \t} escapes, so a multi-line blob like {@code help} comes out as the
     * lines it is.
     */
    static String text(final Object root) {
        var out = new StringBuilder();
        root.writeText(out, 0);

        return out.toString();
    }

    /**
     * Anything that can sit in the tree.
     * <p>
     * Sealed over exactly three cases because that is all a document here is made of, and because it
     * lets each one write itself: there is no visitor, no {@code instanceof} and no way to add a
     * fourth kind of value without the compiler pointing at every writer that would have missed it.
     * <p>
     * {@code depth} is the nesting level of the container doing the writing, counted in objects
     * only. Arrays do not add a level, which is not an oversight -- it is the rule that keeps an
     * object inside an array indented as though the array were not there, the way Jackson does it.
     */
    private sealed interface Value permits Scalar, Object, Array {
        void write(StringBuilder out, int depth, boolean pretty);

        /**
         * The human-readable, possibly multi-line, block form. Only an object ever spans more than
         * the line it starts on; everything else defers to {@link #writeInline}.
         */
        void writeText(StringBuilder out, int depth);

        /**
         * The human-readable form on a single line, as used inside an array and to the right of a
         * {@code key:}.
         */
        void writeInline(StringBuilder out);
    }

    /**
     * A value already spelled as JSON, which is everything but the two containers.
     * <p>
     * Numbers and strings are converted where they are put rather than where they are written, so
     * the tree carries no types of its own and neither writer has anything left to decide.
     */
    private record Scalar(String json) implements Value {
        @Override
        public void write(final StringBuilder out, final int depth, final boolean pretty) {
            out.append(json);
        }

        @Override
        public void writeText(final StringBuilder out, final int depth) {
            writeInline(out);
        }

        @Override
        public void writeInline(final StringBuilder out) {
            out.append(unquote(json));
        }
    }

    /**
     * A JSON object. Named for what it is, at the cost of shadowing {@link java.lang.Object} inside
     * this file, which nothing in here needs.
     */
    static final class Object implements Value {
        /**
         * Insertion ordered, because the order of a report's keys is part of what is being compared
         * when two of them are read side by side.
         */
        private final Map<String, Value> entries = new LinkedHashMap<>();

        private Object() {
        }

        void put(final String name, final int value) {
            entries.put(name, new Scalar(Integer.toString(value)));
        }

        void put(final String name, final long value) {
            entries.put(name, new Scalar(Long.toString(value)));
        }

        void put(final String name, final double value) {
            entries.put(name, new Scalar(Double.toString(value)));
        }

        void put(final String name, final boolean value) {
            entries.put(name, new Scalar(Boolean.toString(value)));
        }

        /**
         * A string, or a JSON null where there is no string. Null rather than absent so that
         * {@code jq} over two reports compares the same set of keys either way, which is the same
         * reason {@link #putNull} exists at all.
         */
        void put(final String name, final String value) {
            entries.put(name, new Scalar(quote(value)));
        }

        void putNull(final String name) {
            entries.put(name, new Scalar("null"));
        }

        Object putObject(final String name) {
            var child = new Object();
            entries.put(name, child);

            return child;
        }

        Array putArray(final String name) {
            var child = new Array();
            entries.put(name, child);

            return child;
        }

        @Override
        public void write(final StringBuilder out, final int depth, final boolean pretty) {
            if (entries.isEmpty()) {
                out.append(pretty ? "{ }" : "{}");
                return;
            }

            out.append('{');

            var first = true;

            for (var entry : entries.entrySet()) {
                if (!first) {
                    out.append(',');
                }

                first = false;

                if (pretty) {
                    newline(out, depth + 1);
                }

                out.append(quote(entry.getKey())).append(pretty ? " : " : ":");
                entry.getValue().write(out, depth + 1, pretty);
            }

            if (pretty) {
                newline(out, depth);
            }

            out.append('}');
        }

        private boolean isEmpty() {
            return entries.isEmpty();
        }

        @Override
        public void writeText(final StringBuilder out, final int depth) {
            if (entries.isEmpty()) {
                out.append("{}");
                return;
            }

            // Widest key at this level, so the colons of the plain values line up. A key whose
            // value is a nested object gets no padding: its value is on the lines below, not to
            // the right of the colon.
            var width = 0;

            for (var entry : entries.entrySet()) {
                if (!(entry.getValue() instanceof Object child) || child.isEmpty()) {
                    width = Math.max(width, entry.getKey().length());
                }
            }

            var indent = " ".repeat(INDENT * depth);
            var first = true;

            for (var entry : entries.entrySet()) {
                if (!first) {
                    out.append('\n');
                }

                first = false;
                out.append(indent).append(entry.getKey());

                if (entry.getValue() instanceof Object child && !child.isEmpty()) {
                    out.append(':').append('\n');
                    child.writeText(out, depth + 1);
                } else {
                    out.append(" ".repeat(width - entry.getKey().length())).append(": ");
                    entry.getValue().writeInline(out);
                }
            }
        }

        @Override
        public void writeInline(final StringBuilder out) {
            out.append('{');

            var first = true;

            for (var entry : entries.entrySet()) {
                if (!first) {
                    out.append(", ");
                }

                first = false;
                out.append(entry.getKey()).append(": ");
                entry.getValue().writeInline(out);
            }

            out.append('}');
        }
    }

    /**
     * A JSON array, which is only ever written on one line. See {@link Value#write} for why it does
     * not deepen the indentation of what it holds.
     */
    static final class Array implements Value {
        private final List<Value> values = new ArrayList<>();

        private Array() {
        }

        void add(final int value) {
            values.add(new Scalar(Integer.toString(value)));
        }

        void add(final long value) {
            values.add(new Scalar(Long.toString(value)));
        }

        void add(final String value) {
            values.add(new Scalar(quote(value)));
        }

        Object addObject() {
            var child = new Object();
            values.add(child);

            return child;
        }

        @Override
        public void write(final StringBuilder out, final int depth, final boolean pretty) {
            if (values.isEmpty()) {
                out.append(pretty ? "[ ]" : "[]");
                return;
            }

            out.append(pretty ? "[ " : "[");

            for (var i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(pretty ? ", " : ",");
                }

                values.get(i).write(out, depth, pretty);
            }

            out.append(pretty ? " ]" : "]");
        }

        @Override
        public void writeText(final StringBuilder out, final int depth) {
            writeInline(out);
        }

        @Override
        public void writeInline(final StringBuilder out) {
            out.append('[');

            for (var i = 0; i < values.size(); i++) {
                if (i > 0) {
                    out.append(", ");
                }

                values.get(i).writeInline(out);
            }

            out.append(']');
        }
    }

    private static void newline(final StringBuilder out, final int depth) {
        out.append('\n').append(" ".repeat(INDENT * depth));
    }

    /**
     * A string as JSON spells it, or {@code null} for one that is not there.
     * <p>
     * Everything from a space upwards is left alone but for the two characters that would end the
     * string or start an escape, which is what keeps a cartridge called {@code Pokémon} spelled that
     * way in a report rather than as six characters of escape. Control characters cannot be left
     * alone, and a path is allowed to contain one.
     */
    private static String quote(final String value) {
        if (value == null) {
            return "null";
        }

        var out = new StringBuilder(value.length() + 2);
        out.append('"');

        for (var i = 0; i < value.length(); i++) {
            var c = value.charAt(i);

            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }

        return out.append('"').toString();
    }

    /**
     * Undoes {@link #quote} for the text renderer: a quoted string comes back as the characters it
     * stood for, escapes and all, and anything that is not a quoted string -- a number, a boolean,
     * {@code null} -- is already its own plain form and is handed back untouched.
     */
    private static String unquote(final String json) {
        if (json.length() < 2 || json.charAt(0) != '"') {
            return json;
        }

        var out = new StringBuilder(json.length());

        for (var i = 1; i < json.length() - 1; i++) {
            var c = json.charAt(i);

            if (c != '\\') {
                out.append(c);
                continue;
            }

            var next = json.charAt(++i);

            switch (next) {
                case '"' -> out.append('"');
                case '\\' -> out.append('\\');
                case '/' -> out.append('/');
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    out.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    i += 4;
                }
                default -> out.append(next);
            }
        }

        return out.toString();
    }
}
