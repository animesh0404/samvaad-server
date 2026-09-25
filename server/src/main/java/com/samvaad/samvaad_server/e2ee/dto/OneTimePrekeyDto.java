package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class OneTimePrekeyDto {

    @NotNull(message = "Prekey ID is required")
    private Integer prekeyId;

    @NotBlank(message = "One-time prekey public key is required")
    private String publicKey;

    public OneTimePrekeyDto() {
    }

    public OneTimePrekeyDto(Integer prekeyId, String publicKey) {
        this.prekeyId = prekeyId;
        this.publicKey = publicKey;
    }

    public Integer getPrekeyId() {
        return prekeyId;
    }

    public void setPrekeyId(Integer prekeyId) {
        this.prekeyId = prekeyId;
    }

    public String getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(String publicKey) {
        this.publicKey = publicKey;
    }
}
