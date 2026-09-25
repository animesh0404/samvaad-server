package com.samvaad.samvaad_server.e2ee.exception;

/**
 * An idempotent claim request identifier is already attached to a different
 * consumption. The sender must retry with a fresh request identifier.
 */
public class PrekeyClaimConflictException extends RuntimeException {
    public PrekeyClaimConflictException() {
        super("One-time prekey claim conflict: request identifier already used");
    }
}
