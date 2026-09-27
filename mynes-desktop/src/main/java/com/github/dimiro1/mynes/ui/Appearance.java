package com.github.dimiro1.mynes.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.lang.System.Logger.Level;

/** The window's saved look, independent of the console's video palette. */
public enum Appearance {
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    private final String id;
    private final String label;

    Appearance(final String id, final String label) {
        this.id = id;
        this.label = label;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public static Appearance byId(final String id) {
        for (var appearance : values()) {
            if (appearance.id.equalsIgnoreCase(id.trim())) {
                return appearance;
            }
        }
        System.getLogger("UI").log(Level.WARNING,
                id + " is not a window theme, falling back to light");
        return LIGHT;
    }

    /** Called before making any Swing components, or on the event dispatch thread when changing. */
    public void install() {
        if (this == DARK) {
            FlatDarkLaf.setup();
        } else {
            FlatLightLaf.setup();
        }
    }

    /** Refresh all open windows, including custom colours FlatLaf does not own. */
    public void refreshWindows() {
        FlatLaf.updateUI();
        for (var window : Window.getWindows()) {
            refresh(window);
        }
    }

    private static void refresh(final Component component) {
        if (component instanceof AppearanceAware aware) {
            aware.refreshAppearance();
        }
        if (component instanceof Container container) {
            for (var child : container.getComponents()) {
                refresh(child);
            }
        }
    }
}
