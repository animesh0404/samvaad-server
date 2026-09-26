package com.samvaad.samvaad_server.e2ee.message;

import com.samvaad.samvaad_server.audit.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * Durable per-device ciphertext: the exact opaque bytes one recipient
 * device must receive, plus the frozen classification the sender
 * supplied. Survives mailbox acknowledgement — history is permanent.
 * The server never inspects {@code ciphertext}.
 */
@Entity
@Table(name = "e2ee_envelopes")
public class E2eeEnvelope extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "envelope_id")
    private UUID envelopeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private E2eeMessage message;

    @Column(name = "recipient_device_id", nullable = false)
    private UUID recipientDeviceId;

    @Enumerated(EnumType.STRING)
    @Column(name = "envelope_type", nullable = false, length = 16)
    private E2eeEnvelopeType envelopeType;

    @Column(name = "ciphertext", nullable = false)
    private byte[] ciphertext;

    public E2eeEnvelope() {
    }

    public UUID getEnvelopeId() {
        return envelopeId;
    }

    public void setEnvelopeId(UUID envelopeId) {
        this.envelopeId = envelopeId;
    }

    public E2eeMessage getMessage() {
        return message;
    }

    public void setMessage(E2eeMessage message) {
        this.message = message;
    }

    public UUID getRecipientDeviceId() {
        return recipientDeviceId;
    }

    public void setRecipientDeviceId(UUID recipientDeviceId) {
        this.recipientDeviceId = recipientDeviceId;
    }

    public E2eeEnvelopeType getEnvelopeType() {
        return envelopeType;
    }

    public void setEnvelopeType(E2eeEnvelopeType envelopeType) {
        this.envelopeType = envelopeType;
    }

    public byte[] getCiphertext() {
        return ciphertext;
    }

    public void setCiphertext(byte[] ciphertext) {
        this.ciphertext = ciphertext;
    }
}
