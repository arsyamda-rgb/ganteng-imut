package com.veyrion.character.database;

/** Unchecked wrapper for database failures so they can travel through {@code CompletableFuture}s. */
public class DatabaseException extends RuntimeException {

    public DatabaseException(String message) {
        super(message);
    }

    public DatabaseException(String message, Throwable cause) {
        super(message, cause);
    }
}
