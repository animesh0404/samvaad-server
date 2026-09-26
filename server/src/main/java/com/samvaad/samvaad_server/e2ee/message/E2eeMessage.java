package com.samvaad.samvaad_server.e2ee.message;

import com.samvaad.samvaad_server.audit.AuditableEntity;
import com.samvaad.samvaad_server.messaging.Conversation;
import com.samvaad.samvaad_server.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One logical encrypted message: a single {@code messageRequestId} from one
 * sender device, carrying one opaque ciphertext envelope per recipient
 * device. No plaintext, no Signal-parsed fields — only routing, ordering,
 * and idempotency metadata alongside the per-device envelopes.
 */
@Entity
@Table(name = "e2ee_messages")
public class E2eeMessage extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "message_id")
    private UUID messageId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sender_user_id", nullable = false)
    private User sender;

    @Column(name = "sender_device_id", nullable = false)
    private UUID senderDeviceId;

    @Column(name = "request_id", nullable = false, unique = true)
    private UUID requestId;

    @Column(name = "sequence_number", nullable = false)
    private long sequenceNumber;

    @Column(name = "server_timestamp", nullable = false)
    private LocalDateTime serverTimestamp;

    public E2eeMessage() {
    }

    public UUID getMessageId() {
        return messageId;
    }

    public void setMessageId(UUID messageId) {
        this.messageId = messageId;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public User getSender() {
        return sender;
    }

    public void setSender(User sender) {
        this.sender = sender;
    }

    public UUID getSenderDeviceId() {
        return senderDeviceId;
    }

    public void setSenderDeviceId(UUID senderDeviceId) {
        this.senderDeviceId = senderDeviceId;
    }

    public UUID getRequestId() {
        return requestId;
    }

    public void setRequestId(UUID requestId) {
        this.requestId = requestId;
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
}
