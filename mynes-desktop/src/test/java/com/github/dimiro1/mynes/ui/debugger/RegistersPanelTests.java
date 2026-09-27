package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.ui.Views;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RegistersPanelTests {
    @Test
    void registerValuesStartInTheSameColumnAcrossGroups() {
        var tree = Views.find(new RegistersPanel(address -> {}), JTree.class);
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();

        for (var groupIndex = 0; groupIndex < 4; groupIndex++) {
            var group = (DefaultMutableTreeNode) root.getChildAt(groupIndex);
            for (var index = 0; index < group.getChildCount(); index++) {
                var node = (DefaultMutableTreeNode) group.getChildAt(index);
                assertEquals(14, renderedText(tree, node).indexOf("--"));
            }
        }
    }

    @Test
    void variableValuesAlignToTheLongestSymbolName() {
        var panel = new RegistersPanel(address -> {});
        var program = new SourceProgram(Path.of("game.dbg"), List.of(), Map.of(), List.of(),
                List.of(
                        new SourceProgram.Symbol("x", "lab", 0x20, null, null, List.of()),
                        new SourceProgram.Symbol("long_name", "lab", 0x21, null, null, List.of())),
                0x4000, List.of());
        panel.setSourceProgram(program);

        var tree = Views.find(panel, JTree.class);
        var root = (DefaultMutableTreeNode) tree.getModel().getRoot();
        var variables = (DefaultMutableTreeNode) root.getChildAt(5);

        for (var index = 0; index < variables.getChildCount(); index++) {
            var node = (DefaultMutableTreeNode) variables.getChildAt(index);
            assertEquals(11, renderedText(tree, node).indexOf("$00"));
        }
    }

    private static String renderedText(final JTree tree, final DefaultMutableTreeNode node) {
        var renderer = tree.getCellRenderer();
        var label = (JLabel) renderer.getTreeCellRendererComponent(
                tree, node, false, false, node.isLeaf(), 0, false);
        return label.getText();
    }
}
