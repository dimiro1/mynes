package com.github.dimiro1.mynes.ui.debugger;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads the text file written by {@code ld65 --dbgfile}. */
final class Cc65DebugInfo {
    private record FileRecord(int id, String name, long size, long modified) {
    }

    private record Segment(
            int id, String name, int start, int size, @Nullable String output, int outputOffset) {
    }

    private record Span(int id, int segment, int start, int size) {
    }

    private record LineRecord(int id, int file, int line, int type, List<Integer> spans) {
    }

    private record SymbolRecord(
            String name,
            String kind,
            int value,
            @Nullable Integer segment,
            @Nullable Integer definition,
            List<Integer> references) {
    }

    private Cc65DebugInfo() {
    }

    static SourceProgram read(
            final Path debugFile, final byte[] prgROM, final @Nullable Path sourceRoot)
            throws IOException {

        var records = Files.readAllLines(debugFile, StandardCharsets.UTF_8);
        var versionSeen = false;
        var files = new LinkedHashMap<Integer, FileRecord>();
        var segments = new LinkedHashMap<Integer, Segment>();
        var spans = new LinkedHashMap<Integer, Span>();
        var lines = new ArrayList<LineRecord>();
        var symbols = new ArrayList<SymbolRecord>();

        for (var number = 0; number < records.size(); number++) {
            var record = records.get(number);

            if (record.isBlank()) {
                continue;
            }

            var tab = record.indexOf('\t');

            if (tab < 0) {
                throw bad(debugFile, number, "record has no tab");
            }

            var kind = record.substring(0, tab);
            var values = attributes(record.substring(tab + 1), debugFile, number);

            try {
                switch (kind) {
                    case "version" -> {
                        var major = integer(required(values, "major"));

                        if (major != 2) {
                            throw bad(debugFile, number, "unsupported debug format version " + major);
                        }

                        versionSeen = true;
                    }
                    case "file" -> {
                        var file = new FileRecord(
                                integer(required(values, "id")),
                                required(values, "name"),
                                integer(required(values, "size")),
                                integer(values.getOrDefault("mtime", "0")));

                        files.put(file.id(), file);
                    }
                    case "seg" -> {
                        var segment = new Segment(
                                integer(required(values, "id")),
                                required(values, "name"),
                                integer(required(values, "start")),
                                integer(required(values, "size")),
                                values.get("oname"),
                                integer(values.getOrDefault("ooffs", "-1")));

                        segments.put(segment.id(), segment);
                    }
                    case "span" -> {
                        var span = new Span(
                                integer(required(values, "id")),
                                integer(required(values, "seg")),
                                integer(required(values, "start")),
                                integer(required(values, "size")));

                        spans.put(span.id(), span);
                    }
                    case "line" -> lines.add(new LineRecord(
                            integer(required(values, "id")),
                            integer(required(values, "file")),
                            integer(required(values, "line")),
                            integer(values.getOrDefault("type", "0")),
                            ids(values.get("span"))));
                    case "sym" -> symbols.add(new SymbolRecord(
                            required(values, "name"),
                            values.getOrDefault("type", "symbol"),
                            integer(required(values, "val")),
                            values.containsKey("seg") ? integer(values.get("seg")) : null,
                            values.containsKey("def") ? integer(values.get("def")) : null,
                            ids(values.get("ref"))));
                    default -> { /* The source view has no use for modules, scopes, types or csym. */ }
                }
            } catch (NumberFormatException e) {
                throw bad(debugFile, number, "invalid number", e);
            }
        }

        if (!versionSeen) {
            throw new IOException(debugFile + " is not an ld65 debug file (no version record)");
        }

        var warnings = new ArrayList<String>();
        var sourceFiles = new ArrayList<SourceProgram.SourceFile>();
        var resolvedById = new HashMap<Integer, SourceProgram.SourceFile>();
        var sourceFileIds = new LinkedHashSet<Integer>();

        lines.forEach(line -> sourceFileIds.add(line.file()));

        for (var file : files.values()) {
            // ld65 also records binary inputs to .incbin (for example CHR data). With no source
            // line pointing into them they are not source files and must not be decoded as text.
            if (!sourceFileIds.contains(file.id())) {
                continue;
            }

            var resolved = resolveSource(debugFile, sourceRoot, file.name());
            var text = resolved == null
                    ? List.<String>of()
                    : Files.readAllLines(resolved, StandardCharsets.UTF_8);
            var stale = resolved != null && stale(resolved, file);
            var sourceFile = new SourceProgram.SourceFile(
                    file.id(), file.name(), resolved, List.copyOf(text), stale);

            sourceFiles.add(sourceFile);
            resolvedById.put(file.id(), sourceFile);

            if (resolved == null) {
                warnings.add("Could not find " + file.name());
            } else if (stale) {
                warnings.add(sourceFile.displayName() + " has changed since the program was built");
            }
        }

        var outputBases = outputBases(debugFile, sourceRoot, segments.values(), prgROM, warnings);
        var ranges = new LinkedHashMap<Integer, Map<Integer, List<SourceProgram.Range>>>();
        var preferred = new ArrayList<SourceProgram.SourceLine>();

        // The ordinary source location before a macro body when both own one span. See SourceProgram.
        lines.sort(Comparator.comparingInt(line -> line.type() == 2 ? 1 : 0));

        for (var line : lines) {
            var file = resolvedById.get(line.file());

            if (file == null) {
                continue;
            }

            var lineRanges = new ArrayList<SourceProgram.Range>();

            for (var spanId : line.spans()) {
                var span = spans.get(spanId);
                var segment = span == null ? null : segments.get(span.segment());
                var range = rangeOf(segment, span, outputBases, prgROM.length);

                if (range != null) {
                    lineRanges.add(range);
                    ranges.computeIfAbsent(file.id(), ignored -> new LinkedHashMap<>())
                            .computeIfAbsent(line.line(), ignored -> new ArrayList<>())
                            .add(range);
                }
            }

            preferred.add(new SourceProgram.SourceLine(file, line.line(), "", lineRanges));
        }

        var sourceSymbols = new ArrayList<SourceProgram.Symbol>();
        var linesById = new HashMap<Integer, LineRecord>();

        lines.forEach(line -> linesById.put(line.id(), line));

        for (var symbol : symbols) {
            Integer prgOffset = null;

            if (symbol.segment() != null) {
                var segment = segments.get(symbol.segment());

                if (segment != null && segment.output() != null) {
                    var base = outputBases.get(segment.output());

                    if (base != null) {
                        var offset = segment.outputOffset() - base + symbol.value() - segment.start();

                        if (offset >= 0 && offset < prgROM.length) {
                            prgOffset = offset;
                        }
                    }
                }
            }

            var definition = locationOf(
                    symbol.definition() == null ? null : linesById.get(symbol.definition()),
                    resolvedById);
            var references = symbol.references().stream()
                    .map(linesById::get)
                    .map(line -> locationOf(line, resolvedById))
                    .filter(java.util.Objects::nonNull)
                    .toList();

            sourceSymbols.add(new SourceProgram.Symbol(
                    symbol.name(),
                    symbol.kind(),
                    symbol.value(),
                    prgOffset,
                    definition,
                    references));
        }

        return new SourceProgram(
                debugFile.toAbsolutePath().normalize(),
                sourceFiles,
                ranges,
                preferred,
                sourceSymbols,
                prgROM.length,
                warnings);
    }

    private static @Nullable SourceProgram.Location locationOf(
            final @Nullable LineRecord line,
            final Map<Integer, SourceProgram.SourceFile> files) {
        if (line == null) {
            return null;
        }

        var file = files.get(line.file());

        return file == null ? null : new SourceProgram.Location(file, line.line());
    }

    private static @Nullable SourceProgram.Range rangeOf(
            final @Nullable Segment segment,
            final Span span,
            final Map<String, Integer> outputBases,
            final int prgBytes) {

        if (segment == null || segment.output() == null || segment.outputOffset() < 0
                || segment.start() < 0x8000 || segment.start() > 0xFFFF) {
            return null;
        }

        var outputBase = outputBases.get(segment.output());

        if (outputBase == null) {
            return null;
        }

        var offset = segment.outputOffset() - outputBase + span.start();

        if (offset < 0 || offset >= prgBytes || span.size() <= 0) {
            return null;
        }

        var size = Math.min(span.size(), prgBytes - offset);

        return new SourceProgram.Range(offset, (segment.start() + span.start()) & 0xFFFF, size);
    }

    /** The byte in each linker output file at which the cartridge's PRG ROM begins. */
    private static Map<String, Integer> outputBases(
            final Path debugFile,
            final @Nullable Path sourceRoot,
            final Iterable<Segment> segments,
            final byte[] prgROM,
            final List<String> warnings) throws IOException {

        var byOutput = new LinkedHashMap<String, List<Segment>>();

        for (var segment : segments) {
            if (segment.output() != null) {
                byOutput.computeIfAbsent(segment.output(), ignored -> new ArrayList<>()).add(segment);
            }
        }

        var bases = new HashMap<String, Integer>();

        for (var entry : byOutput.entrySet()) {
            var inferred = entry.getValue().stream()
                    .filter(segment -> segment.name().equalsIgnoreCase("HEADER"))
                    .mapToInt(segment -> segment.outputOffset() + segment.size())
                    .max()
                    .orElseGet(() -> entry.getKey().toLowerCase().endsWith(".nes") ? 16 : 0);
            var output = resolveOutput(debugFile, sourceRoot, entry.getKey());

            if (output != null) {
                var base = indexOf(Files.readAllBytes(output), prgROM, inferred);

                if (base >= 0) {
                    bases.put(entry.getKey(), base);
                    continue;
                }
            }

            // A normal .nes output starts with the 16-byte header. This inference is also available
            // when the old build directory no longer exists, which is precisely when source-root
            // remapping is useful. A separate PRG binary starts at zero.
            bases.put(entry.getKey(), inferred);

            if (entry.getValue().stream().anyMatch(segment -> segment.start() >= 0x8000)) {
                warnings.add("Could not verify " + entry.getKey()
                        + " against the loaded ROM; using its linked offsets");
            }
        }

        return bases;
    }

    private static int indexOf(final byte[] whole, final byte[] wanted, final int preferred) {
        if (wanted.length == 0 || whole.length < wanted.length) {
            return -1;
        }

        var found = -1;

        outer:
        for (var start = 0; start <= whole.length - wanted.length; start++) {
            if (whole[start] != wanted[0]
                    || whole[start + wanted.length - 1] != wanted[wanted.length - 1]) {
                continue;
            }

            for (var at = 1; at < wanted.length - 1; at++) {
                if (whole[start + at] != wanted[at]) {
                    continue outer;
                }
            }

            if (found < 0 || Math.abs(start - preferred) < Math.abs(found - preferred)) {
                found = start;
            }
        }

        return found;
    }

    private static boolean stale(final Path path, final FileRecord recorded) throws IOException {
        if (recorded.size() > 0 && Files.size(path) != recorded.size()) {
            return true;
        }

        if (recorded.modified() <= 0) {
            return false;
        }

        var actual = Files.getLastModifiedTime(path).toMillis() / 1_000;

        return actual > recorded.modified() + 2;
    }

    private static @Nullable Path resolveSource(
            final Path debugFile, final @Nullable Path root, final String recorded) throws IOException {

        var named = Path.of(recorded);
        var candidates = new LinkedHashSet<Path>();

        candidates.add(named);
        for (var base : searchRoots(debugFile, root)) {
            candidates.add(base.resolve(named).normalize());

            if (named.getFileName() != null) {
                candidates.add(base.resolve(named.getFileName()).normalize());
            }
        }

        for (var candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toAbsolutePath().normalize();
            }
        }

        if (named.getFileName() == null) {
            return null;
        }

        // A source folder is a useful anchor, not a fence: generated includes commonly sit in a
        // sibling build folder. Try the anchor and its nearby project roots, stopping at the first
        // root where the file name is unambiguous.
        for (var base : recursiveSearchRoots(root)) {
            if (!Files.isDirectory(base)) {
                continue;
            }

            try (var paths = Files.walk(base, 8)) {
                var matches = paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().equals(named.getFileName()))
                        .limit(2)
                        .toList();

                if (matches.size() == 1) {
                    return matches.getFirst().toAbsolutePath().normalize();
                }
            }
        }

        return null;
    }

    private static List<Path> recursiveSearchRoots(final @Nullable Path root) {
        if (root == null) {
            return List.of();
        }

        var roots = new LinkedHashSet<Path>();
        addWithParents(roots, root, 1);

        return List.copyOf(roots);
    }

    private static @Nullable Path resolveOutput(
            final Path debugFile, final @Nullable Path root, final String recorded) {

        var named = Path.of(recorded);
        var candidates = new LinkedHashSet<Path>();

        candidates.add(named);
        for (var base : searchRoots(debugFile, root)) {
            candidates.add(base.resolve(named).normalize());
            if (named.getFileName() != null) {
                candidates.add(base.resolve(named.getFileName()));
            }
        }

        return candidates.stream()
                .filter(Files::isRegularFile)
                .findFirst()
                .map(path -> path.toAbsolutePath().normalize())
                .orElse(null);
    }

    /**
     * Places a recorded build-relative path is likely to be relative to. Both a selected source
     * folder and the folder containing the debug file are anchors; their parents cover the common
     * {@code project/src} plus {@code project/build} layout without asking for multiple roots.
     */
    private static List<Path> searchRoots(final Path debugFile, final @Nullable Path root) {
        var roots = new LinkedHashSet<Path>();

        if (root != null) {
            addWithParents(roots, root, 2);
        }

        var debugParent = debugFile.toAbsolutePath().normalize().getParent();

        if (debugParent != null) {
            addWithParents(roots, debugParent, 2);
        }

        return List.copyOf(roots);
    }

    private static void addWithParents(
            final Set<Path> roots, final Path start, final int parents) {
        var at = start.toAbsolutePath().normalize();

        for (var level = 0; at != null && level <= parents; level++, at = at.getParent()) {
            roots.add(at);
        }
    }

    private static Map<String, String> attributes(
            final String text, final Path file, final int line) throws IOException {

        var out = new LinkedHashMap<String, String>();
        var at = 0;

        while (at < text.length()) {
            var equals = text.indexOf('=', at);

            if (equals < 0) {
                throw bad(file, line, "attribute has no '='");
            }

            var key = text.substring(at, equals).trim();
            at = equals + 1;

            String value;
            if (at < text.length() && text.charAt(at) == '"') {
                var quoted = new StringBuilder();
                var escaped = false;
                at++;

                while (at < text.length()) {
                    var c = text.charAt(at++);

                    if (escaped) {
                        quoted.append(c);
                        escaped = false;
                    } else if (c == '\\') {
                        escaped = true;
                    } else if (c == '"') {
                        break;
                    } else {
                        quoted.append(c);
                    }
                }

                value = quoted.toString();
            } else {
                var comma = text.indexOf(',', at);
                var end = comma < 0 ? text.length() : comma;

                value = text.substring(at, end).trim();
                at = end;
            }

            out.put(key, value);

            if (at < text.length() && text.charAt(at) == ',') {
                at++;
            }
        }

        return out;
    }

    private static List<Integer> ids(final @Nullable String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        var out = new ArrayList<Integer>();

        for (var id : text.split("\\+")) {
            out.add(integer(id));
        }

        return List.copyOf(out);
    }

    private static int integer(final String text) {
        var value = text.trim();

        if (value.startsWith("0x") || value.startsWith("0X")) {
            return (int) Long.parseLong(value.substring(2), 16);
        }

        return Integer.parseInt(value);
    }

    private static String required(final Map<String, String> values, final String key) {
        var value = values.get(key);

        if (value == null) {
            throw new IllegalArgumentException("missing " + key);
        }

        return value;
    }

    private static IOException bad(final Path file, final int zeroBasedLine, final String message) {
        return new IOException(file + ":" + (zeroBasedLine + 1) + ": " + message);
    }

    private static IOException bad(
            final Path file, final int zeroBasedLine, final String message, final Exception cause) {
        return new IOException(file + ":" + (zeroBasedLine + 1) + ": " + message, cause);
    }
}
