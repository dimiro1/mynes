package com.github.dimiro1.mynes.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.github.dimiro1.mynes.headless.Headless;

import javax.swing.*;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Arrays;

public class Main {
    /**
     * One line per message, rather than the two that {@code java.util.logging} writes by default.
     * <p>
     * {@link System.Logger} has no formatting of its own: with nothing else on the class path it
     * hands everything to {@code java.util.logging}, whose {@code SimpleFormatter} prints a
     * timestamp and the calling method on one line and the message on the next. That is a fine
     * shape for a server's log file and the wrong one for a program whose output somebody is
     * reading as it runs, and for a headless run whose last line is the one that matters.
     * <p>
     * Read once, when the formatter is constructed, so this has to be set before anything logs.
     */
    private static final String CONSOLE_FORMAT = "%4$s %3$s - %5$s%6$s%n";

    // Above the logger, and not in main(), because both of those are load bearing: static
    // initialisers run in the order they are written, and all of them run before main() is called.
    // The first logger anybody asks for is what builds the formatter that reads this.
    static {
        System.setProperty("java.util.logging.SimpleFormatter.format", CONSOLE_FORMAT);
    }

    private static final Logger logger = System.getLogger(Main.class.getName());

    /**
     * The one way in, for both of the things this can be.
     * <p>
     * No arguments opens the window, which is what double clicking the jar and what
     * {@code mvn compile exec:exec} both do. {@code --headless} runs a cartridge with nobody
     * watching -- see {@link com.github.dimiro1.mynes.headless}.
     */
    static void main(final String[] args) {
        // Taken from wherever it appears rather than only from the front. It is a mode rather than
        // an option, so it usually leads, but a command line assembled a piece at a time is exactly
        // the kind that puts it last -- and being told that --headless is not an option would be a
        // baffling thing to read.
        var mode = Arrays.asList(args).indexOf("--headless");

        if (mode >= 0) {
            // Before anything at all has touched AWT. The picture is drawn into a BufferedImage,
            // which needs no display, but a Toolkit that has already decided there is one cannot be
            // talked round afterwards -- and on macOS it puts an icon in the Dock on its way to
            // deciding.
            System.setProperty("java.awt.headless", "true");

            var rest = new ArrayList<>(Arrays.asList(args));
            rest.remove(mode);

            System.exit(Headless.run(rest.toArray(String[]::new)));
        }

        if (args.length > 0) {
            System.err.println("""
                    MyNES takes no arguments and opens its window, or --headless and runs a
                    cartridge without one.

                      --headless --help   what the headless mode can be asked for
                    """);

            System.exit(2);
        }

        // Inside the branch that needs it: the look and feel loads a native library and wakes the
        // whole toolkit up, neither of which a headless run has any use for.
        FlatLightLaf.setup();

        logger.log(Level.INFO, "MyNES");

        SwingUtilities.invokeLater(() -> {
            var frame = new GameUIFrame();
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });
    }
}
