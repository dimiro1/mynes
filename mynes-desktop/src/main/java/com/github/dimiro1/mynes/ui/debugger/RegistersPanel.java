package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.APUChannel;
import com.github.dimiro1.mynes.Controller;
import com.github.dimiro1.mynes.ui.Readout;
import com.github.dimiro1.mynes.ui.sound.Notes;

import javax.swing.BorderFactory;
import javax.swing.JTree;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ToolTipManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * A compact, expandable view of the stopped machine's registers, stack and RAM symbols.
 * CPU stays open by default. The other groups are available without taking room from the code
 * listing until they are needed. Values come only from a stop snapshot or a frame readout; painting
 * and expanding the tree never reads the running machine.
 */
final class RegistersPanel extends JPanel {
    private static final String[] CPU = {"PC", "A", "X", "Y", "SP", "P", "flags", "cycles"};
    private static final String[] PPU = {
            "frame", "beam", "NMI", "background", "sprite layer", "render", "sprites",
            "patterns", "nametable", "scroll", "sprite 0", "overflow", "v", "t",
            "fine x", "latch"};
    private static final String[] APU = {
            "Pulse 1", "Pulse 2", "Triangle", "Noise", "DMC", "sequence", "IRQ"};
    private static final String[] INPUT = {"pad 1", "pad 2", "lag"};

    private final DefaultMutableTreeNode root = group("State");
    private final DefaultMutableTreeNode cpu = group("CPU");
    private final DefaultMutableTreeNode ppu = group("PPU");
    private final DefaultMutableTreeNode apu = group("APU");
    private final DefaultMutableTreeNode input = group("Input");
    private final DefaultMutableTreeNode stack = group("Stack");
    private final DefaultMutableTreeNode variables = group("Variables");
    private final Map<String, DefaultMutableTreeNode> values = new LinkedHashMap<>();
    private final DefaultTreeModel model = new DefaultTreeModel(root);
    private final JTree tree = new JTree(model) {
        @Override
        public String getToolTipText(final MouseEvent event) {
            var path = getPathForLocation(event.getX(), event.getY());
            if (path == null) {
                return null;
            }
            var item = (Item) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
            return item.tip;
        }
    };
    private final IntConsumer showInMemory;

    private MachineSnapshot snapshot;
    private SourceProgram program;
    private boolean stale = true;

    RegistersPanel(final IntConsumer showInMemory) {
        super(new BorderLayout());
        this.showInMemory = showInMemory;

        root.add(cpu);
        root.add(ppu);
        root.add(apu);
        root.add(input);
        root.add(stack);
        root.add(variables);
        for (var name : CPU) {
            addValue(cpu, name);
        }
        for (var name : PPU) {
            addValue(ppu, name);
        }
        for (var name : APU) {
            addValue(apu, name);
        }
        for (var name : INPUT) {
            addValue(input, name);
        }
        rebuildStack(null, null);
        populateVariables();

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Theme.MONOSPACED);
        tree.setRowHeight(tree.getFontMetrics(Theme.MONOSPACED).getHeight() + 5);
        tree.setBackground(Theme.background());
        tree.setCellRenderer(new Renderer());
        tree.expandPath(new TreePath(cpu.getPath()));
        ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(final MouseEvent event) {
                if (event.getClickCount() != 2) {
                    return;
                }
                var path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null) {
                    return;
                }
                var item = (Item) ((DefaultMutableTreeNode) path.getLastPathComponent()).getUserObject();
                if (item.address >= 0) {
                    RegistersPanel.this.showInMemory.accept(item.address);
                }
            }
        });

        var scroll = new JScrollPane(tree);
        scroll.setBorder(BorderFactory.createLineBorder(Theme.dim()));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 8));
        add(scroll, BorderLayout.CENTER);
    }

    void setSourceProgram(final SourceProgram program) {
        this.program = program;
        populateVariables();
        refreshVariables(snapshot, null);
    }

    void show(final MachineSnapshot stopped) {
        var previous = snapshot;
        snapshot = stopped;
        fill(stopped.machine(), previous == null ? null : previous.machine());
        rebuildStack(stopped, previous);
        refreshVariables(stopped, previous);
        stale = false;
        tree.repaint();
    }

    void live(final Readout machine) {
        fill(machine, null);
        stale();
    }

    void stale() {
        stale = true;
        tree.repaint();
    }

    void reset() {
        snapshot = null;
        values.values().forEach(node -> {
            var item = (Item) node.getUserObject();
            item.value = "--";
            item.changed = false;
            item.address = -1;
            item.tip = null;
            model.nodeChanged(node);
        });
        rebuildStack(null, null);
        refreshVariables(null, null);
        stale();
    }

    private void fill(final Readout machine, final Readout previous) {
        var now = readoutValues(machine);
        var before = previous == null ? Map.<String, String>of() : readoutValues(previous);
        now.forEach((name, value) -> {
            var node = values.get(name);
            var item = (Item) node.getUserObject();
            item.value = value;
            item.changed = tracksChange(name) && before.containsKey(name)
                    && !before.get(name).equals(value);
            item.address = switch (name) {
                case "PC" -> machine.cpu().pc();
                case "SP" -> machine.stackTop();
                default -> -1;
            };
            var earlier = item.changed ? "was " + before.get(name) : null;
            item.tip = item.address < 0 ? earlier
                    : earlier == null ? "Double-click to show in Memory"
                    : earlier + " · Double-click to show in Memory";
            model.nodeChanged(node);
        });
    }

    private static Map<String, String> readoutValues(final Readout machine) {
        var out = new LinkedHashMap<String, String>();
        var registers = machine.cpu();
        out.put("PC", String.format("$%04X", registers.pc()));
        out.put("A", String.format("$%02X", registers.a()));
        out.put("X", String.format("$%02X", registers.x()));
        out.put("Y", String.format("$%02X", registers.y()));
        out.put("SP", String.format("$%02X", registers.sp()));
        out.put("P", String.format("$%02X", registers.p()));
        out.put("flags", machine.flags());
        out.put("cycles", Long.toString(registers.cycles()));
        out.put("frame", Long.toString(machine.frame()));
        out.put("beam", machine.scanline() + " : " + machine.dot());
        out.put("NMI", on(machine.control(), 0x80));
        out.put("background", on(machine.mask(), 0x08));
        out.put("sprite layer", on(machine.mask(), 0x10));
        out.put("v", String.format("$%04X", machine.v()));
        out.put("t", String.format("$%04X", machine.t()));
        out.put("fine x", Integer.toString(machine.fineX()));
        out.put("latch", machine.writeLatch() ? "second" : "first");
        out.put("render", machine.renderingEnabled() ? "on" : "off");
        out.put("sprites", "8x" + machine.spriteHeight());
        out.put("patterns", String.format("bg $%04X  spr $%04X",
                machine.backgroundPatternTable(), machine.spritePatternTable()));
        out.put("nametable", String.format("$%04X", 0x2000 + (machine.control() & 3) * 0x400));
        out.put("scroll", machine.scrollX() + ", " + machine.scrollY());
        out.put("sprite 0", on(machine.status(), 0x40));
        out.put("overflow", on(machine.status(), 0x20));
        for (var channel : APUChannel.values()) {
            var voice = machine.voice(channel);
            var note = voice.playing() && voice.pitch() > 0 ? Notes.nameOf(voice.pitch()) : null;
            out.put(channel.label(), on(machine.apuStatus(), 1 << channel.ordinal())
                    + (note == null ? "" : "  " + note));
        }
        out.put("sequence", machine.fiveStep() ? "5-step" : "4-step");
        var irq = new StringBuilder(machine.frameIRQInhibited() ? "inhibited" : "enabled");
        if ((machine.apuStatus() & 0x40) != 0) {
            irq.append("  frame pending");
        }
        if ((machine.apuStatus() & 0x80) != 0) {
            irq.append("  DMC pending");
        }
        out.put("IRQ", irq.toString());
        out.put("pad 1", buttons(machine.pad1()));
        out.put("pad 2", buttons(machine.pad2()));
        var pads = machine.pads();
        out.put("lag", pads.frames() == 0 ? "—"
                : pads.lagFrames() + " of " + pads.frames());
        return out;
    }

    private static String on(final int register, final int bit) {
        return (register & bit) != 0 ? "on" : "off";
    }

    private static String buttons(final int held) {
        var names = new java.util.ArrayList<String>();
        if ((held & Controller.BUTTON_LEFT) != 0) names.add("←");
        if ((held & Controller.BUTTON_UP) != 0) names.add("↑");
        if ((held & Controller.BUTTON_DOWN) != 0) names.add("↓");
        if ((held & Controller.BUTTON_RIGHT) != 0) names.add("→");
        if ((held & Controller.BUTTON_SELECT) != 0) names.add("Sel");
        if ((held & Controller.BUTTON_START) != 0) names.add("Start");
        if ((held & Controller.BUTTON_B) != 0) names.add("B");
        if ((held & Controller.BUTTON_A) != 0) names.add("A");
        return names.isEmpty() ? "—" : String.join(" ", names);
    }

    private static boolean tracksChange(final String name) {
        return !name.equals("cycles") && !name.equals("frame") && !name.equals("beam");
    }

    private void rebuildStack(final MachineSnapshot stopped, final MachineSnapshot previous) {
        var expanded = tree.isExpanded(new TreePath(stack.getPath()));
        var bytes = stopped == null ? new int[0] : stopped.stack();
        if (bytes.length > 0 && stack.getChildCount() == bytes.length
                && ((Item) ((DefaultMutableTreeNode) stack.getChildAt(0))
                        .getUserObject()).address == stopped.stackTop()) {
            for (var index = 0; index < bytes.length; index++) {
                var node = (DefaultMutableTreeNode) stack.getChildAt(index);
                updateStackItem((Item) node.getUserObject(), previous, bytes, index);
                model.nodeChanged(node);
            }
            return;
        }
        stack.removeAllChildren();
        if (bytes.length == 0) {
            stack.add(leaf("Empty", "", false, -1, null));
        } else {
            for (var index = 0; index < bytes.length; index++) {
                var address = stopped.stackTop() + index;
                var node = leaf(String.format("$%04X", address), "", false, address, null);
                updateStackItem((Item) node.getUserObject(), previous, bytes, index);
                stack.add(node);
            }
        }
        model.nodeStructureChanged(stack);
        if (expanded) {
            tree.expandPath(new TreePath(stack.getPath()));
        }
    }

    private static void updateStackItem(
            final Item item, final MachineSnapshot previous,
            final int[] bytes, final int index) {
        var word = index + 1 < bytes.length
                ? String.format("  → $%04X", bytes[index] | bytes[index + 1] << 8) : "";
        item.value = String.format("$%02X%s", bytes[index], word);
        item.changed = previous != null && previous.read(item.address) != bytes[index];
        item.tip = (item.changed
                ? String.format("was $%02X · ", previous.read(item.address)) : "")
                + "Double-click to show in Memory";
    }

    private void populateVariables() {
        var expanded = tree.isExpanded(new TreePath(variables.getPath()));
        variables.removeAllChildren();
        if (program == null) {
            variables.add(leaf("Attach an ld65 .dbg file", "", false, -1, null));
        } else if (program.ramSymbols().isEmpty()) {
            variables.add(leaf("No RAM symbols", "", false, -1, null));
        } else {
            for (var symbol : program.ramSymbols()) {
                var address = symbol.value();
                variables.add(leaf(symbol.name(),
                        String.format("$%04X: --", address), false, address,
                        "First byte at this RAM label; size is not recorded in .dbg"));
            }
        }
        model.nodeStructureChanged(variables);
        if (expanded) {
            tree.expandPath(new TreePath(variables.getPath()));
        }
    }

    private void refreshVariables(final MachineSnapshot stopped, final MachineSnapshot previous) {
        for (var index = 0; index < variables.getChildCount(); index++) {
            var node = (DefaultMutableTreeNode) variables.getChildAt(index);
            var item = (Item) node.getUserObject();
            if (item.address < 0) {
                continue;
            }
            var address = item.address;
            var byteValue = stopped == null ? "--" : String.format("$%02X", stopped.read(address));
            item.value = String.format("$%04X: %s", address, byteValue);
            item.changed = stopped != null && previous != null
                    && stopped.read(address) != previous.read(address);
            var earlier = item.changed ? String.format("was $%02X · ", previous.read(address)) : "";
            item.tip = earlier + "First byte at this RAM label; size is not recorded in .dbg"
                    + " · Double-click to show in Memory";
            model.nodeChanged(node);
        }
    }

    private void addValue(final DefaultMutableTreeNode parent, final String name) {
        var node = leaf(name, "--", false, -1, null);
        parent.add(node);
        values.put(name, node);
    }

    private static DefaultMutableTreeNode group(final String name) {
        return new DefaultMutableTreeNode(new Item(name, "", false, -1, null));
    }

    private static DefaultMutableTreeNode leaf(
            final String name, final String value, final boolean changed,
            final int address, final String tip) {
        return new DefaultMutableTreeNode(new Item(name, value, changed, address, tip), false);
    }

    private static final class Item {
        final String name;
        String value;
        boolean changed;
        int address;
        String tip;

        Item(final String name, final String value, final boolean changed,
             final int address, final String tip) {
            this.name = name;
            this.value = value;
            this.changed = changed;
            this.address = address;
            this.tip = tip;
        }

        @Override
        public String toString() {
            return value.isEmpty() ? name : name + "   " + value;
        }
    }

    private final class Renderer extends DefaultTreeCellRenderer {
        Renderer() {
            setLeafIcon(null);
            setOpenIcon(null);
            setClosedIcon(null);
        }

        @Override
        public Component getTreeCellRendererComponent(
                final JTree tree, final Object value, final boolean selected,
                final boolean expanded, final boolean leaf, final int row, final boolean focused) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focused);
            var item = (Item) ((DefaultMutableTreeNode) value).getUserObject();
            setFont(leaf ? Theme.MONOSPACED : Theme.MONOSPACED.deriveFont(Font.BOLD));
            if (!selected) {
                setForeground(stale ? Theme.muted()
                        : item.changed ? Theme.changed() : Theme.foreground());
            }
            return this;
        }
    }
}
