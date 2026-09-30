package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * One recipient device's ciphertext within a logical message. All keying
 * is by Samvaad UUID; {@code envelopeType} is one of {@code PREKEY_INIT},
 * {@code RATCHET}; {@code ciphertext} is standard Base64 of the opaque
 * Signal message bytes, never inspected server-side.
 */
public class E2eeEnvelopeSubmitDto {

    @NotNull(message = "Sender device ID is required")
    private UUID senderDeviceId;

    @NotNull(message = "Recipient device ID is required")
    private UUID recipientDeviceId;

    @NotBlank(message = "Envelope type is required")
    private String envelopeType;

    /**
     * Early transport guard only: rejects obviously oversized Base64 fields
     * before decode allocation. 65,536 decoded bytes encode to at most 87,384
     * Base64 characters; the decoded-byte limit enforced by the service is
     * authoritative.
     */
    @NotBlank(message = "Ciphertext is required")
    @Size(max = 88_000, message = "Ciphertext exceeds the transport bound")
    private String ciphertext;

    public E2eeEnvelopeSubmitDto() {
    }

    public UUID getSenderDeviceId() {
        return senderDeviceId;
    }

    public void setSenderDeviceId(UUID senderDeviceId) {
        this.senderDeviceId = senderDeviceId;
    }

    public UUID getRecipientDeviceId() {
        return recipientDeviceId;
    }

    public void setRecipientDeviceId(UUID recipientDeviceId) {
        this.recipientDeviceId = recipientDeviceId;
    }

    public String getEnvelopeType() {
        return envelopeType;
    }

    public void setEnvelopeType(String envelopeType) {
        this.envelopeType = envelopeType;
    }

    public String getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(String ciphertext) {
        this.ciphertext = ciphertext;
    }
}
