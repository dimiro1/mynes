package com.github.dimiro1.mynes.ui.debugger;

import net.miginfocom.swing.MigLayout;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Token;
import org.fife.ui.rtextarea.IconRowEvent;
import org.fife.ui.rtextarea.IconRowListener;
import org.fife.ui.rtextarea.RTextScrollPane;
import org.jetbrains.annotations.Nullable;

import javax.swing.BorderFactory;
import javax.swing.DefaultComboBoxModel;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.text.BadLocationException;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.InputEvent;
import java.util.Set;

/** A read-only, syntax-highlighted source listing over the linked bytes in a ca65 program. */
final class SourcePanel extends JPanel {
    interface Actions {
        void attach();

        void locateSources();

        void detach();

        void toggleBreakpoint(SourceProgram.SourceLine line);

        void runTo(SourceProgram.SourceLine line);

        void showInDisassembly(SourceProgram.SourceLine line);
    }

    private final DefaultComboBoxModel<SourceProgram.SourceFile> fileModel =
            new DefaultComboBoxModel<>();
    private final JComboBox<SourceProgram.SourceFile> files = new JComboBox<>(fileModel);
    private final Listing listing = new Listing();
    private final RTextScrollPane scroll = new RTextScrollPane(listing, true);
    private final JLabel note = Theme.note("Attach an ld65 .dbg file to follow source code.");
    private final JButton locate = new JButton("Find Sources…");
    private final JButton detach = new JButton("Detach");
    private final Actions actions;

    private @Nullable SourceProgram program;
    private @Nullable SourceProgram.SourceFile displayedFile;
    private @Nullable SourceProgram.SourceLine current;
    private @Nullable SourceProgram.SourceLine selected;
    private Set<Integer> breakpoints = Set.of();
    private boolean choosingFile;
    private boolean changingText;

    SourcePanel(final Actions actions) {
        super(new MigLayout("insets 4 8 8 8, fill", "[][grow][][pref!][]", "[][grow,fill][]"));

        this.actions = actions;

        listing.setFont(Theme.MONOSPACED);
        listing.setEditable(false);
        listing.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_ASSEMBLER_6502);
        listing.setCodeFoldingEnabled(false);
        listing.setHighlightCurrentLine(false);
        listing.setAntiAliasingEnabled(true);
        listing.setBackground(Theme.background());
        listing.setForeground(Theme.foreground());
        listing.setSelectionColor(Theme.selectionBackground());
        listing.setSelectedTextColor(Theme.selectionForeground());
        listing.addMouseListener(new Mouse());
        listing.addCaretListener(event -> {
            if (!changingText) {
                selected = lineAtCaret();
            }
        });

        scroll.setLineNumbersEnabled(true);
        scroll.setIconRowHeaderEnabled(true);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.dim()));
        scroll.getGutter().setLineNumberFont(Theme.MONOSPACED);
        scroll.getGutter().setLineNumberColor(Theme.muted());
        scroll.getGutter().setCurrentLineNumberColor(Theme.muted());
        scroll.getGutter().setBorderColor(Theme.dim());
        scroll.getGutter().setIconRowHeaderInheritsGutterBackground(true);
        scroll.getGutter().addIconRowListener(new GutterClicks());

        files.setEnabled(false);
        files.addActionListener(event -> {
            if (!choosingFile) {
                rebuild((SourceProgram.SourceFile) files.getSelectedItem());
            }
        });

        var attach = new JButton("Attach Debug Info…");

        attach.setToolTipText("Open the .dbg file written by ld65 --dbgfile");
        attach.addActionListener(event -> actions.attach());

        locate.setToolTipText("Choose a project or source folder when recorded paths have moved");
        locate.addActionListener(event -> actions.locateSources());
        locate.setVisible(false);

        detach.setToolTipText("Stop using source and symbols for this cartridge");
        detach.addActionListener(event -> actions.detach());
        detach.setEnabled(false);

        add(Theme.heading("Source"));
        add(files, "growx");
        add(attach);
        add(locate);
        add(detach, "wrap");
        add(scroll, "span 5, grow, wrap");
        add(note, "span 5, growx");
    }

    void show(
            final SourceProgram source,
            final @Nullable SourceProgram.SourceLine line,
            final Set<Integer> breaks) {

        if (program != source) {
            setProgram(source);
        }

        current = line;
        breakpoints = breaks;

        if (line != null && files.getSelectedItem() != line.file()) {
            choosingFile = true;
            files.setSelectedItem(line.file());
            choosingFile = false;
            rebuild(line.file());
        } else {
            refreshDecorations();
        }

        scrollToCurrent();
    }

    void setBreakpoints(final Set<Integer> breaks) {
        breakpoints = breaks;
        refreshDecorations();
    }

    void clearMachine() {
        current = null;
        refreshDecorations();
    }

    void detach() {
        program = null;
        displayedFile = null;
        current = null;
        selected = null;
        breakpoints = Set.of();
        fileModel.removeAllElements();
        setListingText("");
        refreshDecorations();
        files.setEnabled(false);
        locate.setVisible(false);
        detach.setEnabled(false);
        note.setText("Attach an ld65 .dbg file to follow source code.");
    }

    @Nullable SourceProgram.SourceLine selectedLine() {
        return selected != null ? selected : current;
    }

    private void setProgram(final SourceProgram source) {
        program = source;
        selected = null;
        choosingFile = true;
        fileModel.removeAllElements();
        source.files().forEach(fileModel::addElement);
        choosingFile = false;

        files.setEnabled(fileModel.getSize() > 0);
        locate.setVisible(source.hasMissingFiles());
        detach.setEnabled(true);

        if (source.warnings().isEmpty()) {
            note.setText(source.debugFile().getFileName() + " · source matches the build");
        } else {
            note.setText(String.join(" · ", source.warnings()));
        }

        rebuild((SourceProgram.SourceFile) files.getSelectedItem());
    }

    private void rebuild(final @Nullable SourceProgram.SourceFile file) {
        displayedFile = file;
        selected = null;

        if (program == null || file == null) {
            setListingText("");
            refreshDecorations();
            return;
        }

        var text = new StringBuilder();

        // A missing file may still have line records. Keep the line count stable so that a future
        // remap and every .dbg line number still point at the same row.
        for (var number = 1; ; number++) {
            var line = program.line(file, number);

            if (line == null) {
                break;
            }

            if (!text.isEmpty()) {
                text.append('\n');
            }

            text.append(line.text());
        }

        setListingText(text.toString());
        refreshDecorations();
    }

    private void setListingText(final String text) {
        changingText = true;

        try {
            listing.setText(text);
            listing.setCaretPosition(0);
        } finally {
            changingText = false;
        }
    }

    private void refreshDecorations() {
        listing.removeAllLineHighlights();
        scroll.getGutter().removeAllTrackingIcons();

        if (program == null || displayedFile == null) {
            return;
        }

        for (var number = 1; number <= listing.getLineCount(); number++) {
            var line = program.line(displayedFile, number);

            if (line == null) {
                continue;
            }

            var isCurrent = line == current;
            var hasBreakpoint = hasBreakpoint(line);

            try {
                if (isCurrent) {
                    listing.addLineHighlight(number - 1, Theme.currentRow());
                }

                if (isCurrent || hasBreakpoint) {
                    scroll.getGutter().addLineTrackingIcon(
                            number - 1,
                            new MarkerIcon(hasBreakpoint, isCurrent),
                            markerTooltip(line, hasBreakpoint, isCurrent));
                }
            } catch (BadLocationException ignored) {
                // The text and SourceProgram are rebuilt together; a transient document update can
                // only make this decoration one repaint late.
            }
        }

        listing.repaint();
    }

    private void scrollToCurrent() {
        if (current == null || displayedFile != current.file()) {
            return;
        }

        var line = current;

        SwingUtilities.invokeLater(() -> {
            try {
                var offset = listing.getLineStartOffset(line.number() - 1);
                var shape = listing.modelToView2D(offset);

                if (shape == null) {
                    return;
                }

                var bounds = shape.getBounds();
                var visible = listing.getVisibleRect();
                var centered = new Rectangle(
                        bounds.x,
                        Math.max(0, bounds.y - visible.height / 3),
                        Math.max(bounds.width, 1),
                        Math.max(visible.height, bounds.height));

                listing.scrollRectToVisible(centered);
            } catch (BadLocationException ignored) {
                // A stale or missing source can legitimately have fewer physical lines.
            }
        });
    }

    private boolean hasBreakpoint(final SourceProgram.SourceLine line) {
        return line.breakpointOffsets().stream().anyMatch(breakpoints::contains);
    }

    private @Nullable SourceProgram.SourceLine lineAtCaret() {
        return line(listing.getCaretLineNumber());
    }

    private @Nullable SourceProgram.SourceLine lineAt(final Point point) {
        try {
            return line(listing.getLineOfOffset(listing.viewToModel2D(point)));
        } catch (BadLocationException ignored) {
            return null;
        }
    }

    private @Nullable SourceProgram.SourceLine line(final int zeroBasedLine) {
        return program == null || displayedFile == null
                ? null : program.line(displayedFile, zeroBasedLine + 1);
    }

    private String markerTooltip(
            final SourceProgram.SourceLine line,
            final boolean hasBreakpoint,
            final boolean isCurrent) {
        var prefix = hasBreakpoint && isCurrent ? "Breakpoint · Current instruction · "
                : hasBreakpoint ? "Breakpoint · " : "Current instruction · ";

        return prefix + rangeTooltip(line);
    }

    private String rangeTooltip(final SourceProgram.SourceLine line) {
        return line.ranges().stream()
                .map(range -> String.format(
                        "PRG+$%05X · linked at $%04X · %d byte%s",
                        range.prgOffset(),
                        range.cpuAddress(),
                        range.size(),
                        range.size() == 1 ? "" : "s"))
                .reduce((left, right) -> left + " | " + right)
                .orElse(line.location());
    }

    private @Nullable SourceProgram.Symbol symbolAt(final Point point) {
        var line = lineAt(point);
        var token = listing.viewToToken(point);

        return symbol(line, token);
    }

    private @Nullable SourceProgram.Symbol symbolAtCaret() {
        return symbol(lineAtCaret(), listing.modelToToken(listing.getCaretPosition()));
    }

    private @Nullable SourceProgram.Symbol symbol(
            final @Nullable SourceProgram.SourceLine line, final @Nullable Token token) {
        if (program == null || line == null || token == null || !token.isPaintable()) {
            return null;
        }

        return program.symbolAt(line, token.getLexeme());
    }

    private String symbolTooltip(final SourceProgram.Symbol symbol) {
        var kind = switch (symbol.kind()) {
            case "lab" -> "label";
            case "equ" -> "constant";
            default -> symbol.kind();
        };
        var definition = symbol.definition() == null
                ? "No source definition in debug information"
                : "Defined at " + symbol.definition().displayName();

        return "<html><b>" + html(symbol.name()) + "</b> = $"
                + String.format("%04X", symbol.value() & 0xFFFF)
                + " · " + html(kind) + "<br>" + html(definition)
                + (symbol.definition() == null ? "" : "<br>Cmd/Ctrl-click to go to definition")
                + "</html>";
    }

    private static String html(final String text) {
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void goToDefinition(final SourceProgram.Symbol symbol) {
        var definition = symbol.definition();

        if (program == null || definition == null) {
            return;
        }

        if (displayedFile != definition.file()) {
            choosingFile = true;
            files.setSelectedItem(definition.file());
            choosingFile = false;
            rebuild(definition.file());
        }

        var line = program.line(definition.file(), definition.line());

        if (line == null) {
            return;
        }

        selected = line;

        try {
            var start = listing.getLineStartOffset(definition.line() - 1);
            var end = listing.getLineEndOffset(definition.line() - 1);
            var source = listing.getText(start, end - start);
            var column = source.indexOf(symbol.name());
            var symbolStart = column < 0 ? start : start + column;
            var symbolEnd = column < 0 ? start : symbolStart + symbol.name().length();

            listing.requestFocusInWindow();
            listing.select(symbolStart, symbolEnd);

            var shape = listing.modelToView2D(symbolStart);

            if (shape != null) {
                var bounds = shape.getBounds();
                var visible = listing.getVisibleRect();

                bounds.grow(0, Math.max(0, visible.height / 3));
                listing.scrollRectToVisible(bounds);
            }
        } catch (BadLocationException ignored) {
            // A changed source may no longer have the definition line recorded by the build.
        }
    }

    private final class Mouse extends MouseAdapter {
        @Override
        public void mouseClicked(final MouseEvent event) {
            if (!SwingUtilities.isLeftMouseButton(event)) {
                return;
            }

            if ((event.getModifiersEx()
                    & (InputEvent.META_DOWN_MASK | InputEvent.CTRL_DOWN_MASK)) != 0) {
                var symbol = symbolAt(event.getPoint());

                if (symbol != null && symbol.definition() != null) {
                    goToDefinition(symbol);
                    event.consume();
                }

                return;
            }

            if (event.getClickCount() == 2) {
                var line = lineAt(event.getPoint());

                if (line != null && line.hasCode()) {
                    actions.toggleBreakpoint(line);
                }
            }
        }
    }

    private final class GutterClicks implements IconRowListener {
        @Override
        public void bookmarkAdded(final IconRowEvent event) {
        }

        @Override
        public void bookmarkRemoved(final IconRowEvent event) {
        }

        @Override
        public void mouseClicked(final IconRowEvent event, final MouseEvent mouse) {
            var line = line(event.getLine());

            if (SwingUtilities.isLeftMouseButton(mouse) && line != null && line.hasCode()) {
                selected = line;
                actions.toggleBreakpoint(line);
                event.consume();
            }
        }
    }

    private final class Listing extends RSyntaxTextArea {
        private JMenuItem toggle;
        private JMenuItem runTo;
        private JMenuItem definition;
        private JMenuItem disassembly;

        @Override
        public String getToolTipText(final MouseEvent event) {
            var line = lineAt(event.getPoint());
            var symbol = symbolAt(event.getPoint());

            return symbol != null ? symbolTooltip(symbol)
                    : line == null || !line.hasCode() ? null : rangeTooltip(line);
        }

        @Override
        protected JPopupMenu createPopupMenu() {
            var menu = super.createPopupMenu();

            menu.addSeparator();

            toggle = new JMenuItem("Set Breakpoint");
            toggle.addActionListener(ignored -> withSelected(actions::toggleBreakpoint));
            menu.add(toggle);

            runTo = new JMenuItem("Run to Line");
            runTo.addActionListener(ignored -> withSelected(actions::runTo));
            menu.add(runTo);

            definition = new JMenuItem("Go to Definition");
            definition.addActionListener(ignored -> {
                var symbol = symbolAtCaret();

                if (symbol != null) {
                    goToDefinition(symbol);
                }
            });
            menu.add(definition);

            disassembly = new JMenuItem("Show in Disassembly");
            disassembly.addActionListener(ignored -> withSelected(actions::showInDisassembly));
            menu.add(disassembly);

            return menu;
        }

        @Override
        protected void configurePopupMenu(final JPopupMenu menu) {
            super.configurePopupMenu(menu);

            var line = lineAtCaret();
            var enabled = line != null && line.hasCode();
            var symbol = symbolAtCaret();

            toggle.setText(line != null && hasBreakpoint(line)
                    ? "Remove Breakpoint" : "Set Breakpoint");
            toggle.setEnabled(enabled);
            runTo.setEnabled(enabled);
            definition.setEnabled(symbol != null && symbol.definition() != null);
            disassembly.setEnabled(enabled);
        }

        @Override
        protected void processMouseEvent(final MouseEvent event) {
            if (event.isPopupTrigger()) {
                var line = lineAt(event.getPoint());

                if (line != null) {
                    selected = line;
                    setCaretPosition(viewToModel2D(event.getPoint()));
                }
            }

            super.processMouseEvent(event);
        }

        private void withSelected(
                final java.util.function.Consumer<SourceProgram.SourceLine> action) {
            var line = selectedLine();

            if (line != null && line.hasCode()) {
                action.accept(line);
            }
        }
    }

    /** One gutter slot that can show a breakpoint, the PC arrow, or both without overlap. */
    private record MarkerIcon(boolean breakpoint, boolean current) implements Icon {
        private static final int WIDTH = 24;
        private static final int HEIGHT = 14;

        @Override
        public int getIconWidth() {
            return WIDTH;
        }

        @Override
        public int getIconHeight() {
            return HEIGHT;
        }

        @Override
        public void paintIcon(
                final Component component, final Graphics graphics, final int x, final int y) {
            var g = (Graphics2D) graphics.create();

            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                        RenderingHints.VALUE_ANTIALIAS_ON);

                if (breakpoint) {
                    g.setColor(Theme.breakpoint());
                    g.fillOval(x + 2, y + 3, 9, 9);
                }

                if (current) {
                    g.setColor(Theme.accent());
                    g.fillPolygon(
                            new int[]{x + 14, x + 22, x + 14},
                            new int[]{y + 2, y + 7, y + 12},
                            3);
                }
            } finally {
                g.dispose();
            }
        }
    }
}
