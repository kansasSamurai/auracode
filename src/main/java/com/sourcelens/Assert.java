package com.sourcelens;

import java.nio.file.Path;

/**
 * Fail-fast input validation utilities.
 *
 * Each method throws {@link IllegalArgumentException} if the condition is not met,
 * with the supplied message as the exception detail. Use at command boundaries to
 * validate flags and preconditions before any business logic runs.
 */
public final class Assert {

    private Assert() {}

    /** Throws if {@code condition} is false. */
    public static void isTrue(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }

    /** Throws if {@code value} is null. */
    public static void notNull(Object value, String message) {
        if (value == null) throw new IllegalArgumentException(message);
    }

    /** Throws if {@code path} is null or does not point to an existing directory. */
    public static void isDirectory(Path path, String message) {
        if (path == null || !path.toFile().isDirectory()) {
            throw new IllegalArgumentException(message);
        }
    }

    /** Throws if {@code path} is null or does not point to an existing file. */
    public static void fileExists(Path path, String message) {
        if (path == null || !path.toFile().exists()) {
            throw new IllegalArgumentException(message);
        }
    }
}
