package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.debug.ExecutionProfile;
import com.github.dimiro1.mynes.ui.AppearanceAware;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** An interactive, cycle-weighted view of the paths captured by {@link ExecutionProfile}. */
final class FlameGraphPanel extends JPanel implements AppearanceAware {
    private static final int ROW_HEIGHT = 25;
    private static final int PAD = 8;

    private static final class ViewNode {
        final String key;
        final String label;
        final ExecutionProfile.Frame frame;
        final ViewNode parent;
        final List<ViewNode> children = new ArrayList<>();
        long selfCycles;
        long instructions;
        long cycles;

        ViewNode(final String key, final String label, final ExecutionProfile.Frame frame,
                 final ViewNode parent, final long selfCycles,
                 final long instructions, final long cycles) {
            this.key = key;
            this.label = label;
            this.frame = frame;
            this.parent = parent;
            this.selfCycles = selfCycles;
            this.instructions = instructions;
            this.cycles = cycles;
        }
    }

    private record Hit(Rectangle bounds, ViewNode node) {
    }

    private final List<Hit> hits = new ArrayList<>();
    private final ArrayDeque<List<String>> zoomHistory = new ArrayDeque<>();
    private ViewNode root;
    private ViewNode visibleRoot;
    private ViewNode pendingZoom;
    private SourceProgram program;
    private boolean balancedWidths = true;
    private Runnable zoomChanged = () -> {};
    private final Timer zoomTimer;

    FlameGraphPanel(final ProfilerPanel.Navigator navigate) {
        setFont(Theme.MONOSPACED);
        setToolTipText(" ");
        zoomTimer = new Timer(doubleClickDelay(), e -> applyPendingZoom());
        zoomTimer.setRepeats(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                if (SwingUtilities.isRightMouseButton(event)) {
                    resetZoom();
                } else if (SwingUtilities.isLeftMouseButton(event)) {
                    if (event.getClickCount() == 2) {
                        zoomTimer.stop();
                        var target = pendingZoom;
                        pendingZoom = null;
                        if (target == null) return;
                        if (target != visibleRoot) {
                            zoomTo(target);
                        } else if (!hasZoomLevel(target) && target.frame.prgOffset() >= 0) {
                            var offset = target.frame.prgOffset();
                            navigate.navigate(navigationLine(target), offset);
                        }
                        return;
                    }
                    if (event.getClickCount() != 1) return;
                    var hit = hitAt(event);
                    pendingZoom = hit == null ? null : hit.node();
                    if (pendingZoom != null) zoomTimer.restart();
                }
            }
        });
    }

    private static int doubleClickDelay() {
        var setting = Toolkit.getDefaultToolkit().getDesktopProperty("awt.multiClickInterval");
        return setting instanceof Integer delay && delay > 0 ? delay + 50 : 500;
    }

    /** Applies a single click after the double-click window has passed. */
    void applyPendingZoom() {
        zoomTimer.stop();
        var target = pendingZoom;
        pendingZoom = null;
        zoomTo(target);
    }

    private void zoomTo(final ViewNode target) {
        if (target == null || target == visibleRoot) return;
        zoomHistory.addLast(pathTo(visibleRoot));
        visibleRoot = target;
        zoomChanged.run();
        revalidate();
        repaint();
    }

    private static boolean hasZoomLevel(final ViewNode node) {
        return node.children.stream().anyMatch(child -> child.cycles > 0);
    }

    void setZoomChanged(final Runnable listener) {
        zoomChanged = listener;
        zoomChanged.run();
    }

    boolean canZoomBack() {
        return !zoomHistory.isEmpty();
    }

    void zoomBack() {
        zoomTimer.stop();
        pendingZoom = null;
        if (zoomHistory.isEmpty()) return;
        visibleRoot = findPath(root, zoomHistory.removeLast());
        zoomChanged.run();
        revalidate();
        repaint();
    }

    void show(final ExecutionProfile.Snapshot snapshot, final SourceProgram source) {
        program = source;
        zoomTimer.stop();
        pendingZoom = null;
        var oldPath = pathTo(visibleRoot);
        root = build(snapshot.flame(), null);
        visibleRoot = findPath(root, oldPath);
        if (!oldPath.isEmpty() && visibleRoot == root) zoomHistory.clear();
        zoomChanged.run();
        revalidate();
        repaint();
    }

    void clear() {
        root = null;
        visibleRoot = null;
        zoomTimer.stop();
        pendingZoom = null;
        zoomHistory.clear();
        hits.clear();
        zoomChanged.run();
        revalidate();
        repaint();
    }

    void resetZoom() {
        zoomTimer.stop();
        pendingZoom = null;
        zoomHistory.clear();
        visibleRoot = root;
        zoomChanged.run();
        revalidate();
        repaint();
    }

    void setBalancedWidths(final boolean balanced) {
        balancedWidths = balanced;
        repaint();
    }

    @Override
    public void refreshAppearance() {
        repaint();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(1050,
                Math.max(220, PAD * 2 + ROW_HEIGHT * depth(visibleRoot)));
    }

    @Override
    public String getToolTipText(final MouseEvent event) {
        var hit = hitAt(event);
        if (hit == null) return null;
        var node = hit.node();
        var share = root == null || root.cycles == 0 ? 0
                : 100.0 * node.cycles / root.cycles;
        var canOpen = node == visibleRoot && !hasZoomLevel(node)
                && node.frame.prgOffset() >= 0;
        var destination = canOpen ? navigationLine(node) : null;
        var opens = !canOpen ? ""
                : "<br>Open: " + escape(destination == null
                        ? String.format("PRG $%05X", node.frame.prgOffset())
                        : destination.location());
        var action = node != visibleRoot ? "Click or double-click to zoom"
                : hasZoomLevel(node) ? "Click a child to zoom"
                : canOpen ? "Double-click to open code" : "No deeper zoom level";
        return "<html><b>" + escape(node.label) + "</b><br>"
                + String.format("%,d run cycles (%.1f%% of profile)<br>%,d instructions",
                        node.cycles, share, node.instructions)
                + opens
                + (balancedWidths ? "<br>Widths use square-root scaling" : "")
                + "<br>" + action + "; right-click to reset.</html>";
    }

    private SourceProgram.SourceLine navigationLine(final ViewNode node) {
        if (program == null || node.frame.prgOffset() < 0) return null;
        var offset = node.frame.prgOffset();
        if (node.frame.kind() != ExecutionProfile.FrameKind.LOCATION) {
            var definition = program.functionDefinitionAt(offset, node.frame.cpuAddress());
            if (definition != null) return definition;
        }
        return program.lineAt(offset);
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        super.paintComponent(graphics);
        hits.clear();
        var g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.background());
            g.fillRect(0, 0, getWidth(), getHeight());
            if (visibleRoot == null || visibleRoot.cycles == 0) {
                g.setColor(Theme.muted());
                g.drawString("Start profiling and refresh to see call paths.",
                        PAD + 4, PAD + g.getFontMetrics().getAscent() + 4);
                return;
            }
            paintNode(g, visibleRoot, PAD, Math.max(1, getWidth() - PAD * 2), 0);
        } finally {
            g.dispose();
        }
    }

    private void paintNode(final Graphics2D g, final ViewNode node,
                           final int x, final int width, final int level) {
        if (width <= 0) return;
        var y = getHeight() - PAD - (level + 1) * ROW_HEIGHT;
        var bounds = new Rectangle(x, y, width, ROW_HEIGHT - 2);
        hits.add(new Hit(bounds, node));
        g.setColor(colour(node.frame));
        g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        g.setColor(Theme.isDark() ? new Color(0x25272A) : new Color(0xFFFFFF));
        g.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);

        var metrics = g.getFontMetrics();
        var label = clipped(node.label, metrics, bounds.width - 8);
        if (!label.isEmpty()) {
            g.setColor(Theme.isDark() ? Color.WHITE : new Color(0x202124));
            var old = g.getClip();
            g.clip(bounds);
            g.drawString(label, x + 4,
                    y + (bounds.height + metrics.getAscent() - metrics.getDescent()) / 2);
            g.setClip(old);
        }

        var totalWeight = weight(node.selfCycles);
        for (var child : node.children) totalWeight += weight(child.cycles);
        if (totalWeight == 0) return;
        var next = x;
        var consumed = 0.0;
        for (var child : node.children) {
            consumed += weight(child.cycles);
            var end = x + (int) Math.round(width * consumed / totalWeight);
            if (end > next) paintNode(g, child, next, end - next, level + 1);
            next = end;
        }
    }

    private double weight(final long cycles) {
        return balancedWidths ? Math.sqrt(cycles) : cycles;
    }

    private ViewNode build(final ExecutionProfile.FlameNode source, final ViewNode parent) {
        var frame = source.frame();
        var label = label(frame);
        var key = frame.kind() == ExecutionProfile.FrameKind.LOCATION
                ? "location:" + label : frame.toString();
        var result = new ViewNode(key, label, frame, parent,
                source.selfCycles(), source.instructions(), source.cycles());
        Map<String, ViewNode> locations = new LinkedHashMap<>();
        for (var child : source.children()) {
            var converted = build(child, result);
            if (converted.frame.kind() == ExecutionProfile.FrameKind.LOCATION) {
                var existing = locations.putIfAbsent(converted.key, converted);
                if (existing != null) {
                    existing.instructions += converted.instructions;
                    existing.selfCycles += converted.selfCycles;
                    existing.cycles += converted.cycles;
                    continue;
                }
            }
            result.children.add(converted);
        }
        result.children.sort(Comparator.comparingLong((ViewNode node) -> node.cycles).reversed());
        return result;
    }

    private String label(final ExecutionProfile.Frame frame) {
        var location = codeName(frame.prgOffset(), frame.cpuAddress());
        return switch (frame.kind()) {
            case ROOT -> "Profile";
            case CALL -> location;
            case NMI -> "NMI → " + location;
            case IRQ -> "IRQ → " + location;
            case BRK -> "BRK → " + location;
            case LOCATION -> program == null || frame.prgOffset() < 0
                    ? location : sourceLineOrOffset(frame.prgOffset());
        };
    }

    private String codeName(final int offset, final int cpuAddress) {
        if (offset < 0) return String.format("RAM $%04X", cpuAddress & 0xFFFF);
        if (program != null) {
            var function = program.functionAt(offset);
            if (function != null) return function;
            var line = program.lineAt(offset);
            if (line != null) return line.location();
        }
        return String.format("PRG $%05X", offset);
    }

    private String sourceLineOrOffset(final int offset) {
        var line = program.lineAt(offset);
        return line == null ? String.format("PRG $%05X", offset) : line.location();
    }

    private static int depth(final ViewNode node) {
        if (node == null) return 1;
        var deepest = 0;
        for (var child : node.children) deepest = Math.max(deepest, depth(child));
        return 1 + deepest;
    }

    private static List<String> pathTo(final ViewNode node) {
        if (node == null) return List.of();
        var path = new ArrayList<String>();
        for (var cursor = node; cursor.parent != null; cursor = cursor.parent) {
            path.addFirst(cursor.key);
        }
        return path;
    }

    private static ViewNode findPath(final ViewNode root, final List<String> path) {
        var cursor = root;
        for (var key : path) {
            var next = cursor.children.stream().filter(child -> child.key.equals(key))
                    .findFirst().orElse(null);
            if (next == null) return root;
            cursor = next;
        }
        return cursor;
    }

    private static String clipped(final String label, final FontMetrics metrics, final int width) {
        if (width < metrics.charWidth('M')) return "";
        if (metrics.stringWidth(label) <= width) return label;
        var end = label.length();
        while (end > 0 && metrics.stringWidth(label.substring(0, end) + "…") > width) end--;
        return end == 0 ? "" : label.substring(0, end) + "…";
    }

    private static Color colour(final ExecutionProfile.Frame frame) {
        var hue = switch (frame.kind()) {
            case ROOT -> 0.58f;
            case CALL -> 0.07f;
            case NMI, IRQ, BRK -> 0.76f;
            case LOCATION -> 0.52f;
        };
        hue += (Math.floorMod(frame.prgOffset(), 7) - 3) * 0.008f;
        return Color.getHSBColor(hue, Theme.isDark() ? 0.50f : 0.32f,
                Theme.isDark() ? 0.52f : 0.88f);
    }

    private Hit hitAt(final MouseEvent event) {
        for (var index = hits.size() - 1; index >= 0; index--) {
            var hit = hits.get(index);
            if (hit.bounds().contains(event.getPoint())) return hit;
        }
        return null;
    }

    private static String escape(final String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
