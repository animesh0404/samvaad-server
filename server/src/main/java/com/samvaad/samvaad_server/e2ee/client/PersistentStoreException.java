package com.samvaad.samvaad_server.e2ee.client;

/**
 * Unchecked failure of the client crypto durability layer: snapshot writes
 * that cannot be made durable, unreadable/corrupt store files, or store
 * format versions without a migration path.
 *
 * <p>Durability failures propagate instead of being masked: callers treat a
 * thrown commit as "nothing in this boundary became durable" and recover by
 * retrying against the unchanged prior state.
 */
public class PersistentStoreException extends RuntimeException {

    public PersistentStoreException(String message) {
        super(message);
    }

    public PersistentStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
