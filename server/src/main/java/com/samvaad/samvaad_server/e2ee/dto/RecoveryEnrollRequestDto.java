package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Recovery enrollment: one unused recovery code plus the new device's public
 * material. Creates a NEW device identity; never resurrects a revoked/lost
 * one. The recovery code is consumed atomically with device creation.
 */
public class RecoveryEnrollRequestDto {

    @NotBlank(message = "Recovery code is required")
    private String recoveryCode;

    private EnrollDeviceRequestDto device;

    public RecoveryEnrollRequestDto() {
    }

    public String getRecoveryCode() {
        return recoveryCode;
    }

    public void setRecoveryCode(String recoveryCode) {
        this.recoveryCode = recoveryCode;
    }

    public EnrollDeviceRequestDto getDevice() {
        return device;
    }

    public void setDevice(EnrollDeviceRequestDto device) {
        this.device = device;
    }
}
