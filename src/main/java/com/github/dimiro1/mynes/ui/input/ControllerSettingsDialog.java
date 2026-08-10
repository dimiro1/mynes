package com.github.dimiro1.mynes.ui.input;

import com.github.dimiro1.mynes.ui.input.KeyBindings.Button;
import net.miginfocom.swing.MigLayout;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Settings &gt; Controller...: a row per button, click one and press the key you want on it.
 * <p>
 * There is no Save or Cancel. Every capture takes effect the moment it happens -- the frame gets
 * told through {@code onChange} and the file is rewritten -- which is what makes trying a key out
 * against the running game a matter of pressing it rather than closing a dialog first.
 * <p>
 * A key already in use is taken from whoever had it, leaving that row showing nothing. Refusing
 * the capture instead would make swapping two buttons impossible.
 */
public class ControllerSettingsDialog extends JDialog {
    private static final Logger logger = LoggerFactory.getLogger("INPUT");

    private static final String UNBOUND_TEXT = "—";
    private static final String CAPTURING_TEXT = "Press a key...";

    private final Consumer<KeyBindings> onChange;
    private final Map<Button, JButton> rows = new EnumMap<>(Button.class);

    private KeyBindings bindings;

    /**
     * The dispatcher that swallows the keyboard while a key is being captured, or null when no
     * row is waiting for one.
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

        for (var button : Button.values()) {
            var row = new JButton();
            row.addActionListener(e -> startCapture(button));
            rows.put(button, row);

            add(new JLabel(button.label()));
            // A fixed width, so that a row going from Enter to a dash does not move the dialog
            // around underneath the pointer.
            add(row, "width 160!, wrap");
        }

        var reset = new JButton("Reset to Defaults");
        reset.addActionListener(e -> apply(KeyBindings.defaults()));

        var close = new JButton("Close");
        close.addActionListener(e -> dispose());

        add(reset, "span 2, split 2, growx");
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
     * Waits for the next key and puts {@code button} on it.
     * <p>
     * The wait is another {@link KeyEventDispatcher}, so every key event in the application
     * belongs to this dialog until it ends. Without that, capturing Space or Enter would press the
     * row that is being edited, and capturing a menu shortcut would open a menu.
     */
    private void startCapture(final Button button) {
        stopCapture();

        rows.get(button).setText(CAPTURING_TEXT);

        capture = e -> {
            if (e.getID() == KeyEvent.KEY_PRESSED) {
                var code = e.getKeyCode();

                stopCapture();

                // Escape backs out, and so does a key this toolkit cannot name, which is the one
                // kind of key the config file could not write down afterwards.
                if (code != KeyEvent.VK_ESCAPE && code != KeyBindings.UNBOUND) {
                    apply(bindings.with(button, code));
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

        // Puts back whatever the row said before it started asking for a key.
        refresh();
    }

    private void apply(final KeyBindings updated) {
        bindings = updated;

        refresh();
        onChange.accept(updated);
        save();
    }

    private void save() {
        try {
            bindings.save(KeyBindings.DEFAULT_PATH);
        } catch (IOException e) {
            logger.error("failed to save controller bindings", e);
            JOptionPane.showMessageDialog(
                    this,
                    "Could not save the controller settings to " + KeyBindings.DEFAULT_PATH,
                    "Error",
                    JOptionPane.ERROR_MESSAGE
            );
        }
    }

    private void refresh() {
        for (var row : rows.entrySet()) {
            var code = bindings.keyFor(row.getKey());
            row.getValue().setText(
                    code == KeyBindings.UNBOUND ? UNBOUND_TEXT : KeyEvent.getKeyText(code));
        }
    }
}
