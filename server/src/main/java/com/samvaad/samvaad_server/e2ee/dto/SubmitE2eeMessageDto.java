package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * One logical encrypted message: a client-generated idempotency key plus
 * one opaque per-recipient-device envelope. Mirrors the frozen client
 * {@code OutboundEnvelope} contract minus format/suite (client-contract
 * concerns the server never interprets).
 */
public class SubmitE2eeMessageDto {

    @NotNull(message = "Message request ID is required")
    private UUID messageRequestId;

    @NotEmpty(message = "At least one envelope is required")
    private List<@Valid E2eeEnvelopeSubmitDto> envelopes;

    public SubmitE2eeMessageDto() {
    }

    public UUID getMessageRequestId() {
        return messageRequestId;
    }

    public void setMessageRequestId(UUID messageRequestId) {
        this.messageRequestId = messageRequestId;
    }

    public List<E2eeEnvelopeSubmitDto> getEnvelopes() {
        return envelopes;
    }

    public void setEnvelopes(List<E2eeEnvelopeSubmitDto> envelopes) {
        this.envelopes = envelopes;
    }
}
