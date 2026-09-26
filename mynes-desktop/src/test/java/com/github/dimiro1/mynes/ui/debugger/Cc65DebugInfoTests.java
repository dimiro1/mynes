package com.github.dimiro1.mynes.ui.debugger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Cc65DebugInfoTests {
    @TempDir
    private Path directory;

    @Test
    void linesAndSymbolsAreMappedThroughSpansAndOutputOffsets() throws Exception {
        var source = directory.resolve("game.s");
        var rom = directory.resolve("game.nes");
        var debug = directory.resolve("game.dbg");
        var sourceText = """
                reset:
                    sei
                loop:
                    inc counter
                    jmp loop
                """;
        var prg = new byte[0x4000];
        var image = new byte[16 + prg.length + 0x2000];

        prg[0] = 0x78;
        prg[1] = (byte) 0xE6;
        prg[2] = 0;
        prg[3] = 0x4C;
        System.arraycopy(prg, 0, image, 16, prg.length);

        Files.writeString(source, sourceText);
        Files.write(rom, image);
        Files.writeString(debug, debug(source, rom));

        var program = Cc65DebugInfo.read(debug, prg, null);

        assertEquals("game.s", program.files().getFirst().displayName());
        assertEquals(2, program.lineAt(0).number());
        assertEquals(4, program.lineAt(1).number());
        assertEquals(java.util.Set.of(1), program.lineAt(1).breakpointOffsets());
        assertEquals("reset", program.firstSymbolAt(0, 0xC000));
        assertEquals("loop", program.firstSymbolAt(1, 0xC001));
        var reset = program.symbolAt(program.lineAt(1), "reset");

        assertNotNull(reset);
        assertNotNull(reset.definition());
        assertEquals("game.s:2", reset.definition().displayName());
        assertTrue(program.warnings().isEmpty());
    }

    @Test
    void theMacroCallWinsOverTheMacroDefinitionForTheSameBytes() throws Exception {
        var source = directory.resolve("game.s");
        var rom = directory.resolve("game.nes");
        var debug = directory.resolve("game.dbg");
        var text = "macro body\ncall macro\n";
        var prg = new byte[0x4000];
        var image = new byte[16 + prg.length];

        Files.writeString(source, text);
        System.arraycopy(prg, 0, image, 16, prg.length);
        Files.write(rom, image);
        Files.writeString(debug, """
                version\tmajor=2,minor=0
                file\tid=0,name="%s",size=%d,mtime=0
                line\tid=0,file=0,line=1,type=2,count=1,span=0
                line\tid=1,file=0,line=2,span=0
                seg\tid=0,name="CODE",start=0xC000,size=1,addrsize=absolute,type=ro,oname="%s",ooffs=16
                span\tid=0,seg=0,start=0,size=1
                """.formatted(source, Files.size(source), rom));

        var program = Cc65DebugInfo.read(debug, prg, null);

        assertEquals(2, program.lineAt(0).number());
    }

    @Test
    void aMovedSourceCanBeFoundUnderTheRootTheUserChooses() throws Exception {
        var root = Files.createDirectories(directory.resolve("checkout/src"));
        var source = root.resolve("game.s");
        var debug = directory.resolve("game.dbg");
        var rom = directory.resolve("game.nes");
        var prg = new byte[0x4000];
        var image = new byte[16 + prg.length];

        Files.writeString(source, "sei\n");
        System.arraycopy(prg, 0, image, 16, prg.length);
        Files.write(rom, image);
        Files.writeString(debug, debugRecord("/old/computer/project/src/game.s", source, rom));

        var withoutRoot = Cc65DebugInfo.read(debug, prg, null);
        var withRoot = Cc65DebugInfo.read(debug, prg, directory.resolve("checkout"));

        assertTrue(withoutRoot.hasMissingFiles());
        assertFalse(withRoot.hasMissingFiles());
        assertEquals(source, withRoot.files().getFirst().path());
    }

    @Test
    void aSourceFolderAlsoFindsGeneratedIncludesInASiblingBuildFolder() throws Exception {
        var project = Files.createDirectories(directory.resolve("checkout"));
        var sources = Files.createDirectories(project.resolve("src"));
        var build = Files.createDirectories(project.resolve("build"));
        var source = sources.resolve("main.s");
        var generated = build.resolve("tiles.inc");
        var binary = build.resolve("game.chr");
        var rom = build.resolve("game.nes");
        var debug = build.resolve("game.dbg");
        var prg = new byte[0x4000];
        var image = new byte[16 + prg.length];

        Files.writeString(source, "sei\n");
        Files.writeString(generated, ".byte 0\n");
        Files.write(binary, new byte[]{0, (byte) 0xFF, 0});
        System.arraycopy(prg, 0, image, 16, prg.length);
        Files.write(rom, image);
        Files.writeString(debug, """
                version\tmajor=2,minor=0
                file\tid=0,name="src/main.s",size=%d,mtime=0
                file\tid=1,name="src/../build/tiles.inc",size=%d,mtime=0
                file\tid=2,name="src/../build/game.chr",size=%d,mtime=0
                line\tid=0,file=0,line=1,span=0
                line\tid=1,file=1,line=1
                seg\tid=0,name="CODE",start=0xC000,size=1,addrsize=absolute,type=ro,oname="build/game.nes",ooffs=16
                span\tid=0,seg=0,start=0,size=1
                """.formatted(Files.size(source), Files.size(generated), Files.size(binary)));

        var program = Cc65DebugInfo.read(debug, prg, sources);

        assertFalse(program.hasMissingFiles());
        assertEquals(2, program.files().size(), "binary linker inputs are not source files");
        assertEquals(source, program.files().get(0).path());
        assertEquals(generated, program.files().get(1).path());
    }

    @Test
    void changedSourceIsMarkedStale() throws Exception {
        var source = directory.resolve("game.s");
        var debug = directory.resolve("game.dbg");
        var rom = directory.resolve("game.nes");
        var prg = new byte[0x4000];
        var image = new byte[16 + prg.length];

        Files.writeString(source, "sei\n");
        System.arraycopy(prg, 0, image, 16, prg.length);
        Files.write(rom, image);
        Files.writeString(debug, debugRecord(source.toString(), source, rom));
        Files.writeString(source, "sei\nnop\n");
        Files.setLastModifiedTime(source, FileTime.fromMillis(System.currentTimeMillis() + 10_000));

        var program = Cc65DebugInfo.read(debug, prg, null);

        assertTrue(program.files().getFirst().stale());
        assertFalse(program.warnings().isEmpty());
    }

    @Test
    void anOffsetWithNoLineOrSymbolSaysNothing() throws Exception {
        var source = directory.resolve("game.s");
        var rom = directory.resolve("game.nes");
        var debug = directory.resolve("game.dbg");
        var prg = new byte[0x4000];
        var image = new byte[16 + prg.length];

        Files.writeString(source, "sei\n");
        System.arraycopy(prg, 0, image, 16, prg.length);
        Files.write(rom, image);
        Files.writeString(debug, debugRecord(source.toString(), source, rom));

        var program = Cc65DebugInfo.read(debug, prg, null);

        assertNull(program.lineAt(100));
        assertNull(program.firstSymbolAt(100, 0xC064));
        assertNotNull(program.lineAt(0));
    }

    private String debug(final Path source, final Path rom) throws Exception {
        return """
                version\tmajor=2,minor=0
                file\tid=0,name="%s",size=%d,mtime=0
                line\tid=0,file=0,line=2,span=0
                line\tid=1,file=0,line=4,span=1
                line\tid=2,file=0,line=5,span=2
                seg\tid=0,name="CODE",start=0xC000,size=6,addrsize=absolute,type=ro,oname="%s",ooffs=16
                span\tid=0,seg=0,start=0,size=1
                span\tid=1,seg=0,start=1,size=2
                span\tid=2,seg=0,start=3,size=3
                sym\tid=0,name="reset",addrsize=absolute,val=0xC000,seg=0,type=lab,def=0,ref=1
                sym\tid=1,name="loop",addrsize=absolute,val=0xC001,seg=0,type=lab
                """.formatted(source, Files.size(source), rom);
    }

    private String debugRecord(final String recorded, final Path source, final Path rom)
            throws Exception {
        return """
                version\tmajor=2,minor=0
                file\tid=0,name="%s",size=%d,mtime=%d
                line\tid=0,file=0,line=1,span=0
                seg\tid=0,name="CODE",start=0xC000,size=1,addrsize=absolute,type=ro,oname="%s",ooffs=16
                span\tid=0,seg=0,start=0,size=1
                """.formatted(
                recorded,
                Files.size(source),
                Files.getLastModifiedTime(source).toMillis() / 1_000,
                rom);
    }
}
