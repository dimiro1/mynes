package com.github.dimiro1.mynes.cart;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import static org.junit.jupiter.api.Assertions.*;

public class CartTests {
    @Test
    void load() throws IOException {
        var filename = "/nestest.nes";

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
        var filename = "/nestest.log";

        try (InputStream stream = this.getClass().getResourceAsStream(filename)) {
            assertNotNull(stream);

            assertThrowsExactly(InvalidINesFileException.class, () -> {
                Cart.load(stream.readAllBytes(), filename);
            });
        }
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
