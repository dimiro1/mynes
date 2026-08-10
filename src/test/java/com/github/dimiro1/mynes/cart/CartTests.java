package com.github.dimiro1.mynes.cart;

import com.github.dimiro1.mynes.InvalidNesFileException;
import com.github.dimiro1.mynes.Cart;
import com.github.dimiro1.mynes.UnsupportedMapperException;
import com.github.dimiro1.mynes.mappers.Mapper0;
import com.github.dimiro1.mynes.mappers.Mapper1;
import com.github.dimiro1.mynes.mappers.Mapper2;
import com.github.dimiro1.mynes.mappers.Mapper3;
import com.github.dimiro1.mynes.mappers.Mapper4;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.params.provider.Arguments.arguments;

public class CartTests {
    @Test
    void load() throws IOException {
        var filename = "/nestest/nestest.nes";

        try (var stream = this.getClass().getResourceAsStream(filename)) {
            assertNotNull(stream);

            assertDoesNotThrow(() -> {
                var cart = Cart.load(stream.readAllBytes(), filename);

                assertEquals(filename, cart.filename());
                assertEquals("79e74c4c8e3218b332117c5043493f1e", md5(cart.prgROM()));
                assertEquals("4f094c912a70b39b38403b1f7a037579", md5(cart.chrROM()));
            });
        }
    }

    @Test
    void loadInvalid() throws IOException {
        var filename = "/nestest/nestest.log";

        try (InputStream stream = this.getClass().getResourceAsStream(filename)) {
            assertNotNull(stream);

            assertThrowsExactly(InvalidNesFileException.class, () -> Cart.load(stream.readAllBytes(), filename));
        }
    }

    /**
     * The mapper number is split across the high nibbles of flags 6 and 7. Reading it wrongly is
     * silent: an unsupported cart loads as mapper 0 and then misbehaves at run time instead of
     * being rejected here.
     */
    @Test
    void loadUnsupportedMapper() {
        var rom = synthesizeRom(0x50, 0x00);  // mapper 5, MMC5

        var thrown = assertThrowsExactly(
                UnsupportedMapperException.class,
                () -> Cart.load(rom, "mapper5.nes")
        );

        assertTrue(thrown.getMessage().contains("5"), "should name the mapper: " + thrown.getMessage());
    }

    @ParameterizedTest(name = "mapper ${0} loads as ${1}")
    @MethodSource("supportedMappers")
    void loadBuildsTheMapperTheHeaderNames(final int number, final Class<?> expected) {
        var rom = synthesizeRom(number << 4, 0x00);

        var cart = Cart.load(rom, "mapper" + number + ".nes");

        assertInstanceOf(expected, cart.mapper());
    }

    private static Stream<Arguments> supportedMappers() {
        return Stream.of(
                arguments(0, Mapper0.class),
                arguments(1, Mapper1.class),
                arguments(2, Mapper2.class),
                arguments(3, Mapper3.class),
                arguments(4, Mapper4.class)
        );
    }

    @Test
    void loadReadsTheMapperNumberFromBothFlagBytes() {
        // Mapper 66 = 0x42: low nibble from flags 6, high nibble from flags 7.
        var rom = synthesizeRom(0x20, 0x40);

        var thrown = assertThrowsExactly(
                UnsupportedMapperException.class,
                () -> Cart.load(rom, "mapper66.nes")
        );

        assertTrue(thrown.getMessage().contains("66"), "should name the mapper: " + thrown.getMessage());
    }

    /**
     * Builds a minimal but valid iNES image: a header, one 16KB PRG bank and no CHR banks.
     */
    private byte[] synthesizeRom(final int flags6, final int flags7) {
        var rom = new byte[16 + 0x4000];

        rom[0] = 'N';
        rom[1] = 'E';
        rom[2] = 'S';
        rom[3] = 0x1A;
        rom[4] = 1;  // one PRG bank
        rom[5] = 0;  // no CHR banks
        rom[6] = (byte) flags6;
        rom[7] = (byte) flags7;

        return rom;
    }

    private String md5(byte[] data) {
        try {
            var md5 = MessageDigest.getInstance("MD5");
            return String.format("%x", new BigInteger(1, md5.digest(data)));
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return "";
        }
    }
}
