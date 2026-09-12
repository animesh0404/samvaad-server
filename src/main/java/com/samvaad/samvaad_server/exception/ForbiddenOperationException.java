package com.samvaad.samvaad_server.exception;

public class ForbiddenOperationException extends RuntimeException {

    public ForbiddenOperationException() {
        super("Forbidden");
    }
}