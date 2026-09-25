package com.samvaad.samvaad_server.e2ee.exception;

public class DeviceNotActiveException extends RuntimeException {
    public DeviceNotActiveException() {
        super("E2EE device is not active");
    }
}
