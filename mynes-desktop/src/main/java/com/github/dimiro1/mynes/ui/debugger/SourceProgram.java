package com.github.dimiro1.mynes.ui.debugger;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Source, symbols and their places in one particular program ROM.
 * <p>
 * Nothing here is ca65-shaped. The parser turns its files, lines, spans and segments into the two
 * questions the window asks: which source line owns this byte of PRG ROM, and which bytes did this
 * source line emit. Keeping that seam clean is what leaves room for another assembler later.
 */
final class SourceProgram {
    record SourceFile(
            int id,
            String recordedPath,
            @Nullable Path path,
            List<String> text,
            boolean stale) {

        String displayName() {
            if (path != null && path.getFileName() != null) {
                return path.getFileName().toString();
            }

            var recorded = Path.of(recordedPath);

            return recorded.getFileName() == null ? recordedPath : recorded.getFileName().toString();
        }

        @Override
        public String toString() {
            return displayName() + (path == null ? " (missing)" : stale ? " (changed)" : "");
        }
    }

    /**
     * One contiguous range a source line emitted.
     *
     * @param prgOffset its stable place in the cartridge's program ROM.
     * @param cpuAddress where the linker intended it to run; a banked mapper may show it elsewhere.
     * @param size how many bytes came from the line.
     */
    record Range(int prgOffset, int cpuAddress, int size) {
        boolean contains(final int offset) {
            return offset >= prgOffset && offset < prgOffset + size;
        }
    }

    /** The physical bytes emitted by an ld65 procedure scope. */
    record FunctionRange(String name, int prgOffset, int size, @Nullable Location definition) {
        FunctionRange(final String name, final int prgOffset, final int size) {
            this(name, prgOffset, size, null);
        }
    }

    record SourceLine(SourceFile file, int number, String text, List<Range> ranges) {
        /** Every place execution may enter for this line, in PRG order and only once. */
        Set<Integer> breakpointOffsets() {
            var offsets = new LinkedHashSet<Integer>();

            ranges.forEach(range -> offsets.add(range.prgOffset()));

            return Collections.unmodifiableSet(offsets);
        }

        boolean hasCode() {
            return !ranges.isEmpty();
        }

        String location() {
            return file.displayName() + ":" + number;
        }
    }

    record Location(SourceFile file, int line) {
        String displayName() {
            return file.displayName() + ":" + line;
        }
    }

    record Symbol(
            String name,
            String kind,
            int value,
            @Nullable Integer prgOffset,
            @Nullable Location definition,
            List<Location> references) {
    }

    private record FileLine(int file, int line) {
    }

    private final Path debugFile;
    private final List<SourceFile> files;
    private final Map<FileLine, SourceLine> lines;
    private final SourceLine[] lineAtPRG;
    private final String[] functionAtPRG;
    private final SourceLine[] functionDefinitionAtPRG;
    private final Map<Integer, List<Symbol>> symbolsAtPRG;
    private final Map<Integer, List<Symbol>> symbolsAtAddress;
    private final Map<String, List<Symbol>> symbolsByName;
    private final Map<FileLine, Map<String, List<Symbol>>> symbolsBySourceLine;
    private final List<Symbol> ramSymbols;
    private final List<String> warnings;

    SourceProgram(
            final Path debugFile,
            final List<SourceFile> files,
            final Map<Integer, Map<Integer, List<Range>>> ranges,
            final List<SourceLine> preferredLines,
            final List<Symbol> symbols,
            final int prgBytes,
            final List<String> warnings) {
        this(debugFile, files, ranges, preferredLines, symbols, prgBytes, warnings, List.of());
    }

    SourceProgram(
            final Path debugFile,
            final List<SourceFile> files,
            final Map<Integer, Map<Integer, List<Range>>> ranges,
            final List<SourceLine> preferredLines,
            final List<Symbol> symbols,
            final int prgBytes,
            final List<String> warnings,
            final List<FunctionRange> functions) {

        this.debugFile = debugFile;
        this.files = List.copyOf(files);
        this.warnings = List.copyOf(warnings);
        this.lines = new LinkedHashMap<>();
        this.lineAtPRG = new SourceLine[prgBytes];
        this.functionAtPRG = new String[prgBytes];
        this.functionDefinitionAtPRG = new SourceLine[prgBytes];
        this.symbolsAtPRG = new LinkedHashMap<>();
        this.symbolsAtAddress = new LinkedHashMap<>();
        this.symbolsByName = new LinkedHashMap<>();
        this.symbolsBySourceLine = new LinkedHashMap<>();
        this.ramSymbols = symbols.stream()
                .filter(symbol -> symbol.kind().equals("lab") && symbol.prgOffset() == null)
                .filter(symbol -> symbol.value() >= 0 && (symbol.value() < 0x2000
                        || symbol.value() >= 0x6000 && symbol.value() < 0x8000))
                .sorted(Comparator.comparing(Symbol::name).thenComparingInt(Symbol::value))
                .toList();

        for (var file : files) {
            var byLine = ranges.getOrDefault(file.id(), Map.of());
            var count = Math.max(
                    file.text().size(),
                    byLine.keySet().stream().mapToInt(Integer::intValue).max().orElse(0));

            for (var number = 1; number <= count; number++) {
                var text = number <= file.text().size() ? file.text().get(number - 1) : "";
                var sourceLine = new SourceLine(
                        file,
                        number,
                        text,
                        List.copyOf(byLine.getOrDefault(number, List.of())));

                lines.put(new FileLine(file.id(), number), sourceLine);
            }
        }

        // Direct assembler/C locations precede macro-definition locations in this list. First wins
        // where both own the same generated bytes, so stopping on a macro expansion lands on the
        // call somebody wrote rather than on the macro body shared by every call.
        for (var preferred : preferredLines) {
            var sourceLine = line(preferred.file(), preferred.number());

            if (sourceLine == null) {
                continue;
            }

            for (var range : preferred.ranges()) {
                var until = Math.min(lineAtPRG.length, range.prgOffset() + range.size());

                for (var offset = Math.max(0, range.prgOffset()); offset < until; offset++) {
                    if (lineAtPRG[offset] == null) {
                        lineAtPRG[offset] = sourceLine;
                    }
                }
            }
        }

        // The narrowest nested procedure owns a byte when scope spans overlap.
        functions.stream().sorted(Comparator.comparingInt(FunctionRange::size).reversed())
                .forEach(function -> {
                    var until = Math.min(functionAtPRG.length,
                            function.prgOffset() + function.size());
                    var definition = function.definition() == null ? null
                            : line(function.definition().file(), function.definition().line());
                    for (var offset = Math.max(0, function.prgOffset()); offset < until; offset++) {
                        functionAtPRG[offset] = function.name();
                        functionDefinitionAtPRG[offset] = definition;
                    }
                });

        for (var symbol : symbols) {
            symbolsAtAddress.computeIfAbsent(symbol.value() & 0xFFFF, ignored -> new ArrayList<>())
                    .add(symbol);
            symbolsByName.computeIfAbsent(symbol.name(), ignored -> new ArrayList<>()).add(symbol);

            if (symbol.prgOffset() != null) {
                symbolsAtPRG.computeIfAbsent(symbol.prgOffset(), ignored -> new ArrayList<>())
                        .add(symbol);
            }

            if (symbol.definition() != null) {
                addSourceSymbol(symbol.definition(), symbol);
            }

            symbol.references().forEach(reference -> addSourceSymbol(reference, symbol));
        }
    }

    Path debugFile() {
        return debugFile;
    }

    List<SourceFile> files() {
        return files;
    }

    List<String> warnings() {
        return warnings;
    }

    /** Addressed RAM labels. ld65 does not record their size, so a viewer can show one byte only. */
    List<Symbol> ramSymbols() {
        return ramSymbols;
    }

    int executableLineCount() {
        return (int) lines.values().stream().filter(SourceLine::hasCode).count();
    }

    boolean hasMissingFiles() {
        return files.stream().anyMatch(file -> file.path() == null);
    }

    @Nullable SourceLine line(final SourceFile file, final int number) {
        return lines.get(new FileLine(file.id(), number));
    }

    @Nullable SourceLine lineAt(final int prgOffset) {
        return prgOffset >= 0 && prgOffset < lineAtPRG.length ? lineAtPRG[prgOffset] : null;
    }

    @Nullable String functionAt(final int prgOffset) {
        if (prgOffset < 0 || prgOffset >= functionAtPRG.length) return null;
        if (functionAtPRG[prgOffset] != null) return functionAtPRG[prgOffset];
        var exact = symbolsAtPRG.getOrDefault(prgOffset, List.of());
        return exact.stream().filter(symbol -> symbol.kind().equals("lab"))
                .map(Symbol::name).findFirst().orElse(null);
    }

    /** The label that defines a called procedure, rather than its first generated instruction. */
    @Nullable SourceLine functionDefinitionAt(final int prgOffset, final int cpuAddress) {
        if (prgOffset >= 0 && prgOffset < functionDefinitionAtPRG.length
                && functionDefinitionAtPRG[prgOffset] != null) {
            return functionDefinitionAtPRG[prgOffset];
        }
        var name = functionAt(prgOffset);
        if (name == null) return null;
        var named = symbolsByName.getOrDefault(name, List.of());
        for (var symbol : named) {
            if (symbol.prgOffset() != null && symbol.prgOffset() == prgOffset
                    && symbol.definition() != null) {
                var definition = symbol.definition();
                return line(definition.file(), definition.line());
            }
        }
        for (var symbol : named) {
            if (symbol.value() == (cpuAddress & 0xFFFF) && symbol.definition() != null) {
                var definition = symbol.definition();
                return line(definition.file(), definition.line());
            }
        }
        return null;
    }

    List<Symbol> symbolsAt(final int prgOffset, final int cpuAddress) {
        var physical = symbolsAtPRG.get(prgOffset);

        return physical != null ? List.copyOf(physical)
                : List.copyOf(symbolsAtAddress.getOrDefault(cpuAddress & 0xFFFF, List.of()));
    }

    @Nullable String firstSymbolAt(final int prgOffset, final int cpuAddress) {
        var found = symbolsAt(prgOffset, cpuAddress);

        return found.isEmpty() ? null : found.getFirst().name();
    }

    /**
     * The symbol this spelling means on this source line. The .dbg reference records disambiguate
     * cheap local labels such as {@code @done}; a globally unique name is the useful fallback for
     * lines produced through macros that did not receive a direct reference record.
     */
    @Nullable Symbol symbolAt(final SourceLine line, final String name) {
        var onLine = symbolsBySourceLine
                .getOrDefault(new FileLine(line.file().id(), line.number()), Map.of())
                .getOrDefault(name, List.of());

        if (!onLine.isEmpty()) {
            return onLine.getFirst();
        }

        var named = symbolsByName.getOrDefault(name, List.of());

        return named.size() == 1 ? named.getFirst() : null;
    }

    @Nullable SourceLine lineForBreakpoint(final int prgOffset) {
        var exact = lineAt(prgOffset);

        if (exact != null && exact.breakpointOffsets().contains(prgOffset)) {
            return exact;
        }

        for (var sourceLine : lines.values()) {
            if (sourceLine.breakpointOffsets().contains(prgOffset)) {
                return sourceLine;
            }
        }

        return null;
    }

    @Nullable SourceLine correspondingLine(final SourceLine previous) {
        if (previous == null) return null;
        return files.stream()
                .filter(file -> file.recordedPath().equals(previous.file().recordedPath()))
                .findFirst()
                .map(file -> line(file, previous.number()))
                .orElse(null);
    }

    Set<Integer> relocateBreakpointsFrom(
            final SourceProgram previous, final Set<Integer> offsets) {
        var moved = new LinkedHashSet<Integer>();
        for (var offset : offsets) {
            var oldLine = previous.lineForBreakpoint(offset);
            if (oldLine == null) {
                if (offset >= 0 && offset < lineAtPRG.length) moved.add(offset);
                continue;
            }
            var newLine = correspondingLine(oldLine);
            if (newLine != null) moved.addAll(newLine.breakpointOffsets());
        }
        return Collections.unmodifiableSet(moved);
    }

    private void addSourceSymbol(final Location location, final Symbol symbol) {
        symbolsBySourceLine
                .computeIfAbsent(
                        new FileLine(location.file().id(), location.line()),
                        ignored -> new LinkedHashMap<>())
                .computeIfAbsent(symbol.name(), ignored -> new ArrayList<>())
                .add(symbol);
    }
}
