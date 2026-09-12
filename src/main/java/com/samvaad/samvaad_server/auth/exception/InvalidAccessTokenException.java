package com.samvaad.samvaad_server.auth.exception;

public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException(String message) {
        super(message);
    }

    public InvalidAccessTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}