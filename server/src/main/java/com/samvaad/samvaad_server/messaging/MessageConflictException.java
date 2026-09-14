package com.samvaad.samvaad_server.messaging;

public class MessageConflictException extends RuntimeException {
    public MessageConflictException(String message) {
        super(message);
    }
}
