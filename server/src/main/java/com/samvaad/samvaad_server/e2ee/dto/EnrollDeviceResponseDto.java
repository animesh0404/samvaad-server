package com.samvaad.samvaad_server.e2ee.dto;

import java.util.List;

/**
 * Enrollment outcome. {@code recoveryCodes} is present ONLY on a successful
 * first-device bootstrap, carries the plaintext set exactly once, and is
 * never returned by any other endpoint.
 */
public class EnrollDeviceResponseDto {

    private DeviceDto device;
    private EnrollmentState enrollmentState;
    private List<String> recoveryCodes;

    public EnrollDeviceResponseDto() {
    }

    public DeviceDto getDevice() {
        return device;
    }

    public void setDevice(DeviceDto device) {
        this.device = device;
    }

    public EnrollmentState getEnrollmentState() {
        return enrollmentState;
    }

    public void setEnrollmentState(EnrollmentState enrollmentState) {
        this.enrollmentState = enrollmentState;
    }

    public List<String> getRecoveryCodes() {
        return recoveryCodes;
    }

    public void setRecoveryCodes(List<String> recoveryCodes) {
        this.recoveryCodes = recoveryCodes;
    }
}
