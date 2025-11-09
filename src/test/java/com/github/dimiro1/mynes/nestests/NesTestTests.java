package com.github.dimiro1.mynes.nestests;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Cart;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Test suite for the nestest ROM.
 * <p>
 * Nestest is a comprehensive CPU test ROM that validates correct implementation
 * of all official and unofficial 6502 opcodes. It provides a detailed execution
 * log that can be compared against the expected behavior.
 * <p>
 * The test works by:
 * <ul>
 *   <li>Loading the nestest ROM and starting execution at $C000</li>
 *   <li>Comparing each instruction's state against a golden log file</li>
 *   <li>Reading test results from memory addresses $02-$03 after completion</li>
 * </ul>
 * <p>
 * Reference: <a href="https://github.com/christopherpow/nes-test-roms/tree/master/other">nestest.nes</a>
 */
public class NesTestTests {
    private static final Logger logger = LoggerFactory.getLogger(NesTestTests.class);

    // Nestest configuration
    private static final int NESTEST_START_PC = 0xC000;  // Starting program counter for automated test mode
    private static final int NESTEST_START_CYCLES = 7;   // Initial cycle count after reset

    // Test result addresses
    private static final int TEST_RESULT_LOW = 0x02;     // Low byte of test result code
    private static final int TEST_RESULT_HIGH = 0x03;    // High byte of test result code

    /**
     * Runs the full nestest ROM and validates execution against the golden log.
     * <p>
     * This test verifies that every CPU instruction executes correctly by comparing
     * the CPU state (PC, registers, flags, cycles) after each instruction against
     * the expected values from nestest.log.
     *
     * @throws IOException if the ROM or log file cannot be read
     */
    @Test
    void nesTest() throws IOException {
        var logFile = "/nestest/nestest.log";
        var romFile = "/nestest/nestest.nes";
        var romStream = this.getClass().getResourceAsStream(romFile);

        assertNotNull(romStream, "ROM file not found: " + romFile);

        var cart = Cart.load(romStream.readAllBytes(), romFile);
        var nes = new NES(cart);
        var cpu = nes.getCPU();
        var memory = nes.getMemory();
        var cpuListener = new NestestCPUListener();

        cpu.addEventListener(cpuListener);

        // Initialize CPU state for automated test mode
        // Nestest requires starting at $C000 instead of using the reset vector
        nes.step();  // Execute reset
        cpu.setPC(NESTEST_START_PC);
        cpu.setCycles(NESTEST_START_CYCLES);

        try (var stream = this.getClass().getResourceAsStream(logFile)) {
            assertNotNull(stream, "Log file not found: " + logFile);

            for (NestestLogParser.Entry expectedEntry : NestestLogParser.parse(stream)) {
                nes.step();

                var actualEntry = cpuListener.getCurrentStep();
                if (!expectedEntry.equals(actualEntry)) {
                    // Log diagnostic information on failure
                    var resultLow = memory.read(TEST_RESULT_LOW);
                    var resultHigh = memory.read(TEST_RESULT_HIGH);
                    logger.error(() -> String.format(
                            "Test result code: $%02X%02X", resultHigh, resultLow
                    ));
                    logger.error(() -> "Previous step: " + cpuListener.getPreviousStep());
                    logger.error(() -> "Expected: " + expectedEntry);
                    logger.error(() -> "Actual:   " + actualEntry);
                }
                assertEquals(expectedEntry, actualEntry, "CPU state mismatch");
            }
        }

        // Log final test results
        var resultLow = memory.read(TEST_RESULT_LOW);
        var resultHigh = memory.read(TEST_RESULT_HIGH);
        logger.info(() -> String.format("Test completed. Result code: $%02X%02X", resultHigh, resultLow));
        logger.info(() -> "Final state: " + cpuListener.getCurrentStep());
    }

    /**
     * Tests the nestest log parser to ensure it correctly parses CPU state entries.
     * <p>
     * This verifies that the parser can extract instruction data, register values,
     * and cycle counts from the golden log file format.
     *
     * @throws IOException if the log file cannot be read
     */
    @Test
    void parseLogFile() throws IOException {
        var logFile = "/nestest/nestest.log";

        try (var stream = this.getClass().getResourceAsStream(logFile)) {
            assertNotNull(stream, "Log file not found: " + logFile);
            var entries = NestestLogParser.parse(stream);

            // Verify first entry (PC=$C000, JMP $C5F5)
            assertEquals(
                    new NestestLogParser.Entry(
                            0xC000,  // pc
                            0x4C,    // instruction[0] (JMP opcode)
                            0xF5,    // instruction[1] (low byte)
                            0xC5,    // instruction[2] (high byte)
                            3,       // length
                            0,       // a
                            0,       // x
                            0,       // y
                            0x24,    // p
                            0xFD,    // sp
                            7        // cycle
                    ),
                    entries.getFirst(),
                    "First log entry should match"
            );

            // Verify entry at index 1395 (PC=$D136, SBC ($80,X))
            assertEquals(
                    new NestestLogParser.Entry(
                            0xD136,  // pc
                            0xE1,    // instruction[0] (SBC opcode)
                            0x80,    // instruction[1]
                            0,       // instruction[2]
                            2,       // length
                            0x40,    // a
                            0,       // x
                            0x6C,    // y
                            0x65,    // p
                            0xFB,    // sp
                            3607     // cycle
                    ),
                    entries.get(1395),
                    "Entry at index 1395 should match"
            );
        }
    }
}
