package com.github.dimiro1.mynes.ui;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for dragging a cartridge onto the window.
 * <p>
 * No window anywhere and no display needed: what a drop decides is which of the dropped files is a
 * game, and a {@link TransferHandler.TransferSupport} can be built around a bare panel and a
 * {@link Transferable} of this test's own. The part that cannot be tested without a mouse -- that
 * the platform hands a file drop over as {@link DataFlavor#javaFileListFlavor} -- is the JDK's job
 * rather than this class's.
 */
class RomDropTests {
    @Test
    void oneCartridgeIsTheGame() {
        var rom = new File("/roms/smb.nes");

        assertSame(rom, RomDrop.cartridgeIn(List.of(rom)));
    }

    /**
     * A drag out of a file manager carries whatever the file system spelled it, and a ROM ripped in
     * 1998 is as likely to shout as not.
     */
    @Test
    void theExtensionIsReadWithoutRegardToCase() {
        var rom = new File("/roms/SMB.NES");

        assertSame(rom, RomDrop.cartridgeIn(List.of(rom)));
    }

    /**
     * The two the Open dialog's filter takes, since the cursor has to say yes to exactly the files
     * the menu would open. What is inside the zip is nobody's business here -- the drag is answered
     * on the name, and the archive is not read until the window opens it.
     */
    @Test
    void theZipACollectionShipsOneInIsACartridgeToo() {
        var zip = new File("/roms/smb.zip");
        var shouted = new File("/roms/SMB.ZIP");

        assertSame(zip, RomDrop.cartridgeIn(List.of(zip)));
        assertSame(shouted, RomDrop.cartridgeIn(List.of(shouted)));
    }

    @Test
    void anythingElseIsNotACartridge() {
        assertNull(RomDrop.cartridgeIn(List.of(new File("/roms/hack.ips"))));
        assertNull(RomDrop.cartridgeIn(List.of(new File("/roms/readme.txt"))));

        // The extension rather than the name having it in the middle somewhere.
        assertNull(RomDrop.cartridgeIn(List.of(new File("/roms/smb.nes.txt"))));
        assertNull(RomDrop.cartridgeIn(List.of(new File("/roms/smb.zip.txt"))));
    }

    /**
     * A window plays one game, so a fistful of ROMs is a question about which of them rather than
     * an instruction -- and picking the first would be answering it on somebody's behalf.
     */
    @Test
    void aHandfulOfCartridgesIsNotOne() {
        assertNull(RomDrop.cartridgeIn(
                List.of(new File("/roms/smb.nes"), new File("/roms/tetris.nes"))));
    }

    @Test
    void anEmptyDropIsNotOneEither() {
        assertNull(RomDrop.cartridgeIn(List.of()));
    }

    @Test
    void theCursorSaysYesToACartridge() {
        assertTrue(new RomDrop(rom -> { })
                .canImport(support(new File("/roms/smb.nes"))));
    }

    @Test
    void theCursorSaysNoToAnythingElse() {
        var drop = new RomDrop(rom -> { });

        assertFalse(drop.canImport(support(new File("/roms/hack.ips"))));

        // Nothing dragged out of a file manager at all: a run of text, which is a flavour this
        // never asks the transferable for.
        assertFalse(drop.canImport(new TransferHandler.TransferSupport(
                new JPanel(), new StringTransferable("/roms/smb.nes"))));
    }

    /**
     * And hands it over as it was dropped, which is what keeps a dropped game on the same path as
     * one opened from the menu.
     */
    @Test
    void aDroppedCartridgeIsOpened() throws Exception {
        var opened = new AtomicReference<File>();
        var rom = new File("/roms/smb.nes");

        assertTrue(new RomDrop(opened::set).importData(support(rom)));

        flush();

        assertSame(rom, opened.get());
    }

    /**
     * Refused rather than opened, and the game that was already playing is left alone. Reachable
     * where the platform would not give the file list up during the drag, so the cursor had said
     * yes on the flavour alone.
     */
    @Test
    void aDropOfSomethingElseOpensNothing() throws Exception {
        var opened = new AtomicReference<File>();

        assertFalse(new RomDrop(opened::set).importData(support(new File("/roms/hack.ips"))));

        flush();

        assertNull(opened.get());
    }

    /**
     * The window is handed the cartridge from the event queue rather than from inside the drop, so
     * nothing has happened by the time {@code importData} returns.
     */
    @Test
    void theGameIsNotLoadedInsideTheDrop() {
        var opened = new AtomicReference<File>();

        new RomDrop(opened::set).importData(support(new File("/roms/smb.nes")));

        assertNull(opened.get());
    }

    private static TransferHandler.TransferSupport support(final File... files) {
        return new TransferHandler.TransferSupport(new JPanel(), new FileTransferable(files));
    }

    /**
     * Waits for whatever {@code importData} posted, by queueing something behind it.
     */
    private static void flush() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    private record FileTransferable(File... files) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.javaFileListFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            return DataFlavor.javaFileListFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }

            return List.of(files);
        }
    }

    private record StringTransferable(String text) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.stringFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            return DataFlavor.stringFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }

            return text;
        }
    }
}
