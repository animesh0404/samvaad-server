package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Existing-device recovery rebind request ({@code POST
 * /api/e2ee/devices/{deviceId}/bind} body). Carries exactly one usable
 * recovery code; no key material of any kind is accepted here.
 */
public class BindDeviceRequestDto {

    @NotBlank(message = "Recovery code is required")
    private String recoveryCode;

    public BindDeviceRequestDto() {
    }

    public String getRecoveryCode() {
        return recoveryCode;
    }

    public void setRecoveryCode(String recoveryCode) {
        this.recoveryCode = recoveryCode;
    }
}
