package com.github.dimiro1.mynes;

/**
 * Exception thrown when attempting to load an invalid or malformed NES ROM file.
 * <p>
 * This exception is thrown when a file fails to meet the iNES format specifications,
 * such as:
 * <ul>
 *   <li>Invalid magic number (file doesn't start with "NES\x1A")</li>
 *   <li>Missing or invalid PRG-ROM data</li>
 *   <li>Truncated file (insufficient data for declared banks)</li>
 *   <li>Corrupted header information</li>
 * </ul>
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
