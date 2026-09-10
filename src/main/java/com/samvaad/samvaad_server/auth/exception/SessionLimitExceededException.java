package com.samvaad.samvaad_server.auth.exception;

public class SessionLimitExceededException extends RuntimeException {
    public SessionLimitExceededException() {
        super("Invalid credentials");
    }

    public SessionLimitExceededException(String message) {
        super(message);
    }
}
