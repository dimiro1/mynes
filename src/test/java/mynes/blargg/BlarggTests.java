package mynes.blargg;

import mynes.NES;
import mynes.cart.Cart;
import mynes.memory.Memory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

public class BlarggTests {
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

                var result = new int[]{
                        memory.read(0x6001),
                        memory.read(0x6002),
                        memory.read(0x6003)
                };

                if (Arrays.equals(result, new int[]{0xDE, 0xB0, 0x61})) {
                    var data = memory.read(0x6000);

                    switch (data) {
                        case 0x00:
                            // Success
                            System.out.println("Screen message:\n" + getMessage(memory));
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
                            System.out.println("Screen message:\n" + getMessage(memory));
                            fail(String.format("expected $%02X, got $%02X", 0x80, data));
                            break;
                    }
                }
            }
        });
    }

    private String getMessage(final Memory memory) {
        var buffer = new ByteArrayOutputStream();
        var address = 0x6004;

        while (true) {
            var data = memory.read(address);
            if (data == 0) {
                break;
            }
            address++;
            buffer.write((byte) data);
        }

        return buffer.toString(StandardCharsets.US_ASCII);
    }
}
