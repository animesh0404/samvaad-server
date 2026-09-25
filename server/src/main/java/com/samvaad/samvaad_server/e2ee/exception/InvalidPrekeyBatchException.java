package com.samvaad.samvaad_server.e2ee.exception;

public class InvalidPrekeyBatchException extends RuntimeException {
    public InvalidPrekeyBatchException(String detail) {
        super("Invalid one-time prekey batch: " + detail);
    }
}
