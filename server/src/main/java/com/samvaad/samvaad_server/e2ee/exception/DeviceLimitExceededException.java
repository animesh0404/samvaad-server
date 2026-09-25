package com.samvaad.samvaad_server.e2ee.exception;

public class DeviceLimitExceededException extends RuntimeException {
    public DeviceLimitExceededException() {
        super("Maximum number of enrolled E2EE devices reached");
    }
}
