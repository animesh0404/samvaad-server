package com.samvaad.samvaad_server.e2ee.dto;

import java.util.UUID;

/**
 * Optional idempotency key for a one-time-prekey claim. Replaying the same
 * request returns the same consumed prekey without consuming another.
 */
public class ClaimPrekeyRequestDto {

    private UUID requestId;

    public ClaimPrekeyRequestDto() {
    }

    public ClaimPrekeyRequestDto(UUID requestId) {
        this.requestId = requestId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
    }
}
