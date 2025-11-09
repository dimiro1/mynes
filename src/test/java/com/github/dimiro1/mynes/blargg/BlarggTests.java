package com.github.dimiro1.mynes.blargg;

import com.github.dimiro1.mynes.NES;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.MMU;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.platform.commons.logging.Logger;
import org.junit.platform.commons.logging.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

public class BlarggTests {
    Logger logger = LoggerFactory.getLogger(BlarggTests.class);

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

        assertNotNull(romStream);

        var cart = Cart.load(romStream.readAllBytes(), filename);
        var nes = new NES(cart);
        var cpu = nes.getCPU();
        var memory = nes.getMemory();

        assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
            var running = true;
            var resetRequested = false;

            while (running) {
                nes.step();

                if (memory.read(0x6001) == 0xDE
                        && memory.read(0x6002) == 0xB0
                        && memory.read(0x6003) == 0x61
                ) {
                    var status = memory.read(0x6000);

                    switch (status) {
                        case 0x00:
                            // Success
                            logger.info(() -> "Screen message:\n" + getMessage(memory));
                            running = false;
                            break;
                        case 0x80:
                            // running
                            break;
                        case 0x81:
                            if (!resetRequested) {
                                cpu.requestRST();
                                resetRequested = true;
                            }
                            break;
                        default:
                            running = false;
                            logger.error(() -> "Screen message:\n" + getMessage(memory));
                            fail(String.format("expected $%02X, got $%02X", 0x80, status));
                            break;
                    }
                }
            }
        });
    }

    private String getMessage(final MMU MMU) {
        var buffer = new ByteArrayOutputStream();
        var address = 0x6004;

        while (true) {
            var data = MMU.read(address);
            if (data == 0) {
                break;
            }
            address++;
            buffer.write((byte) data);
        }

        return buffer.toString(StandardCharsets.US_ASCII);
    }
}
