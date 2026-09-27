package com.github.dimiro1.mynes.ui.controlpanel;

import com.github.dimiro1.mynes.ui.debugger.Theme;
import com.github.dimiro1.mynes.ui.AppearanceAware;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Font;

/** A compact status line for facts about how the emulator is running. */
final class Dashboard extends JPanel implements AppearanceAware {
    private final JLabel running = new JLabel(" ");

    Dashboard() {
        super(new BorderLayout());
        refreshAppearance();
        running.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        add(running, BorderLayout.CENTER);
    }

    @Override
    public void refreshAppearance() {
        setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.dim()),
                BorderFactory.createEmptyBorder(4, 12, 4, 12)));
    }

    void setRunning(final String text) {
        running.setText(text);
    }

    void clear() {
        running.setText(" ");
    }
}
