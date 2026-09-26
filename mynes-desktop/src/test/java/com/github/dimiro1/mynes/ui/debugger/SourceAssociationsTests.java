package com.github.dimiro1.mynes.ui.debugger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SourceAssociationsTests {
    @TempDir
    private Path directory;

    @Test
    void anAssociationRoundTripsByCartridgeDigest() throws Exception {
        var path = directory.resolve("debug-sources.properties");
        var debug = directory.resolve("build/game.dbg");
        var root = directory.resolve("checkout");
        var associations = SourceAssociations.load(path);

        associations.remember("abc123", debug, root);

        var loaded = SourceAssociations.load(path).get("abc123");

        assertEquals(debug.toAbsolutePath(), loaded.debugFile());
        assertEquals(root.toAbsolutePath(), loaded.sourceRoot());
    }

    @Test
    void forgettingRemovesOnlyThatCartridge() throws Exception {
        var path = directory.resolve("debug-sources.properties");
        var associations = SourceAssociations.load(path);

        associations.remember("one", directory.resolve("one.dbg"), null);
        associations.remember("two", directory.resolve("two.dbg"), null);
        associations.forget("one");

        var loaded = SourceAssociations.load(path);

        assertNull(loaded.get("one"));
        assertEquals(
                directory.resolve("two.dbg").toAbsolutePath(),
                loaded.get("two").debugFile());
    }

    @Test
    void aBrokenFileBehavesLikeAnEmptyOne() throws Exception {
        var path = directory.resolve("debug-sources.properties");

        Files.writeString(path, "\\uNOTHEX=broken");

        assertNull(SourceAssociations.load(path).get("anything"));
    }
}
