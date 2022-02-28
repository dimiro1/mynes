package mynes.nestests;

import mynes.NES;
import mynes.cart.Cart;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class NestestTests {

    @Test
    void nesTest() throws IOException {
        var log = "/nestest/nestest.log";
        var rom = "/nestest/nestest.nes";
        var romStream = this.getClass().getResourceAsStream(rom);

        assertNotNull(romStream);

        var cart = Cart.load(romStream.readAllBytes(), rom);
        var nes = new NES(cart);
        var cpu = nes.getCPU();
        var memory = nes.getMemory();
        var cpuListener = new NestestCPUListener();

        // Handle reset
        nes.step();

        cpu.setPC(0xC000);
        cpu.addEventListener(cpuListener);

        try (var stream = this.getClass().getResourceAsStream(log)) {
            NestestLogParser.parse(stream).forEach((entry) -> {
                nes.step();

                if (!entry.equals(cpuListener.getCurrentStep())) {
                    System.out.printf("%X\n", memory.read(0x02));
                    System.out.printf("%X\n", memory.read(0x03));
                    System.out.println(cpuListener.getPreviousStep());
                }
                assertEquals(entry, cpuListener.getCurrentStep());
            });
        }
        System.out.printf("%X\n", memory.read(0x02));
        System.out.println(cpuListener.getCurrentStep());
    }

    @Test
    void parse() throws IOException {
        var filename = "/nestest/nestest.log";

        try (var stream = this.getClass().getResourceAsStream(filename)) {
            var lines = NestestLogParser.parse(stream);

            assertEquals(
                    new NestestLogParser.Entry(
                            0xC000,
                            new int[]{0x4C, 0xF5, 0xC5},
                            0,
                            0,
                            0,
                            0x24,
                            0xfd,
                            7
                    ),
                    lines.get(0)
            );

            assertEquals(
                    new NestestLogParser.Entry(
                            0xD136,
                            new int[]{0xE1, 0x80},
                            0x40,
                            0,
                            0x6C,
                            0x65,
                            0xfb,
                            3607
                    ),
                    lines.get(1395)
            );
        }
    }
}
