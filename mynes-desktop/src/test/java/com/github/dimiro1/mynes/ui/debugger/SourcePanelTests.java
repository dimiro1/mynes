package com.github.dimiro1.mynes.ui.debugger;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Exercises the source component in each debugger state without opening a window. */
class SourcePanelTests {
    @Test
    void sourceCurrentLineAndBreakpointRenderWithoutAWindow() {
        var file = new SourceProgram.SourceFile(
                0,
                "src/game.s",
                Path.of("src/game.s"),
                List.of("reset:", "    sei", "    jmp reset"),
                false);
        var first = new SourceProgram.Range(0, 0xC000, 1);
        var second = new SourceProgram.Range(1, 0xC001, 3);
        var program = new SourceProgram(
                Path.of("game.dbg"),
                List.of(file),
                Map.of(0, Map.of(2, List.of(first), 3, List.of(second))),
                List.of(
                        new SourceProgram.SourceLine(file, 2, "", List.of(first)),
                        new SourceProgram.SourceLine(file, 3, "", List.of(second))),
                List.of(),
                0x4000,
                List.of());
        var panel = new SourcePanel(new NoActions());

        panel.show(program, program.lineAt(0), Set.of(0));

        var listing = component(panel, RSyntaxTextArea.class);

        assertNotNull(listing);
        assertEquals(SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_6502,
                listing.getSyntaxEditingStyle());
        assertEquals(String.join("\n", file.text()), listing.getText());
        assertNotNull(listing.getTokenListForLine(1));

        panel.clearMachine();
        panel.setBreakpoints(Set.of());

        panel.detach();
        assertEquals("", listing.getText());
    }

    private static <T extends Component> T component(
            final Container parent, final Class<T> type) {
        for (var child : parent.getComponents()) {
            if (type.isInstance(child)) {
                return type.cast(child);
            }

            if (child instanceof Container container) {
                var nested = component(container, type);

                if (nested != null) {
                    return nested;
                }
            }
        }

        return null;
    }

    private static final class NoActions implements SourcePanel.Actions {
        @Override public void attach() { }
        @Override public void locateSources() { }
        @Override public void detach() { }
        @Override public void toggleBreakpoint(final SourceProgram.SourceLine line) { }
        @Override public void runTo(final SourceProgram.SourceLine line) { }
        @Override public void showInDisassembly(final SourceProgram.SourceLine line) { }
    }
}
