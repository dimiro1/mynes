package com.github.dimiro1.mynes.memory;

public class InvalidAddress extends RuntimeException {
    public InvalidAddress(int address, String name) {
        super(String.format("invalid address: %02X, block: %s", address, name));
    }
}
