package com.samvaad.samvaad_server.e2ee.message;

import com.samvaad.samvaad_server.audit.AuditableEntity;
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
 * Undelivered-ciphertext pointer for one recipient device. Acknowledgement
 * deletes the entry only; the referenced message and envelope (durable
 * history) are untouched. Delivery state is independent per device.
 */
@Entity
@Table(name = "e2ee_mailbox")
public class E2eeMailboxEntry extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "entry_id")
    private UUID entryId;

    @Column(name = "recipient_device_id", nullable = false)
    private UUID recipientDeviceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private E2eeMessage message;

    public E2eeMailboxEntry() {
    }

    public UUID getEntryId() {
        return entryId;
    }

    public void setEntryId(UUID entryId) {
        this.entryId = entryId;
    }

    public UUID getRecipientDeviceId() {
        return recipientDeviceId;
    }

    public void setRecipientDeviceId(UUID recipientDeviceId) {
        this.recipientDeviceId = recipientDeviceId;
    }

    public E2eeMessage getMessage() {
        return message;
    }

    public void setMessage(E2eeMessage message) {
        this.message = message;
    }
}
