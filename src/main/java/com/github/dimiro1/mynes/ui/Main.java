package com.github.dimiro1.mynes.ui;

import com.formdev.flatlaf.FlatLightLaf;
import com.github.dimiro1.mynes.headless.Headless;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.*;
import java.util.ArrayList;
import java.util.Arrays;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);

    /**
     * The one way in, for both of the things this can be.
     * <p>
     * No arguments opens the window, which is what double clicking the jar and what
     * {@code mvn compile exec:exec} both do. {@code --headless} runs a cartridge with nobody
     * watching -- see {@link com.github.dimiro1.mynes.headless}.
     */
    public static void main(final String[] args) {
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

        logger.info("MyNES");

        SwingUtilities.invokeLater(() -> {
            var frame = new GameUIFrame();
            frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
            frame.setVisible(true);
        });
    }
}
