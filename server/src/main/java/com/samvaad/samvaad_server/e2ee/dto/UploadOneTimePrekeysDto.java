package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.constraints.NotNull;

import java.util.List;

/**
 * Initial provisioning or replenishment upload. The batch must contain
 * exactly the policy batch size ({@code E2EE} 100); availability
 * reconciliation is owned by the service.
 */
public class UploadOneTimePrekeysDto {

    @NotNull(message = "One-time prekeys are required")
    private List<OneTimePrekeyDto> prekeys;

    public UploadOneTimePrekeysDto() {
    }

    public UploadOneTimePrekeysDto(List<OneTimePrekeyDto> prekeys) {
        this.prekeys = prekeys;
    }

    public List<OneTimePrekeyDto> getPrekeys() {
        return prekeys;
    }

    public void setPrekeys(List<OneTimePrekeyDto> prekeys) {
        this.prekeys = prekeys;
    }
}
