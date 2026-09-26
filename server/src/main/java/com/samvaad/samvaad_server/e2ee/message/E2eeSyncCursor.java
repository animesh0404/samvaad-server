package com.samvaad.samvaad_server.e2ee.message;

import com.samvaad.samvaad_server.audit.AuditableEntity;
import com.samvaad.samvaad_server.messaging.Conversation;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Per-device synchronization cursor: the highest conversation sequence
 * the recipient device asserts it has durably processed. Monotonic;
 * backwards moves are rejected. Fetching or acknowledging mailbox
 * ciphertext never advances this cursor — only an explicit client
 * assertion does. The server treats the value as a processed marker,
 * never as proof of successful decryption.
 */
@Entity
@Table(name = "e2ee_sync_cursors")
public class E2eeSyncCursor extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "cursor_id")
    private UUID cursorId;

    @Column(name = "recipient_device_id", nullable = false)
    private UUID recipientDeviceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "conversation_id", nullable = false)
    private Conversation conversation;

    @Column(name = "through_sequence", nullable = false)
    private long throughSequence;

    public E2eeSyncCursor() {
    }

    public UUID getCursorId() {
        return cursorId;
    }

    public void setCursorId(UUID cursorId) {
        this.cursorId = cursorId;
    }

    public UUID getRecipientDeviceId() {
        return recipientDeviceId;
    }

    public void setRecipientDeviceId(UUID recipientDeviceId) {
        this.recipientDeviceId = recipientDeviceId;
    }

    public Conversation getConversation() {
        return conversation;
    }

    public void setConversation(Conversation conversation) {
        this.conversation = conversation;
    }

    public long getThroughSequence() {
        return throughSequence;
    }

    public void setThroughSequence(long throughSequence) {
        this.throughSequence = throughSequence;
    }
}
