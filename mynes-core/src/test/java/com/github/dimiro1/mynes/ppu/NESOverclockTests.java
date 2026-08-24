package com.github.dimiro1.mynes.ppu;

import com.github.dimiro1.mynes.APU;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Overclock;
import com.github.dimiro1.mynes.Region;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole machine on a longer frame: who gets the extra cycles, and who does not.
 * <p>
 * The CPU does, which is the entire point -- a program with more cycles between one NMI and the next
 * is a program that finishes its work. The APU does not, and that is the part worth a test rather
 * than a comment: everything it counts is counted in CPU cycles, so an APU that ran through the
 * extra lines would play the music slow and hand a front end a frame and a half of samples to fit
 * into a frame.
 *
 * @see Overclock
 */
class NESOverclockTests {
    private static final int NOP = 0xEA;

    /**
     * How long the four step sequence is, in CPU cycles, which is what "the frame counter's own
     * time" means below.
     */
    private static final int FOUR_STEP_PERIOD = 29830;

    /**
     * Bit 6 of $4015: the frame counter has been round once more.
     */
    private static final int FRAME_IRQ = 0x40;

    @Test
    void theCpuGetsTheLinesAsCycles() {
        // 30 scanlines is 10230 dots, which is 3410 CPU cycles at three dots each -- so a frame
        // becomes 33190.67 rather than 29780.67, and two of them twice that.
        var nes = nesRunning(NOP, NOP, NOP, NOP);
        nes.getPPU().setOverclock(new Overclock(30, 0));

        var cycles = cyclesOverFrames(nes, 2);

        assertEquals(2 * (89342 + 30 * 341) / 3.0, cycles, 2,
                "two overclocked frames of CPU cycles");
    }

    @Test
    void bothHalvesReachTheCpu() {
        var nes = nesRunning(NOP, NOP, NOP, NOP);
        nes.getPPU().setOverclock(new Overclock(10, 20));

        assertEquals((89342 + 30 * 341) / 3.0, cyclesOverFrames(nes, 1), 2);
    }

    @Test
    void aFrameStillMakesTheSameNumberOfSamples() {
        // The claim a front end depends on: the desktop paces itself on a blocking write to the
        // sound card, so a frame that handed over half as many samples again would run the game at
        // two thirds speed however fast the machine underneath it was going.
        var plain = samplesOverFrames(machine(Overclock.NONE), 40);
        var overclocked = samplesOverFrames(machine(new Overclock(131, 0)), 40);

        assertEquals(plain, overclocked, 1,
                "an overclocked frame is a hardware frame's worth of sound");
        assertEquals(40 * APU.SAMPLE_RATE / 60.0988, plain, 2, "which is about 734 a frame");
    }

    @Test
    void apuCyclesStillEqualCpuCycles() {
        // Not an accounting detail: the parity of the APU's counter is what CPUBus.isGetCycle reads,
        // and the MMU asks the same question of the CPU's when it starts a sprite DMA. A counter
        // that stood still through the extra lines would come back inverted and a DMA would take
        // 513 cycles where the hardware takes 514.
        var nes = machine(new Overclock(77, 33));

        cyclesOverFrames(nes, 5);

        assertEquals(nes.getCPU().getState().cycles(), nes.getAPU().getCycles());
    }

    @Test
    void theFrameCounterRunsInHardwareTime() {
        // 60 frames is 1786840 CPU cycles of hardware time, which is 59.9 times round the four step
        // sequence. At +50% the machine spends 2680260 cycles in those frames -- an APU that ran
        // through them would go round 89 times, and every envelope, sweep and length counter with
        // it, which is music at two thirds tempo.
        var nes = machine(new Overclock(131, 0));

        var sequences = sequencesOverFrames(nes, 60);
        var hardware = 60 * (89342 / 3.0) / FOUR_STEP_PERIOD;

        assertEquals(hardware, sequences, 1.0,
                "the frame counter went round " + sequences + " times in 60 overclocked frames");
    }

    @Test
    void aMachineNobodyOverclockedIsUntouched() {
        var nes = machine(Overclock.NONE);

        assertEquals(89342 / 3.0, cyclesOverFrames(nes, 1), 2);
        assertEquals(60 * (89342 / 3.0) / FOUR_STEP_PERIOD, sequencesOverFrames(machine(
                Overclock.NONE), 60), 1.0);
    }

    // ================================================================================== internals

    private NES machine(final Overclock overclock) {
        var nes = nesRunning(NOP, NOP, NOP, NOP);
        nes.getPPU().setOverclock(overclock);

        return nes;
    }

    /**
     * How many CPU cycles {@code frames} whole frames take, measured from one frame boundary to
     * another so that the reset sequence's own few cycles are not in it.
     */
    private long cyclesOverFrames(final NES nes, final int frames) {
        var ppu = nes.getPPU();

        advanceFrame(nes);

        var started = nes.getCPU().getState().cycles();
        var target = ppu.getFrame() + frames;

        while (ppu.getFrame() < target) {
            nes.tick();
        }

        return nes.getCPU().getState().cycles() - started;
    }

    /**
     * How many finished samples come out over {@code frames} whole frames. Drained as it goes: the
     * chip's ring holds 8192 and forty frames make about thirty thousand.
     */
    private long samplesOverFrames(final NES nes, final int frames) {
        var ppu = nes.getPPU();
        var apu = nes.getAPU();
        var buffer = new short[4096];

        advanceFrame(nes);
        apu.drainSamples(buffer);

        var target = ppu.getFrame() + frames;
        var samples = 0L;

        while (ppu.getFrame() < target) {
            nes.tick();
            samples += apu.drainSamples(buffer);
        }

        return samples + apu.drainSamples(buffer);
    }

    /**
     * How many times the four step sequence comes round over {@code frames} whole frames.
     * <p>
     * Counted through $4015, which is the only window onto the frame counter a program has. The flag
     * is raised on three consecutive cycles and a read only <em>asks</em> for it to be cleared, so a
     * sighting is followed by a few cycles of reading before the next one is looked for -- otherwise
     * one lap would be counted three times.
     */
    private int sequencesOverFrames(final NES nes, final int frames) {
        var ppu = nes.getPPU();
        var apu = nes.getAPU();

        advanceFrame(nes);

        var target = ppu.getFrame() + frames;
        var sequences = 0;

        while (ppu.getFrame() < target) {
            nes.tick();

            if ((apu.readStatus() & FRAME_IRQ) == 0) {
                continue;
            }

            sequences++;

            for (var i = 0; i < 8; i++) {
                nes.tick();
                apu.readStatus();
            }
        }

        assertTrue(sequences > 0, "the frame counter never came round at all");

        return sequences;
    }

    private void advanceFrame(final NES nes) {
        var ppu = nes.getPPU();
        var frame = ppu.getFrame();

        while (ppu.getFrame() == frame) {
            nes.tick();
        }
    }

    /**
     * Builds a machine sitting at the first instruction of {@code code}, past the reset sequence.
     * The same helper {@code NESIntegrationTests} uses, copied rather than shared: it is four lines
     * of iNES header and neither test wants the other's changes to it.
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

        var nes = new NES(Cart.load(image, "overclock.nes"), Region.NTSC);
        nes.step();  // the reset sequence

        return nes;
    }
}
