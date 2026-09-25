package com.samvaad.samvaad_server.e2ee.exception;

public class DeviceAlreadyExistsException extends RuntimeException {
    public DeviceAlreadyExistsException() {
        super("E2EE device identity already enrolled");
    }
}
