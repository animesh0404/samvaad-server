package com.samvaad.samvaad_server.e2ee.exception;

/**
 * A presented recovery code is wrong, already consumed, superseded, or no
 * usable code exists. The message is deliberately generic so failure does
 * not disclose which case occurred.
 */
public class InvalidRecoveryCodeException extends RuntimeException {
    public InvalidRecoveryCodeException() {
        super("Invalid recovery code");
    }
}
