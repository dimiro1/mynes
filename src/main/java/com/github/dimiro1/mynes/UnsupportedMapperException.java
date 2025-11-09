package com.github.dimiro1.mynes;

/**
 * Exception thrown when attempting to load a ROM that uses an unsupported mapper.
 * <p>
 * The NES used various memory mappers (MMC chips) to extend the capabilities
 * of the base hardware. This emulator may not support all mapper types.
 */
public class UnsupportedMapperException extends RuntimeException {
    /**
     * Creates a new exception for an unsupported mapper.
     *
     * @param mapperNumber the mapper number that is not supported
     * @param filename the ROM file that uses this mapper
     */
    public UnsupportedMapperException(final int mapperNumber, final String filename) {
        super("Mapper " + mapperNumber + " is not supported in file: " + filename);
    }
}
