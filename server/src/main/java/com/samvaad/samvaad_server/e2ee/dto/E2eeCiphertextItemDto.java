package com.samvaad.samvaad_server.e2ee.dto;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One device's ciphertext item, shared by mailbox fetch and history
 * reads. Mailbox rows come from undelivered state; history rows come
 * from durable envelopes — the shape is identical, the source differs.
 */
public class E2eeCiphertextItemDto {

    private UUID messageId;
    private UUID conversationId;
    private long sequenceNumber;
    private UUID senderUserId;
    private UUID senderDeviceId;
    private String envelopeType;
    private String ciphertext;
    private LocalDateTime serverTimestamp;

    public E2eeCiphertextItemDto() {
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

    public UUID getSenderUserId() {
        return senderUserId;
    }

    public void setSenderUserId(UUID senderUserId) {
        this.senderUserId = senderUserId;
    }

    public UUID getSenderDeviceId() {
        return senderDeviceId;
    }

    public void setSenderDeviceId(UUID senderDeviceId) {
        this.senderDeviceId = senderDeviceId;
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

    public LocalDateTime getServerTimestamp() {
        return serverTimestamp;
    }

    public void setServerTimestamp(LocalDateTime serverTimestamp) {
        this.serverTimestamp = serverTimestamp;
    }
}
