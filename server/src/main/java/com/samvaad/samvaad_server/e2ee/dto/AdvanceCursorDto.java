package com.samvaad.samvaad_server.e2ee.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

public class AdvanceCursorDto {

    @NotNull(message = "Conversation ID is required")
    private UUID conversationId;

    @Min(value = 0, message = "Sequence must be >= 0")
    private long throughSequence;

    public AdvanceCursorDto() {
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public long getThroughSequence() {
        return throughSequence;
    }

    public void setThroughSequence(long throughSequence) {
        this.throughSequence = throughSequence;
    }
}
