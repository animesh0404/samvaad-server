package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Authenticated replacement of a device's own last-resort Kyber (PQXDH)
 * public material. Public bytes only, standard Base64; the client generates
 * the keypair and retains the secret. Replacement is atomic and versioned by
 * {@code kyberPrekeyId}: re-submitting the identical id with identical bytes
 * is a no-op, reusing an id with different bytes is rejected, and a new id
 * replaces all three columns together. No rotation cadence is defined here.
 */
public class RotateKyberPrekeyRequestDto {

    @NotNull(message = "Kyber prekey ID is required")
    private Integer kyberPrekeyId;

    @NotBlank(message = "Kyber prekey is required")
    private String kyberPrekey;

    @NotBlank(message = "Kyber prekey signature is required")
    private String kyberPrekeySignature;

    public RotateKyberPrekeyRequestDto() {
    }

    public Integer getKyberPrekeyId() {
        return kyberPrekeyId;
    }

    public void setKyberPrekeyId(Integer kyberPrekeyId) {
        this.kyberPrekeyId = kyberPrekeyId;
    }

    public String getKyberPrekey() {
        return kyberPrekey;
    }

    public void setKyberPrekey(String kyberPrekey) {
        this.kyberPrekey = kyberPrekey;
    }

    public String getKyberPrekeySignature() {
        return kyberPrekeySignature;
    }

    public void setKyberPrekeySignature(String kyberPrekeySignature) {
        this.kyberPrekeySignature = kyberPrekeySignature;
    }
}
