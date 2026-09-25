package com.samvaad.samvaad_server.e2ee.exception;

/**
 * A session may create a new E2EE device only when it is not already bound
 * to another device. Rebinding an already-bound session would orphan the
 * previously bound device, leaving it ACTIVE but permanently unmanageable.
 */
public class SessionAlreadyBoundException extends RuntimeException {
    public SessionAlreadyBoundException() {
        super("Session is already bound to an E2EE device");
    }
}
