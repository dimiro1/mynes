package com.github.dimiro1.mynes.cart;

public class InvalidINesFileException extends RuntimeException {
    public InvalidINesFileException(final String filename) {
        super(String.format("%s is not a valid .nes file", filename));
    }
}
