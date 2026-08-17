package com.github.dimiro1.mynes;

import com.github.dimiro1.mynes.mappers.IRQHandler;
import com.github.dimiro1.mynes.mappers.Mapper;
import com.github.dimiro1.mynes.mappers.Mapper0;
import com.github.dimiro1.mynes.mappers.Mirroring;
import com.github.dimiro1.mynes.state.StateIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three devices that share the /IRQ line.
 * <p>
 * /IRQ is one open collector wire with the cartridge, the APU's frame counter and its DMC all able
 * to pull it low, and the CPU sees only the level. So the bus has to remember who is holding it:
 * releasing one source while another still wants the interrupt must leave the line low. That is
 * the whole of what these tests are about.
 */
class BUSIRQTests {
    private BUS bus;
    private CPU cpu;

    @BeforeEach
    void setUp() {
        var mapper = new Mapper0(new byte[0x4000], new byte[0x2000], Mirroring.HORIZONTAL);
        bus = new BUS(mapper, null, null);
        bus.initialize();
        cpu = bus.getCPU();
    }

    @Test
    void aFreshBusHoldsTheLineReleased() {
        assertFalse(cpu.isIRQLineAsserted());
    }

    @Test
    void anySingleSourcePullsTheLineLow() {
        bus.setMapperIRQ(true);
        assertTrue(cpu.isIRQLineAsserted(), "the cartridge");
        bus.setMapperIRQ(false);

        bus.setAPUFrameIRQ(true);
        assertTrue(cpu.isIRQLineAsserted(), "the frame counter");
        bus.setAPUFrameIRQ(false);

        bus.setDMCIRQ(true);
        assertTrue(cpu.isIRQLineAsserted(), "the DMC");
        bus.setDMCIRQ(false);

        assertFalse(cpu.isIRQLineAsserted(), "and the line comes back up when they all let go");
    }

    @Test
    void oneSourceLettingGoDoesNotReleaseTheLineForTheOthers() {
        bus.setMapperIRQ(true);
        bus.setAPUFrameIRQ(true);
        bus.setDMCIRQ(true);

        bus.setMapperIRQ(false);
        assertTrue(cpu.isIRQLineAsserted(), "the frame counter and the DMC are still holding it");

        bus.setAPUFrameIRQ(false);
        assertTrue(cpu.isIRQLineAsserted(), "the DMC is still holding it");

        bus.setDMCIRQ(false);
        assertFalse(cpu.isIRQLineAsserted(), "the last one to let go releases the line");
    }

    @Test
    void assertingTwiceIsNotTwoRequests() {
        bus.setMapperIRQ(true);
        bus.setMapperIRQ(true);
        bus.setMapperIRQ(false);

        assertFalse(cpu.isIRQLineAsserted(), "the line is a level, not a count");
    }

    /**
     * What {@link BUS#initialize()} hands the cartridge has to be the mapper's own end of the wire
     * and nobody else's, or an MMC3 acknowledging its scanline interrupt would take an APU one
     * down with it.
     */
    @Test
    void theHandlerGivenToTheCartridgeDrivesOnlyTheCartridgeSource() {
        var mapper = new CapturingMapper();
        var wired = new BUS(mapper, null, null);
        wired.initialize();

        wired.setAPUFrameIRQ(true);
        mapper.handler.setIRQLine(true);
        mapper.handler.setIRQLine(false);

        assertTrue(wired.getCPU().isIRQLineAsserted(),
                "the cartridge letting go must not answer for the frame counter");
    }

    /**
     * A cartridge with interrupt hardware, as far as {@link BUS#initialize()} can tell: it keeps
     * the handler it is given so the test can pull the line the way a mapper would.
     */
    private static final class CapturingMapper implements Mapper {
        private IRQHandler handler;

        @Override
        public void setIRQHandler(final IRQHandler handler) {
            this.handler = handler;
        }

        @Override
        public int prgRead(final int address) {
            return 0;
        }

        @Override
        public void prgWrite(final int address, final int data) {
        }

        @Override
        public int charRead(final int address) {
            return 0;
        }

        @Override
        public void charWrite(final int address, final int data) {
        }

        @Override
        public Mirroring mirroring() {
            return Mirroring.HORIZONTAL;
        }

        @Override
        public void serialize(final StateIO io) {
            // A board with no memory and no registers, which is the one case where this is empty.
        }
    }
}
