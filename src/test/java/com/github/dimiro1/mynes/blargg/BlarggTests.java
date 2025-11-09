package com.github.dimiro1.mynes.blargg;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.MMU;
import com.github.dimiro1.mynes.NES;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for Blargg's NES CPU test ROMs.
 * <p>
 * These test ROMs use a memory-mapped protocol to report test status:
 * <ul>
 *   <li>$6000: Status code (0x00=pass, 0x80=running, 0x81=reset request, other=fail)</li>
 *   <li>$6001-$6003: Magic signature bytes (0xDE, 0xB0, 0x61)</li>
 *   <li>$6004+: Null-terminated ASCII status message</li>
 * </ul>
 * <p>
 * Reference: <a href="https://github.com/christopherpow/nes-test-roms">Blargg's Test ROMs</a>
 */
public class BlarggTests {
    private static final Logger logger = LoggerFactory.getLogger(BlarggTests.class);

    // Blargg test protocol memory addresses
    private static final int STATUS_ADDRESS = 0x6000;
    private static final int SIGNATURE_ADDRESS = 0x6001;
    private static final int MESSAGE_START_ADDRESS = 0x6004;

    // Blargg test status codes
    private static final int STATUS_PASSED = 0x00;
    private static final int STATUS_RUNNING = 0x80;
    private static final int STATUS_RESET_REQUEST = 0x81;

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
//            "/instr-misc/03-dummy_reads.nes", // PPU Required
//            "/instr-misc/04-dummy_reads_apu.nes", // APU Required
//            "/instr-timing/instr-timing.nes", // APU Required
    })
    void instructionsV5(final String filename) throws IOException {
        var romStream = this.getClass().getResourceAsStream(filename);

        assertNotNull(romStream, "ROM file not found: " + filename);

        var cart = Cart.load(romStream.readAllBytes(), filename);
        var nes = new NES(cart);
        var bus = nes.getBus();
        var memory = nes.getMemory();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var running = true;
            var resetRequested = false;

            while (running) {
                nes.step();

                // Check if test has written the signature bytes
                if (hasTestSignature(memory)) {
                    var status = memory.read(STATUS_ADDRESS);

                    switch (status) {
                        case STATUS_PASSED:
                            // Test passed successfully
                            logger.info(() -> "Screen message:\n" + getTestMessage(memory));
                            running = false;
                            break;

                        case STATUS_RUNNING:
                            // Test is still running, continue
                            break;

                        case STATUS_RESET_REQUEST:
                            // Test requests a CPU reset
                            if (!resetRequested) {
                                bus.triggerRST();
                                resetRequested = true;
                            }
                            break;

                        default:
                            // Test failed with error code
                            running = false;
                            var message = getTestMessage(memory);
                            logger.error(() -> "Screen message:\n" + message);
                            fail(String.format(
                                    "Test failed with status code $%02X (expected $%02X for pass):\n%s",
                                    status, STATUS_PASSED, message
                            ));
                            break;
                    }
                }
            }
        });
    }

    /**
     * Checks if the Blargg test signature is present in memory.
     * <p>
     * The signature consists of three magic bytes at $6001-$6003: 0xDE 0xB0 0x61
     *
     * @param mmu the memory management unit to read from
     * @return true if the test signature is present
     */
    private boolean hasTestSignature(final MMU mmu) {
        return mmu.read(SIGNATURE_ADDRESS) == 0xDE
                && mmu.read(SIGNATURE_ADDRESS + 1) == 0xB0
                && mmu.read(SIGNATURE_ADDRESS + 2) == 0x61;
    }

    /**
     * Reads the null-terminated ASCII message from test memory.
     * <p>
     * The message starts at address $6004 and continues until a null byte (0x00).
     *
     * @param mmu the memory management unit to read from
     * @return the test status message as a string
     */
    private String getTestMessage(final MMU mmu) {
        var message = new StringBuilder();
        var address = MESSAGE_START_ADDRESS;

        while (true) {
            var data = mmu.read(address);
            if (data == 0) {
                break;
            }
            message.append((char) data);
            address++;
        }

        return message.toString();
    }
}
