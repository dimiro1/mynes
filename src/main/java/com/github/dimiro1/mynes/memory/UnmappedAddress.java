package com.github.dimiro1.mynes.memory;

public class UnmappedAddress extends RuntimeException {
    public UnmappedAddress(int address) {
        super(String.format("invalid address: %02X", address));
    }
}
