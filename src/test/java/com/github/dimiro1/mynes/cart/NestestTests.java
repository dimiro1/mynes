package com.github.dimiro1.mynes.cart;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class NestestTests {

    @Test
    void parse() throws IOException {
        var filename = "/nestest.log";

        try (var stream = this.getClass().getResourceAsStream(filename)) {
            var lines = NestestLogParser.parse(stream);

            System.out.println(lines.get(0));
            System.out.println(lines.get(1));
            System.out.println(lines.get(2));
            System.out.println(lines.get(3));
            assertEquals(0xC000, lines.get(0).pc());
        }
    }
}
