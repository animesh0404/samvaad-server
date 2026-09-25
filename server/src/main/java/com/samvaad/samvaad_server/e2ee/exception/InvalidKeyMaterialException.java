package com.samvaad.samvaad_server.e2ee.exception;

public class InvalidKeyMaterialException extends RuntimeException {
    public InvalidKeyMaterialException(String detail) {
        super("Invalid E2EE key material: " + detail);
    }
}
