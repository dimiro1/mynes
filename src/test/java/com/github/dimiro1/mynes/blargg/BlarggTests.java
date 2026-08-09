package com.github.dimiro1.mynes.blargg;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;

/**
 * Test suite for Blargg's NES CPU test ROMs.
 * <p>
 * These ROMs report through the $6000 protocol; {@link BlarggRunner} explains it and does the
 * driving. The PPU suites live in {@link PPUBlarggTests}.
 *
 * @see <a href="https://github.com/christopherpow/nes-test-roms">Blargg's Test ROMs</a>
 */
public class BlarggTests {
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /**
     * Instructions that are allowed to be reported as failing, per ROM.
     * <p>
     * These ROMs compare a checksum against one blargg captured from his own console, so an
     * instruction whose behaviour varies between physical chips is pinned to whatever that
     * console did. ATX is such an instruction: it ORs the accumulator with a constant that
     * depends on the chip and its temperature, and blargg's console behaved as if that constant
     * were $FF, while the Tom Harte set this project verifies every opcode against uses $EE.
     * There is no value of the constant that satisfies both, so the CPU follows the Harte set
     * (see {@code CPU.UNSTABLE_MAGIC}) and the disagreement is recorded here.
     * <p>
     * This is deliberately keyed on the exact instruction blargg prints: any other failure in
     * the same ROM still fails the test.
     */
    private static final Map<String, Set<String>> ACCEPTED_DEVIATIONS = Map.of(
            "/instr-test-v5/03-immediate.nes", Set.of("AB ATX #n")
    );

    /**
     * Tests various CPU instruction test ROMs from Blargg's test suite.
     * <p>
     * These tests verify correct CPU instruction implementation including:
     * addressing modes, stack operations, branches, jumps, and special instructions.
     *
     * @param filename the path to the test ROM resource
     * @throws IOException if the ROM file cannot be read
     */
    @ParameterizedTest
    @ValueSource(strings = {
            "/instr-test-v5/01-basics.nes",
            "/instr-test-v5/02-implied.nes",
            "/instr-test-v5/03-immediate.nes",
            "/instr-test-v5/04-zero_page.nes",
            "/instr-test-v5/05-zp_xy.nes",
            "/instr-test-v5/06-absolute.nes",
            "/instr-test-v5/07-abs_xy.nes",
            "/instr-test-v5/08-ind_x.nes",
            "/instr-test-v5/09-ind_y.nes",
            "/instr-test-v5/10-branches.nes",
            "/instr-test-v5/11-stack.nes",
            "/instr-test-v5/12-jmp_jsr.nes",
            "/instr-test-v5/13-rts.nes",
            "/instr-test-v5/14-rti.nes",
            "/instr-test-v5/15-brk.nes",
            "/instr-test-v5/16-special.nes",
            "/instr-misc/01-abs_x_wrap.nes",
            "/instr-misc/02-branch_wrap.nes",
            "/instr-misc/03-dummy_reads.nes",
            "/cpu-reset/registers.nes",
            "/cpu-reset/ram_after_reset.nes",
//            "/instr-misc/04-dummy_reads_apu.nes", // APU Required
//            "/instr-timing/instr-timing.nes", // APU Required
    })
    void instructionsV5(final String filename) throws IOException {
        BlarggRunner.runStatusProtocol(
                filename, TIMEOUT, ACCEPTED_DEVIATIONS.getOrDefault(filename, Set.of())
        );
    }
}
