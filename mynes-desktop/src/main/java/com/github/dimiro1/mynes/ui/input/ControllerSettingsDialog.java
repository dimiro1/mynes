package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.ui.debugger.Theme;
import com.github.dimiro1.mynes.ui.input.KeyBindings.Button;
import com.github.dimiro1.mynes.ui.input.KeyBindings.Port;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Settings &gt; Controller...: a row per button and a column per pad, click one and press the key
 * you want on it.
 * <p>
 * There is no Save or Cancel. Every capture takes effect the moment it happens -- the frame gets
 * told through {@code onChange}, and saves -- which is what makes trying a key out against the
 * running game a matter of pressing it rather than closing a dialog first.
 * <p>
 * A key already in use is taken from whoever had it, leaving that cell showing nothing. Refusing
 * the capture instead would make swapping two buttons impossible -- and the two pads are one grid
 * rather than two dialogs so that the theft is somewhere anybody can see it happen.
 * <p>
 * Player two arrives empty, which {@link KeyBindings#defaults()} explains, and Reset to Defaults is
 * how a keyboard given away to it comes back in one click. That is the whole of the unbinding
 * story: a per-cell clear would be sixteen more buttons for something one already does.
 */
public class ControllerSettingsDialog extends JDialog {
    private static final String UNBOUND_TEXT = "—";
    private static final String CAPTURING_TEXT = "Press a key...";

    private final Consumer<KeyBindings> onChange;
    private final Map<Port, Map<Button, JButton>> cells = new EnumMap<>(Port.class);

    private KeyBindings bindings;

    /**
     * The dispatcher that swallows the keyboard while a key is being captured, or null when no
     * cell is waiting for one.
     */
    private @Nullable KeyEventDispatcher capture;

    public ControllerSettingsDialog(
            final Frame owner,
            final KeyBindings bindings,
            final Consumer<KeyBindings> onChange) {
        super(owner, "Controller", true);

        this.bindings = bindings;
        this.onChange = onChange;

        init();
    }

    private void init() {
        setResizable(false);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setLayout(new MigLayout());

        for (var port : Port.values()) {
            cells.put(port, new EnumMap<>(Button.class));
        }

        // The corner above the button names, which is empty because the column under it holds
        // them.
        add(new JLabel());
        add(Theme.heading(Port.ONE.label()));
        add(Theme.heading(Port.TWO.label()), "wrap");

        for (var button : Button.values()) {
            add(new JLabel(button.label()));

            for (var port : Port.values()) {
                var cell = new JButton();
                cell.addActionListener(e -> startCapture(port, button));
                cells.get(port).put(button, cell);

                // A fixed width, so that a cell going from Enter to a dash does not move the dialog
                // around underneath the pointer.
                add(cell, port == Port.TWO ? "width 160!, wrap" : "width 160!");
            }
        }

        add(Theme.note("A key presses one button on one pad. Giving it to another takes it off"
                + " whatever had it."), "span 3, gaptop 4, wrap");

        var reset = new JButton("Reset to Defaults");
        reset.addActionListener(e -> apply(KeyBindings.defaults()));

        var close = new JButton("Close");
        close.addActionListener(e -> dispose());

        add(reset, "span 3, split 2, growx");
        add(close, "growx, wrap");

        // A capture left running would keep eating the application's key events after the dialog
        // has gone.
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(final WindowEvent e) {
                stopCapture();
            }
        });

        refresh();
        pack();
        setLocationRelativeTo(getOwner());
    }

    /**
     * Waits for the next key and puts {@code button} on {@code port} on it.
     * <p>
     * The wait is another {@link KeyEventDispatcher}, so every key event in the application
     * belongs to this dialog until it ends. Without that, capturing Space or Enter would press the
     * cell that is being edited, and capturing a menu shortcut would open a menu.
     */
    private void startCapture(final Port port, final Button button) {
        stopCapture();

        cells.get(port).get(button).setText(CAPTURING_TEXT);

        capture = e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                var code = e.getKeyCode();

                stopCapture();

                // Escape backs out, and so does a key this toolkit cannot name, which is the one
                // kind of key the config file could not write down afterwards.
                if (code != KeyEvent.VK_ESCAPE && code != KeyBindings.UNBOUND) {
                    apply(bindings.with(port, button, code));
                }
            }

            return true;
        };

        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(capture);
    }

    private void stopCapture() {
        if (capture == null) {
            return;
        }

        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(capture);
        capture = null;

        // Puts back whatever the cell said before it started asking for a key.
        refresh();
    }

    /**
     * Takes a capture. Writing the file is the frame's job: it owns the config, of which the
     * bindings are one section, and a second writer here would drop the rest of it.
     */
    private void apply(final KeyBindings updated) {
        bindings = updated;

        refresh();
        onChange.accept(updated);
    }

    private void refresh() {
        for (var port : cells.entrySet()) {
            for (var cell : port.getValue().entrySet()) {
                var code = bindings.keyFor(port.getKey(), cell.getKey());
                cell.getValue().setText(
                        code == KeyBindings.UNBOUND ? UNBOUND_TEXT : KeyEvent.getKeyText(code));
            }
        }
    }
}
