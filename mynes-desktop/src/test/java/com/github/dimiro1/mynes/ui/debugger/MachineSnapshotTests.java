package com.github.dimiro1.mynes.ui.debugger;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.debug.Debugger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * The one read the debugger window makes of the machine.
 * <p>
 * Worth testing on its own because everything the window shows comes out of it, and because the two
 * things it gets right are both easy to get wrong quietly: reading through {@code peek} rather than
 * {@code read}, and taking cartridge RAM from the chip rather than off the bus.
 */
class MachineSnapshotTests {
    private NES nes;
    private Debugger debugger;

    @BeforeEach
    void setUp() {
        nes = new NES(Cart.load(rom(), "snapshot.nes"));
        debugger = new Debugger();
        debugger.attach(nes);
    }

    @Test
    void itCoversTheWholeAddressSpace() {
        assertEquals(0x10000, MachineSnapshot.of(nes, debugger).bus().length);
    }

    @Test
    void itReadsWhatIsInMemory() {
        nes.getMemory().write(0x0123, 0x5A);

        assertEquals(0x5A, MachineSnapshot.of(nes, debugger).read(0x0123));
    }

    /**
     * Filling a hex view through real reads would clear $2002's VBlank flag and clock the controller
     * ports, so the act of looking would change the game. Registers come back as zero instead, which
     * is what {@code peek} promises.
     */
    @Test
    void itDoesNotDisturbTheRegistersItPassesOver() {
        var snapshot = MachineSnapshot.of(nes, debugger);

        assertEquals(0, snapshot.read(0x2002));
        assertEquals(0, snapshot.read(0x4016));
    }

    /**
     * The one place the bus gives the wrong answer. MMC1 and MMC3 read back zero at $6000 when the
     * game has switched the chip off, and a hex view full of zeros over the save RAM would be the
     * first bug reported against the window.
     */
    @Test
    void cartridgeRamComesFromTheChipRatherThanOffTheBus() {
        var prgRAM = nes.getBus().getMapper().prgRAM();

        prgRAM[0] = (byte) 0xAB;
        prgRAM[1] = (byte) 0xCD;

        var snapshot = MachineSnapshot.of(nes, debugger);

        assertEquals(0xAB, snapshot.read(0x6000));
        assertEquals(0xCD, snapshot.read(0x6001));
    }

    @Test
    void theFlagsAreSpeltTheWayNestestSpellsThem() {
        // $24 is what the CPU powers up holding: the unused bit and the interrupt disable.
        assertEquals("nv-bdIzc", MachineSnapshot.of(nes, debugger).flags());
    }

    @Test
    void itCarriesTheTrailSoTheListingHasHistory() {
        debugger.addBreakpoint(0xFFFF);

        for (var i = 0; i < 4; i++) {
            var wasPC = nes.getCPU().getPC();

            nes.step();
            debugger.afterInstruction(nes.getCPU().getPC(), wasPC);
        }

        assertNotEquals(0, MachineSnapshot.of(nes, debugger).trail().length);
    }

    /**
     * NROM with a spin at the reset vector, which is all this needs: nothing here runs the machine
     * far enough to care what it does.
     */
    private static byte[] rom() {
        var image = new byte[16 + 0x4000 + 0x2000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;
        image[5] = 1;

        image[16] = 0x4C;          // JMP $8000
        image[17] = 0x00;
        image[18] = (byte) 0x80;

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0x80;

        return image;
    }
}
