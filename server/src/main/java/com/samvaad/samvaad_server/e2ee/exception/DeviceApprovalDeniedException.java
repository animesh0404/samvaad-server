package com.samvaad.samvaad_server.e2ee.exception;

public class DeviceApprovalDeniedException extends RuntimeException {
    public DeviceApprovalDeniedException() {
        super("Device approval denied");
    }
}
