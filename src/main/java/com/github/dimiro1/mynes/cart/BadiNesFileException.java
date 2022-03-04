package com.github.dimiro1.mynes.cart;

public class BadiNesFileException extends RuntimeException {
    public BadiNesFileException(final String filename) {
        super(String.format("%s is not a valid .nes file", filename));
    }
}
