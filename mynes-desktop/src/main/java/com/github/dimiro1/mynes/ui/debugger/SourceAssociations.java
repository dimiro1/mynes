package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.ui.Config;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Remembers which local debug build belongs to a cartridge digest. */
final class SourceAssociations {
    static final Path DEFAULT_PATH = Config.DEFAULT_PATH.resolveSibling("debug-sources.properties");

    record Entry(Path debugFile, @Nullable Path sourceRoot) {
    }

    private static final String DEBUG_SUFFIX = ".debug";
    private static final String ROOT_SUFFIX = ".root";

    private final Path path;
    private final Map<String, Entry> entries;

    private SourceAssociations(final Path path, final Map<String, Entry> entries) {
        this.path = path;
        this.entries = entries;
    }

    static SourceAssociations load(final Path path) {
        var properties = new Properties();

        if (Files.isRegularFile(path)) {
            try (var input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (IOException | IllegalArgumentException ignored) {
                // A local convenience file must never keep the emulator from opening. The selected
                // .dbg can simply be attached again, which will replace whatever was broken.
            }
        }

        var entries = new LinkedHashMap<String, Entry>();

        for (var key : properties.stringPropertyNames()) {
            if (!key.endsWith(DEBUG_SUFFIX)) {
                continue;
            }

            var digest = key.substring(0, key.length() - DEBUG_SUFFIX.length());
            var debug = path(properties.getProperty(key));
            var root = path(properties.getProperty(digest + ROOT_SUFFIX));

            if (debug != null) {
                entries.put(digest, new Entry(debug, root));
            }
        }

        return new SourceAssociations(path, entries);
    }

    @Nullable Entry get(final String digest) {
        return entries.get(digest);
    }

    void remember(final String digest, final Path debugFile, final @Nullable Path sourceRoot)
            throws IOException {
        entries.put(digest, new Entry(
                debugFile.toAbsolutePath().normalize(),
                sourceRoot == null ? null : sourceRoot.toAbsolutePath().normalize()));
        save();
    }

    void forget(final String digest) throws IOException {
        entries.remove(digest);
        save();
    }

    private void save() throws IOException {
        var parent = path.toAbsolutePath().getParent();

        if (parent != null) {
            Files.createDirectories(parent);
        }

        var properties = new Properties();

        entries.forEach((digest, entry) -> {
            properties.setProperty(digest + DEBUG_SUFFIX, entry.debugFile().toString());

            if (entry.sourceRoot() != null) {
                properties.setProperty(digest + ROOT_SUFFIX, entry.sourceRoot().toString());
            }
        });

        try (var output = Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            properties.store(output, "Source debug files, keyed by cartridge SHA-256");
        }
    }

    private static @Nullable Path path(final @Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }

        try {
            return Path.of(text).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
