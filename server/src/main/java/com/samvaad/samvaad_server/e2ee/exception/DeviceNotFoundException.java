package com.samvaad.samvaad_server.e2ee.exception;

import java.util.UUID;

public class DeviceNotFoundException extends RuntimeException {
    public DeviceNotFoundException(UUID deviceId) {
        super("E2EE device not found: " + deviceId);
    }
}
