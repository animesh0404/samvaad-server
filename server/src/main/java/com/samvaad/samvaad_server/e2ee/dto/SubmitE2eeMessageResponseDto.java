package com.samvaad.samvaad_server.e2ee.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class SubmitE2eeMessageResponseDto {

    private UUID messageId;
    private UUID conversationId;
    private long sequenceNumber;
    private LocalDateTime serverTimestamp;
    private List<UUID> acceptedRecipientDevices;
    private boolean createdNew;

    public SubmitE2eeMessageResponseDto() {
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
    }

    public UUID getConversationId() {
        return conversationId;
    }

    public void setConversationId(UUID conversationId) {
        this.conversationId = conversationId;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public void setSequenceNumber(long sequenceNumber) {
        this.sequenceNumber = sequenceNumber;
    }

    public LocalDateTime getServerTimestamp() {
        return serverTimestamp;
    }

    public void setServerTimestamp(LocalDateTime serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }

    public List<UUID> getAcceptedRecipientDevices() {
        return acceptedRecipientDevices;
    }

    public void setAcceptedRecipientDevices(List<UUID> acceptedRecipientDevices) {
        this.acceptedRecipientDevices = acceptedRecipientDevices;
    }

    public boolean isCreatedNew() {
        return createdNew;
    }

    public void setCreatedNew(boolean createdNew) {
        this.createdNew = createdNew;
    }
}
