package com.samvaad.samvaad_server.e2ee.device;

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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * One public one-time prekey belonging to an enrolled E2EE device. A row with
 * a null {@code consumedAt} is available for asynchronous session
 * establishment; a row is consumed exactly once by atomically setting
 * {@code consumedAt} (idempotent replays resolve by
 * {@code consumedByRequestId}). Private prekey material stays exclusively on
 * the client device; this entity stores only the public key.
 */
@Entity
@Table(name = "e2ee_one_time_prekeys")
public class E2eeOneTimePrekey extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "one_time_prekey_id")
    private UUID oneTimePrekeyId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "device_id", nullable = false)
    private E2eeDevice device;

    @Column(name = "prekey_id", nullable = false)
    private int prekeyId;

    @Column(name = "public_key", nullable = false)
    private byte[] publicKey;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "consumed_by_request_id")
    private UUID consumedByRequestId;

    public E2eeOneTimePrekey() {
    }

    public boolean isAvailable() {
        return consumedAt == null;
    }

    public UUID getOneTimePrekeyId() {
        return oneTimePrekeyId;
    }

    public void setOneTimePrekeyId(UUID oneTimePrekeyId) {
        this.oneTimePrekeyId = oneTimePrekeyId;
    }

    public E2eeDevice getDevice() {
        return device;
    }

    public void setDevice(E2eeDevice device) {
        this.device = device;
    }

    public int getPrekeyId() {
        return prekeyId;
    }

    public void setPrekeyId(int prekeyId) {
        this.prekeyId = prekeyId;
    }

    public byte[] getPublicKey() {
        return publicKey;
    }

    public void setPublicKey(byte[] publicKey) {
        this.publicKey = publicKey;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }

    public UUID getConsumedByRequestId() {
        return consumedByRequestId;
    }

    public void setConsumedByRequestId(UUID consumedByRequestId) {
        this.consumedByRequestId = consumedByRequestId;
    }
}
