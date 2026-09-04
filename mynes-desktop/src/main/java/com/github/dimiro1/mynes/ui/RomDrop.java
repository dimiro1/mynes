package com.github.dimiro1.mynes.ui;

import org.jetbrains.annotations.Nullable;

import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.InvalidDnDOperationException;
import java.io.File;
import java.io.IOException;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * A cartridge dragged out of a file manager and let go over the window.
 * <p>
 * Swing already has all of this: {@link DataFlavor#javaFileListFlavor} is what the JDK's own
 * translators hand every platform's file drop over as -- Finder's, Explorer's and X11's
 * {@code text/uri-list} alike -- so one flavour covers all three and there is nothing here to
 * depend on.
 * <p>
 * Nothing about the game is decided in here. What comes out is the file, handed to whatever was
 * given to the constructor, which is {@code GameUIFrame}'s own Open -- so a dropped cartridge takes
 * exactly the path the menu's does, error dialog and Open Recent entry included. A dropped zip is
 * unpacked there too, and asks there which cartridge it holds when it holds more than one.
 */
final class RomDrop extends TransferHandler {
    private static final Logger logger = System.getLogger("UI");

    /**
     * What a cartridge is called, and what the zip a collection ships one in is called. The same two
     * the Open dialog's filter takes, and deliberately so: the cursor has to say yes to exactly the
     * files the menu would open, or one of the two ways in is quietly narrower than the other.
     */
    private static final List<String> EXTENSIONS = List.of(".nes", ".zip");

    private final Consumer<File> open;

    RomDrop(final Consumer<File> open) {
        this.open = open;
    }

    /**
     * Whether the cursor says yes, which is the only answer anybody gets before letting go.
     * <p>
     * The files are looked at rather than only the flavour, because the cursor is the answer to
     * "will this work" and a drag of a text file that says yes and then does nothing is the worse
     * kind of wrong. Some sources will not give their data up until the drop, though --
     * hence the fall back to accepting on the flavour alone, and hence {@link #importData} asking
     * again rather than trusting this.
     */
    @Override
    public boolean canImport(final TransferSupport support) {
        if (!support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
            return false;
        }

        // A drop reported back as MOVE is a file manager being told it may take the file away, and
        // opening a cartridge does not move it. Only where the source offers a copy: refusing the
        // whole drop over the action would be a stranger answer than accepting the one it offered.
        if (support.isDrop() && (support.getSourceDropActions() & COPY) == COPY) {
            support.setDropAction(COPY);
        }

        var files = filesIn(support);

        return files == null || cartridgeIn(files) != null;
    }

    @Override
    public boolean importData(final TransferSupport support) {
        var files = filesIn(support);
        final var rom = files == null ? null : cartridgeIn(files);

        if (rom == null) {
            // Only reachable where canImport had to accept on the flavour alone, so the cursor has
            // already said yes and the drag icon flying back is the whole of what is seen. A log
            // line rather than a dialog: nothing has gone wrong, and the game is still playing.
            logger.log(Level.INFO, "nothing that looks like a cartridge was dropped");
            return false;
        }

        // Off the drop and back onto the queue, for two reasons. The file manager is waiting for
        // this method to return, and loading a cartridge stops one machine and builds another; and
        // a modal dialog put up from inside a drop -- which is what a file that will not load ends
        // in -- is a way to hang the drag itself.
        SwingUtilities.invokeLater(() -> open.accept(rom));

        return true;
    }

    /**
     * The one cartridge in a drop, or null when that is not what it was.
     * <p>
     * Exactly one file: a window plays one game, and a fistful of ROMs is a question about which of
     * them rather than an instruction. The name is the whole of the test, and nothing is opened to
     * check -- this runs on every drag-over event, and a file on a network share is one {@code stat}
     * away from a cursor that stutters. That is also why a zip is taken on its name here and by its
     * first four bytes once it is opened: the cursor cannot afford to read the file, and
     * {@code Archive} and {@link com.github.dimiro1.mynes.Cart#load} are the real judges either way.
     */
    static @Nullable File cartridgeIn(final List<File> files) {
        if (files.size() != 1) {
            return null;
        }

        var file = files.getFirst();
        var name = file.getName().toLowerCase(Locale.ROOT);

        return EXTENSIONS.stream().anyMatch(name::endsWith) ? file : null;
    }

    /**
     * The dropped files, or null where the drag would not give them up.
     * <p>
     * Null rather than an empty list, because the two mean opposite things: nothing was dropped is
     * a refusal, and nothing can be read yet is a question to ask again at the drop.
     * {@link InvalidDnDOperationException} is that second one -- some platforms only fetch the data
     * once the mouse is released.
     */
    private static @Nullable List<File> filesIn(final TransferSupport support) {
        try {
            @SuppressWarnings("unchecked")
            var files = (List<File>) support.getTransferable()
                    .getTransferData(DataFlavor.javaFileListFlavor);

            return files;
        } catch (UnsupportedFlavorException | IOException | InvalidDnDOperationException e) {
            return null;
        }
    }
}
