package com.github.dimiro1.mynes.blargg;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;

/**
 * blargg's APU test ROMs, which are about everything of the audio unit the CPU can see.
 * <p>
 * They report through the $6000 protocol; {@link BlarggRunner} explains it and does the driving.
 * What they cannot check is what it sounds like -- no ROM can -- so what these pin down is the
 * counters and the timing behind the sound: the length counters and their table, the frame
 * counter's interrupt and the exact cycle it arrives on, the clock jitter that comes of the APU
 * running at half the CPU's rate, and the DMC's sample handling and its sixteen rates.
 * <p>
 * The three timing ones are the reason every number in {@code APU.FrameCounter} is a named
 * constant. {@code 4-jitter} arbitrates the three-or-four cycle delay on a $4017 write,
 * {@code 5-len_timing} the cycles the four steps land on, and {@code 6-irq_flag_timing} the three
 * consecutive cycles the interrupt flag is set on at the end of a sequence.
 *
 * @see <a href="https://github.com/christopherpow/nes-test-roms">blargg's test ROMs</a>
 */
public class APUBlarggTests {
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @ParameterizedTest
    @ValueSource(strings = {
            "/apu-test/1-len_ctr.nes",
            "/apu-test/2-len_table.nes",
            "/apu-test/3-irq_flag.nes",
            "/apu-test/4-jitter.nes",
            "/apu-test/5-len_timing.nes",
            "/apu-test/6-irq_flag_timing.nes",
            "/apu-test/7-dmc_basics.nes",
            "/apu-test/8-dmc_rates.nes",
    })
    void apu(final String filename) throws IOException {
        BlarggRunner.runStatusProtocol(filename, TIMEOUT, Set.of());
    }

    /**
     * The other suite: what the chip looks like at power on and after the reset button.
     * <p>
     * These are the ones that press Reset on themselves -- status $81 in the $6000 protocol, which
     * {@link BlarggRunner} answers after the hundred milliseconds they ask for -- and then check
     * what survived. {@code 4017_timing} is the strictest: it measures how long after the reset
     * the frame counter behaves as though $4017 had been written, and wants somewhere between 9
     * and 12 cycles.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/apu-reset/4015_cleared.nes",
            "/apu-reset/irq_flag_cleared.nes",
            "/apu-reset/len_ctrs_enabled.nes",
            "/apu-reset/works_immediately.nes",
            "/apu-reset/4017_written.nes",
            "/apu-reset/4017_timing.nes",
    })
    void reset(final String filename) throws IOException {
        BlarggRunner.runStatusProtocol(filename, TIMEOUT, Set.of());
    }
}
