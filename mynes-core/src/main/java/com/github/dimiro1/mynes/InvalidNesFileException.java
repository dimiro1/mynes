package com.github.dimiro1.mynes;

/**
 * A file that is not a cartridge at all.
 * <p>
 * The sibling of {@link UnsupportedMapperException}, and the line between the two is worth keeping.
 * This one means the bytes are not an iNES or NES 2.0 image -- the wrong four at the front, or a
 * header promising more banks than the file has left to give. That one means the image parsed
 * perfectly and named a board nobody has written. The first is somebody's file being wrong; the
 * second is this emulator being unfinished, and only one of them is worth reporting.
 */
public class InvalidNesFileException extends RuntimeException {
    /**
     * Creates a new exception for an invalid NES ROM file.
     *
     * @param filename the name of the file that failed validation
     */
    public InvalidNesFileException(final String filename) {
        super(String.format("%s is not a valid .nes file", filename));
    }
}
