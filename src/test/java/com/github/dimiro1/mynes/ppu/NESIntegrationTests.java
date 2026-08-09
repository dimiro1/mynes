package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The CPU and the PPU running together.
 * <p>
 * Three PPU dots per CPU cycle, no matter what the CPU is doing -- and in particular the PPU has
 * to keep running through an OAM DMA transfer, which holds the CPU off the bus for over five
 * hundred cycles. Stepping by instruction rather than by cycle must not change that either.
 */
class NESIntegrationTests {
    private static final int NOP = 0xEA;

    @Test
    void everyCpuCycleIsThreePpuDots() {
        var nes = nesRunning(NOP, NOP, NOP, NOP, NOP, NOP, NOP, NOP);
        var cpu = nes.getCPU();
        var ppu = nes.getPPU();

        var startCycles = cpu.getState().cycles();
        var startDots = dotsSincePowerOn(ppu);

        for (var i = 0; i < 50; i++) {
            nes.tick();
        }

        assertEquals(50, cpu.getState().cycles() - startCycles);
        assertEquals(150, dotsSincePowerOn(ppu) - startDots);
    }

    @Test
    void steppingAnInstructionAdvancesThePpuByThreeDotsPerCycle() {
        var nes = nesRunning(NOP, NOP, NOP, NOP);
        var cpu = nes.getCPU();
        var ppu = nes.getPPU();

        for (var i = 0; i < 4; i++) {
            var beforeCycles = cpu.getState().cycles();
            var beforeDots = dotsSincePowerOn(ppu);

            nes.step();

            var cycles = cpu.getState().cycles() - beforeCycles;
            assertEquals(2, cycles, "a NOP is two cycles");
            assertEquals(3 * cycles, dotsSincePowerOn(ppu) - beforeDots);
        }
    }

    @Test
    void thePpuKeepsRunningThroughAnOamDmaStall() {
        // LDA #$02 / STA $4014 / NOP
        var nes = nesRunning(0xA9, 0x02, 0x8D, 0x14, 0x40, NOP);
        var cpu = nes.getCPU();
        var ppu = nes.getPPU();

        nes.step();  // LDA
        nes.step();  // STA $4014, which arms the transfer

        assertTrue(nes.getBus().isDMAInProgress());

        var beforeCycles = cpu.getState().cycles();
        var beforeDots = dotsSincePowerOn(ppu);

        nes.step();  // the stall, then the NOP

        var cycles = cpu.getState().cycles() - beforeCycles;
        assertTrue(cycles > 500, "the transfer should have held the CPU for over five hundred cycles");
        assertEquals(
                3 * cycles, dotsSincePowerOn(ppu) - beforeDots,
                "the PPU does not stall with the CPU"
        );
    }

    /**
     * @return how many dots the PPU has run since power on, which is the only way to compare its
     * progress across a frame boundary.
     */
    private long dotsSincePowerOn(final com.github.dimiro1.mynes.PPU ppu) {
        // Frames are not all the same length once rendering is on, but nothing here enables it.
        return ppu.getFrame() * 341L * 262L + ppu.getScanline() * 341L + ppu.getDot();
    }

    /**
     * Builds a machine sitting at the first instruction of {@code code}, past the reset sequence.
     */
    private NES nesRunning(final int... code) {
        var image = new byte[16 + 0x4000];

        image[0] = 'N';
        image[1] = 'E';
        image[2] = 'S';
        image[3] = 0x1A;
        image[4] = 1;  // one PRG bank, mirrored into both $8000 and $C000

        for (var i = 0; i < code.length; i++) {
            image[16 + i] = (byte) code[i];
        }

        image[16 + 0x3FFC] = 0x00;
        image[16 + 0x3FFD] = (byte) 0xC0;

        var nes = new NES(Cart.load(image, "integration.nes"));
        nes.step();  // the reset sequence

        return nes;
    }
}
