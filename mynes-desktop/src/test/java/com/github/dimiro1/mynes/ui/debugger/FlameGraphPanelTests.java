package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.ExecutionProfile;
import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.Test;

import java.awt.event.MouseEvent;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlameGraphPanelTests {
    @Test
    void balancedWidthsGiveSmallerPathsMoreRoomWithoutChangingTheirCycleCounts() {
        var heavy = node(ExecutionProfile.FrameKind.LOCATION, 0, 0x8000,
                90, 900, List.of());
        var small = node(ExecutionProfile.FrameKind.LOCATION, 1, 0x8001,
                10, 100, List.of());
        var root = node(ExecutionProfile.FrameKind.ROOT, -1, -1,
                0, 0, List.of(heavy, small));
        var graph = new FlameGraphPanel((line, offset) -> {});
        graph.show(new ExecutionProfile.Snapshot(List.of(), 100, 1000, root), null);
        Views.paint(graph);

        var balanced = graph.getToolTipText(mouse(graph, MouseEvent.MOUSE_MOVED,
                850, 165, MouseEvent.BUTTON1));
        assertTrue(balanced.contains("PRG $00001"));
        assertTrue(balanced.contains("100 run cycles (10.0% of profile)"));

        graph.setBalancedWidths(false);
        Views.paint(graph);
        assertTrue(graph.getToolTipText(mouse(graph, MouseEvent.MOUSE_MOVED,
                850, 165, MouseEvent.BUTTON1)).contains("PRG $00000"));
    }

    @Test
    void zoomsThroughCallFramesAndNavigatesOnlyFromTheFinalLocation() {
        var file = new SourceProgram.SourceFile(1, "src/main.s", Path.of("src/main.s"),
                List.of("draw_sprite:", "  nop"), false);
        var range = new SourceProgram.Range(6, 0x8006, 1);
        var line = new SourceProgram.SourceLine(file, 2, "  nop", List.of(range));
        var symbol = new SourceProgram.Symbol("draw_sprite", "lab", 0x8006, 6,
                new SourceProgram.Location(file, 1), List.of());
        var program = new SourceProgram(Path.of("game.dbg"), List.of(file),
                Map.of(1, Map.of(2, List.of(range))), List.of(line), List.of(symbol), 0x40,
                List.of());
        var location = node(ExecutionProfile.FrameKind.LOCATION, 6, 0x8006,
                4, 14, List.of());
        var call = node(ExecutionProfile.FrameKind.CALL, 6, 0x8006,
                0, 0, List.of(location));
        var caller = node(ExecutionProfile.FrameKind.LOCATION, 0, 0x8000,
                2, 6, List.of());
        var root = node(ExecutionProfile.FrameKind.ROOT, -1, -1,
                0, 0, List.of(call, caller));
        var navigatedLine = new AtomicReference<SourceProgram.SourceLine>();
        var navigatedOffset = new AtomicInteger(-1);
        var graph = new FlameGraphPanel((source, offset) -> {
            navigatedLine.set(source);
            navigatedOffset.set(offset);
        });
        var snapshot = new ExecutionProfile.Snapshot(List.of(), 6, 20, root);
        graph.show(snapshot, program);
        Views.paint(graph);

        var callTip = graph.getToolTipText(mouse(graph, MouseEvent.MOUSE_MOVED,
                20, 165, MouseEvent.BUTTON1));
        assertTrue(callTip.contains("draw_sprite"));
        assertTrue(callTip.contains("double-click to zoom"));
        graph.dispatchEvent(mouse(graph, MouseEvent.MOUSE_CLICKED,
                20, 165, MouseEvent.BUTTON1));
        graph.dispatchEvent(mouse(graph, MouseEvent.MOUSE_CLICKED,
                20, 165, MouseEvent.BUTTON1, 2));
        assertNull(navigatedLine.get(), "a call with children must zoom instead of opening code");
        assertEquals(-1, navigatedOffset.get());
        Views.paint(graph);
        assertTrue(graph.getToolTipText(mouse(graph, MouseEvent.MOUSE_MOVED,
                20, 190, MouseEvent.BUTTON1)).contains("draw_sprite"));
        assertTrue(graph.canZoomBack());

        graph.dispatchEvent(mouse(graph, MouseEvent.MOUSE_CLICKED,
                20, 165, MouseEvent.BUTTON1));
        graph.dispatchEvent(mouse(graph, MouseEvent.MOUSE_CLICKED,
                20, 165, MouseEvent.BUTTON1, 2));
        assertNull(navigatedLine.get(), "the final location must be zoomed before opening code");
        assertEquals(-1, navigatedOffset.get());
        Views.paint(graph);
        assertTrue(graph.getToolTipText(mouse(graph, MouseEvent.MOUSE_MOVED,
                20, 190, MouseEvent.BUTTON1)).contains("Open: main.s:2"));
        graph.dispatchEvent(mouse(graph, MouseEvent.MOUSE_CLICKED,
                20, 190, MouseEvent.BUTTON1));
        graph.dispatchEvent(mouse(graph, MouseEvent.MOUSE_CLICKED,
                20, 190, MouseEvent.BUTTON1, 2));
        assertEquals(line, navigatedLine.get());
        assertEquals(6, navigatedOffset.get());

        graph.show(snapshot, program);
        graph.zoomBack();
        Views.paint(graph);
        assertTrue(graph.getToolTipText(mouse(graph, MouseEvent.MOUSE_MOVED,
                20, 190, MouseEvent.BUTTON1)).contains("draw_sprite"));
        assertTrue(graph.canZoomBack());
        graph.zoomBack();
        Views.paint(graph);
        assertTrue(graph.getToolTipText(mouse(graph, MouseEvent.MOUSE_MOVED,
                20, 190, MouseEvent.BUTTON1)).contains("Profile"));
        assertFalse(graph.canZoomBack());

        graph.dispatchEvent(mouse(graph, MouseEvent.MOUSE_CLICKED,
                20, 165, MouseEvent.BUTTON1));
        graph.applyPendingZoom();
        assertTrue(graph.canZoomBack());
        graph.dispatchEvent(mouse(graph, MouseEvent.MOUSE_CLICKED,
                20, 190, MouseEvent.BUTTON3));
        Views.paint(graph);
        assertTrue(graph.getToolTipText(mouse(graph, MouseEvent.MOUSE_MOVED,
                20, 190, MouseEvent.BUTTON1)).contains("Profile"));
        assertFalse(graph.canZoomBack());
    }

    private static ExecutionProfile.FlameNode node(
            final ExecutionProfile.FrameKind kind, final int offset, final int address,
            final long selfInstructions, final long selfCycles,
            final List<ExecutionProfile.FlameNode> children) {
        var instructions = selfInstructions + children.stream()
                .mapToLong(ExecutionProfile.FlameNode::instructions).sum();
        var cycles = selfCycles + children.stream()
                .mapToLong(ExecutionProfile.FlameNode::cycles).sum();
        return new ExecutionProfile.FlameNode(new ExecutionProfile.Frame(kind, offset, address),
                selfInstructions, selfCycles, instructions, cycles, children);
    }

    private static MouseEvent mouse(final FlameGraphPanel graph, final int kind,
                                    final int x, final int y, final int button) {
        return mouse(graph, kind, x, y, button, 1);
    }

    private static MouseEvent mouse(final FlameGraphPanel graph, final int kind,
                                    final int x, final int y, final int button,
                                    final int clicks) {
        return new MouseEvent(graph, kind, System.currentTimeMillis(), 0,
                x, y, clicks, false, button);
    }
}
