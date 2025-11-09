package com.github.dimiro1.mynes.nestests;

/**
 * Exception thrown when the nestest log file cannot be parsed.
 * <p>
 * This typically indicates that the log file format doesn't match the expected
 * nestest.log format, or the file is corrupted.
 */
public class NestestLogParseException extends RuntimeException {
    /**
     * Creates a new parse exception with the line that failed to parse.
     *
     * @param line the log line that could not be parsed
     */
    public NestestLogParseException(final String line) {
        super("Failed to parse nestest log line: " + line);
    }

    /**
     * Creates a new parse exception with a cause.
     *
     * @param line the log line that could not be parsed
     * @param cause the underlying exception
     */
    public NestestLogParseException(final String line, final Throwable cause) {
        super("Failed to parse nestest log line: " + line, cause);
    }
}
