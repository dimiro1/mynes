package com.github.dimiro1.mynes.blargg;

import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.MMU;
import com.github.dimiro1.mynes.NES;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Runs blargg's test ROMs and turns what they report into a JUnit result.
 * <p>
 * The ROMs come from two eras and report in two different ways.
 * <p>
 * The later ones -- the CPU suites, {@code ppu_vbl_nmi}, the OAM tests, {@code ppu_open_bus} --
 * use the <b>$6000 protocol</b>: three magic bytes at $6001-$6003 say the protocol is live, a
 * status byte at $6000 says whether the run is still going, and a null terminated message at
 * $6004 says what happened in words.
 * <p>
 * The 2005 era ones -- sprite 0 hit, sprite overflow, the early PPU tests -- predate that. They
 * report a <b>numeric result code</b> in a fixed zero page byte, print it on screen and beep it,
 * then spin forever. Code 1 means everything passed and anything else indexes a list of failures
 * in the ROM's readme, which is vendored next to it.
 *
 * @see <a href="https://github.com/christopherpow/nes-test-roms">blargg's test ROMs</a>
 */
final class BlarggRunner {
    private static final Logger logger = LoggerFactory.getLogger(BlarggRunner.class);

    // --- $6000 protocol ---------------------------------------------------------------

    private static final int STATUS_ADDRESS = 0x6000;
    private static final int SIGNATURE_ADDRESS = 0x6001;
    private static final int MESSAGE_START_ADDRESS = 0x6004;

    private static final int STATUS_PASSED = 0x00;
    private static final int STATUS_RUNNING = 0x80;
    private static final int STATUS_RESET_REQUEST = 0x81;

    /**
     * How long to leave a reset request sitting before acting on it.
     * <p>
     * The protocol asks for at least 100ms, which on a 1.79MHz CPU is about 180,000 cycles.
     * A test that resets itself expects to have had that time to finish writing out its state,
     * and pulling the line immediately makes some of them restart mid-write.
     */
    private static final long RESET_DELAY_CYCLES = 200_000;

    // --- result code protocol ---------------------------------------------------------

    /**
     * The result code every 2005 era ROM writes when all of its sub-tests passed.
     */
    private static final int RESULT_PASSED = 1;

    /**
     * How many frames a result code ROM has to sit on one instruction before it counts as having
     * finished. Long enough that the between-frames idle loop, which NMI breaks out of, does not
     * look like the loop it spins in after reporting.
     */
    private static final int STUCK_FRAMES = 3;

    private BlarggRunner() {
    }

    /**
     * Runs a ROM that reports through the $6000 protocol.
     *
     * @param resource   the classpath resource holding the ROM.
     * @param timeout    how long to give the whole run in wall clock time.
     * @param deviations failure lines that are known hardware-to-hardware differences rather than
     *                   emulator bugs, and so do not fail the test.
     * @throws IOException if the ROM cannot be read.
     */
    static void runStatusProtocol(
            final String resource,
            final Duration timeout,
            final Set<String> deviations
    ) throws IOException {
        var nes = load(resource);
        var bus = nes.getBus();
        var memory = nes.getMemory();

        assertTimeoutPreemptively(timeout, () -> {
            var running = true;
            var resetRequestedAt = -1L;
            var resetDone = false;

            while (running) {
                nes.step();

                if (!hasTestSignature(memory)) {
                    continue;
                }

                var status = memory.read(STATUS_ADDRESS);

                if (status == STATUS_PASSED) {
                    logger.info(() -> "Screen message:\n" + getTestMessage(memory));
                    running = false;
                } else if (status == STATUS_RUNNING) {
                    // Re-arm, so a later reset request is treated as a fresh one rather than as
                    // the one just serviced.
                    resetRequestedAt = -1L;
                    resetDone = false;
                } else if (status == STATUS_RESET_REQUEST && !resetDone) {
                    // The test asks for a reset, but not before it has had its 100ms.
                    var now = nes.getCPU().getState().cycles();

                    if (resetRequestedAt < 0) {
                        resetRequestedAt = now;
                    } else if (now - resetRequestedAt >= RESET_DELAY_CYCLES) {
                        bus.triggerRST();
                        resetDone = true;
                    }
                } else if (status != STATUS_RESET_REQUEST) {
                    running = false;
                    var message = getTestMessage(memory);

                    if (isAcceptedDeviation(message, deviations)) {
                        logger.warn(() -> "Accepted known chip-to-chip deviation:\n" + message);
                    } else {
                        logger.error(() -> "Screen message:\n" + message);
                        fail(String.format(
                                "Test failed with status code $%02X (expected $%02X for pass):\n%s",
                                status, STATUS_PASSED, message
                        ));
                    }
                }
            }
        });
    }

    /**
     * Runs a 2005 era ROM that reports a numeric result code in zero page.
     * <p>
     * There is no "still running" status to poll, so the run stops on whichever of three things
     * happens first: the pass code appears, the ROM reaches the single instruction loop it spins
     * in once it has reported, or the frame budget runs out. The last of those is a failure in
     * its own right, but the code found in memory is still the most useful thing to print --
     * it names the sub-test the ROM was in the middle of when it stopped making progress.
     *
     * @param resource      the classpath resource holding the ROM.
     * @param resultAddress the zero page byte the ROM keeps its result code in.
     * @param frameBudget   how many frames of emulated time to allow.
     * @param timeout       how long to give the whole run in wall clock time.
     * @throws IOException if the ROM cannot be read.
     */
    static void runResultCode(
            final String resource,
            final int resultAddress,
            final long frameBudget,
            final Duration timeout
    ) throws IOException {
        var nes = load(resource);
        var memory = nes.getMemory();
        var ppu = nes.getPPU();

        assertTimeoutPreemptively(timeout, () -> {
            var previousPC = -1;
            var movedOnFrame = 0L;
            var settled = false;

            while (!settled && ppu.getFrame() < frameBudget) {
                nes.step();

                if (memory.read(resultAddress) == RESULT_PASSED) {
                    return;
                }

                // Once it has reported, the ROM masks interrupts and sits in "exit: jmp exit",
                // so a program counter that stops moving means there is nothing more to wait for.
                // It has to have stopped for a while: these ROMs idle in a one instruction
                // "wait: jmp wait" loop between frames and let NMI do the work, and that looks
                // exactly the same from one step to the next.
                var pc = nes.getCPU().getState().pc();

                if (pc != previousPC) {
                    previousPC = pc;
                    movedOnFrame = ppu.getFrame();
                }

                settled = ppu.getFrame() - movedOnFrame >= STUCK_FRAMES;
            }

            var result = memory.read(resultAddress);

            if (result == RESULT_PASSED) {
                return;
            }

            fail(String.format(
                    "%s reported result code %d after %d frames (1 means passed; see the "
                            + "vendored readme.txt for what %d means)",
                    resource, result, ppu.getFrame(), result
            ));
        });
    }

    private static NES load(final String resource) throws IOException {
        try (var romStream = BlarggRunner.class.getResourceAsStream(resource)) {
            assertNotNull(romStream, "ROM file not found: " + resource);
            return new NES(Cart.load(romStream.readAllBytes(), resource));
        }
    }

    /**
     * Decides whether every instruction the ROM reported as failing is a documented
     * chip-to-chip deviation for that ROM.
     * <p>
     * These ROMs print the failing instructions first, one per line, followed by a blank line
     * and the ROM name, so the leading lines of the message are the failure list.
     *
     * @param message    the null-terminated status message the ROM wrote to memory.
     * @param deviations the failures that are accepted for this ROM.
     * @return true if the ROM failed, but only on lines in {@code deviations}.
     */
    private static boolean isAcceptedDeviation(final String message, final Set<String> deviations) {
        var failing = message.lines()
                .takeWhile(line -> !line.isBlank())
                .map(String::trim)
                .collect(Collectors.toSet());

        return !failing.isEmpty() && deviations.containsAll(failing);
    }

    /**
     * Checks if the Blargg test signature is present in memory.
     * <p>
     * The signature consists of three magic bytes at $6001-$6003: 0xDE 0xB0 0x61
     *
     * @param mmu the memory management unit to read from
     * @return true if the test signature is present
     */
    private static boolean hasTestSignature(final MMU mmu) {
        return mmu.read(SIGNATURE_ADDRESS) == 0xDE
                && mmu.read(SIGNATURE_ADDRESS + 1) == 0xB0
                && mmu.read(SIGNATURE_ADDRESS + 2) == 0x61;
    }

    /**
     * Reads the null-terminated ASCII message from test memory.
     * <p>
     * The message starts at address $6004 and continues until a null byte (0x00). Some of the
     * newer ROMs colour their output with ANSI escape sequences, which are dropped here so that
     * the deviation matching sees plain text.
     *
     * @param mmu the memory management unit to read from
     * @return the test status message as a string
     */
    private static String getTestMessage(final MMU mmu) {
        var message = new StringBuilder();
        var address = MESSAGE_START_ADDRESS;

        // Bounded by the end of the 8KB save RAM window the message lives in, so a ROM that never
        // wrote a terminator produces a bad message rather than an endless loop.
        while (address < 0x8000) {
            var data = mmu.read(address++);

            if (data == 0) {
                break;
            }

            if (data == 0x1B) {
                // An escape sequence: "[", then digits and semicolons, then a letter.
                while (address < 0x8000 && !Character.isLetter(mmu.read(address))) {
                    address++;
                }
                address++;
                continue;
            }

            message.append((char) data);
        }

        return message.toString();
    }
}
