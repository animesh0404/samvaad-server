package com.samvaad.samvaad_server.e2ee.dto;

import java.util.UUID;

public class SyncCursorDto {

    private UUID conversationId;
    private long throughSequence;

    public SyncCursorDto() {
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
