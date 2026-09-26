package com.samvaad.samvaad_server.e2ee.exception;

import java.util.UUID;

public class E2eeMessageConflictException extends RuntimeException {
    public E2eeMessageConflictException(UUID requestId) {
        super("Encrypted message request ID already used with different content: " + requestId);
    }

    public E2eeMessageConflictException(String detail) {
        super(detail);
    }
}
