package com.github.dimiro1.mynes.ui.debugger;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourceReloadTests {
    @Test
    void movesSourceBreakpointsWhenTheRebuildMovesCode() {
        var before = program(1, 4);
        var after = program(7, 24);

        assertEquals(Set.of(24, 100), after.relocateBreakpointsFrom(before, Set.of(4, 100)));
        assertEquals(24, after.correspondingLine(before.lineAt(4))
                .breakpointOffsets().iterator().next());
    }

    @Test
    void movesSourceBreakpointsEvenWhenTheOldOffsetIsBeyondTheRebuiltRom() {
        var before = program(1, 0x200);
        var after = program(7, 8, 0x100);

        assertEquals(Set.of(8), after.relocateBreakpointsFrom(before, Set.of(0x200)));
    }

    private static SourceProgram program(final int fileId, final int offset) {
        return program(fileId, offset, 0x4000);
    }

    private static SourceProgram program(final int fileId, final int offset, final int prgBytes) {
        var file = new SourceProgram.SourceFile(fileId, "src/game.s", Path.of("src/game.s"),
                List.of("reset:", "  sei"), false);
        var range = new SourceProgram.Range(offset, 0xC000 + offset, 1);
        return new SourceProgram(Path.of("game.dbg"), List.of(file),
                Map.of(fileId, Map.of(2, List.of(range))),
                List.of(new SourceProgram.SourceLine(file, 2, "", List.of(range))),
                List.of(), prgBytes, List.of());
    }
}
