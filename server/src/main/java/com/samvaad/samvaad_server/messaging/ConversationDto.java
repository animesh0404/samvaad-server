package com.samvaad.samvaad_server.messaging;

import java.time.LocalDateTime;
import java.util.UUID;

public class ConversationDto {

    private UUID conversationId;

    private UUID otherParticipantUserId;

    private String otherParticipantUsername;

    private long lastSequenceNumber;

    private LocalDateTime updatedAt;

    public ConversationDto() {
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public UUID getOtherParticipantUserId() {
        return otherParticipantUserId;
    }

    public void setOtherParticipantUserId(UUID otherParticipantUserId) {
        this.otherParticipantUserId = otherParticipantUserId;
    }

    public String getOtherParticipantUsername() {
        return otherParticipantUsername;
    }

    public void setOtherParticipantUsername(String otherParticipantUsername) {
        this.otherParticipantUsername = otherParticipantUsername;
    }

    public long getLastSequenceNumber() {
        return lastSequenceNumber;
    }

    public void setLastSequenceNumber(long lastSequenceNumber) {
        this.lastSequenceNumber = lastSequenceNumber;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
