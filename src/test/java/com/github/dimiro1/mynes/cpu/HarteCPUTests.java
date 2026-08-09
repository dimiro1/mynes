package com.github.dimiro1.mynes.cpu;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Per-opcode verification against the Tom Harte SingleStepTests {@code nes6502/v1} set.
 * <p>
 * Every case is a single instruction executed from a known register and memory state, with the
 * expected end state and the expected per-cycle bus traffic recorded from a reference
 * implementation. That makes this the only test in the suite that pins down individual opcodes;
 * nestest and the blargg ROMs only observe the machine end to end.
 * <p>
 * The set is generated for the 2A03, so the decimal flag is stored but ignored by ADC and SBC,
 * which is exactly what {@link com.github.dimiro1.mynes.CPU} implements. There is deliberately
 * no special-casing of D anywhere in this harness.
 * <p>
 * Two strictness stages run as separate factories, both on by default:
 * <ul>
 *   <li><b>registers and memory</b> -- final state plus cycle count.</li>
 *   <li><b>bus trace</b> -- the ordered, per-cycle address/value/direction sequence, which also
 *       pins down the dummy reads and writes that never affect the end state but that the PPU
 *       and the mappers can see. Tagged {@code bus-trace}, so it can be run on its own with
 *       {@code mvn test -Dgroups=bus-trace} or skipped with
 *       {@code mvn test -DexcludedGroups=bus-trace}.</li>
 * </ul>
 * <p>
 * Triage rule: a failing opcode that is not on {@link #SKIPPED} is a real bug in the CPU.
 * Fix the CPU, never loosen the assertions.
 *
 * @see <a href="https://github.com/SingleStepTests/65x02">SingleStepTests/65x02</a>
 */
public class HarteCPUTests {
    private static final Logger logger = LoggerFactory.getLogger(HarteCPUTests.class);

    private static final int OPCODE_COUNT = 256;

    // Enough failures to spot a pattern without dumping ten thousand of them.
    private static final int MAX_REPORTED_FAILURES = 5;

    private static final Duration PER_OPCODE_TIMEOUT = Duration.ofSeconds(60);

    private static final String JAMMED = "the CPU jams on this opcode by design; there is no "
            + "end state to compare against";

    // kil() throws, which would take down the whole opcode node.
    private static final int[] JAMMING_OPCODES = {
            0x02, 0x12, 0x22, 0x32, 0x42, 0x52, 0x62, 0x72, 0x92, 0xB2, 0xD2, 0xF2,
    };

    private static final Map<Integer, String> SKIPPED = skipList();

    private static Map<Integer, String> skipList() {
        var skipped = new HashMap<Integer, String>();

        for (var opcode : JAMMING_OPCODES) {
            skipped.put(opcode, JAMMED);
        }

        return Map.copyOf(skipped);
    }

    /**
     * Stage 1: final registers, final memory and cycle count for all 256 opcodes.
     */
    @TestFactory
    Stream<DynamicTest> registersAndMemory() {
        return opcodeTests(HarteCaseRunner.Strictness.STATE);
    }

    /**
     * Stage 2: everything stage 1 checks, plus the exact per-cycle bus trace.
     */
    @Tag("bus-trace")
    @TestFactory
    Stream<DynamicTest> busTrace() {
        return opcodeTests(HarteCaseRunner.Strictness.BUS_TRACE);
    }

    /**
     * Guards against pointing the harness at the wrong data set.
     * <p>
     * The plain {@code 6502/v1} set has the same file layout as {@code nes6502/v1} but models a
     * CPU with working BCD, so ADC and SBC results differ whenever the decimal flag happens to
     * be set. Rather than let that surface as thousands of confusing arithmetic failures, assert
     * up front that the data is binary-mode and that its status register follows the usual
     * convention of an always-set bit 5.
     */
    @Test
    void datasetIsTheNesVariant() throws Exception {
        logger.info(() -> "Harte data source: " + HarteCaseLoader.sourceDescription());

        var decimalCases = new int[]{0};
        var checked = new int[]{0};

        try (var input = HarteCaseLoader.open(0x69)) {  // ADC #immediate
            HarteCaseLoader.stream(input, testCase -> {
                var initial = testCase.initial();
                checked[0]++;

                assertEquals(
                        0x20, initial.p() & 0x20,
                        "bit 5 of P is clear in case \"" + testCase.name()
                                + "\"; this data set does not use the expected P convention"
                );

                if ((initial.p() & 0x08) == 0) {
                    return;  // decimal flag clear, nothing to distinguish
                }

                decimalCases[0]++;
                var immediate = byteAt(testCase, initial.pc() + 1);
                var binary = (initial.a() + immediate + (initial.p() & 0x01)) & 0xFF;

                assertEquals(
                        binary, testCase.expected().a(),
                        "ADC produced a decimal-adjusted result in case \"" + testCase.name()
                                + "\"; this looks like the 6502/v1 set rather than nes6502/v1"
                );
            });
        }

        assertTrue(checked[0] > 0, "opcode $69 has no cases");
        assertTrue(decimalCases[0] > 0, "no case with the decimal flag set, cannot tell the sets apart");
    }

    private Stream<DynamicTest> opcodeTests(final HarteCaseRunner.Strictness strictness) {
        return IntStream.range(0, OPCODE_COUNT)
                .mapToObj(opcode -> DynamicTest.dynamicTest(
                        String.format("$%02X", opcode),
                        () -> runOpcode(opcode, strictness)
                ));
    }

    private void runOpcode(final int opcode, final HarteCaseRunner.Strictness strictness) {
        var skipReason = SKIPPED.get(opcode);
        if (skipReason != null) {
            Assumptions.abort(String.format("$%02X skipped: %s", opcode, skipReason));
        }

        assertTimeoutPreemptively(PER_OPCODE_TIMEOUT, () -> {
            var runner = new HarteCaseRunner();
            var failures = new ArrayList<String>();
            var total = new int[]{0};

            try (var input = HarteCaseLoader.open(opcode)) {
                HarteCaseLoader.stream(input, testCase -> {
                    total[0]++;

                    if (failures.size() >= MAX_REPORTED_FAILURES) {
                        return;
                    }

                    var failure = runner.run(testCase, strictness);
                    if (failure != null) {
                        failures.add(failure);
                    }
                });
            }

            if (!failures.isEmpty()) {
                fail(describe(opcode, total[0], failures));
            }
        });
    }

    private static String describe(final int opcode, final int total, final List<String> failures) {
        var message = new StringBuilder(String.format(
                "opcode $%02X failed (%d cases run, showing the first %d failure(s)):%n",
                opcode, total, failures.size()
        ));

        for (var failure : failures) {
            message.append(failure);
        }

        return message.toString();
    }

    private static int byteAt(final HarteCase testCase, final int address) {
        for (var entry : testCase.initial().ram()) {
            if (entry[0] == (address & 0xFFFF)) {
                return entry[1];
            }
        }
        throw new IllegalStateException(String.format(
                "case \"%s\" does not seed address $%04X", testCase.name(), address
        ));
    }
}
