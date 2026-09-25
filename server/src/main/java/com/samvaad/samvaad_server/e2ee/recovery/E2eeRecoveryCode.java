package com.samvaad.samvaad_server.e2ee.recovery;

import com.samvaad.samvaad_server.audit.AuditableEntity;
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
 * One account-level one-time recovery code (ADR 0018 §4). Recovery codes are
 * account-recovery credentials, not cryptographic keys and not message-history
 * recovery keys. Each code is independently consumable. The server persists
 * only a BCrypt hash ({@code codeHash}); plaintext codes are returned to the
 * client exactly once at generation and are never stored, logged, or
 * retrievable. {@code consumedAt} marks a spent code; {@code supersededAt}
 * marks a code retired by explicit rotation to a fresh set.
 */
@Entity
@Table(name = "e2ee_recovery_codes")
public class E2eeRecoveryCode extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "recovery_code_id")
    private UUID recoveryCodeId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "set_id", nullable = false)
    private UUID setId;

    @Column(name = "code_position", nullable = false)
    private int codePosition;

    @Column(name = "code_hash", nullable = false, length = 72)
    private String codeHash;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "superseded_at")
    private LocalDateTime supersededAt;

    public E2eeRecoveryCode() {
    }

    public boolean isUsable() {
        return consumedAt == null && supersededAt == null;
    }

    public UUID getRecoveryCodeId() {
        return recoveryCodeId;
    }

    public void setRecoveryCodeId(UUID recoveryCodeId) {
        this.recoveryCodeId = recoveryCodeId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public UUID getSetId() {
        return setId;
    }

    public void setSetId(UUID setId) {
        this.setId = setId;
    }

    public int getCodePosition() {
        return codePosition;
    }

    public void setCodePosition(int codePosition) {
        this.codePosition = codePosition;
    }

    public String getCodeHash() {
        return codeHash;
    }

    public void setCodeHash(String codeHash) {
        this.codeHash = codeHash;
    }

    public LocalDateTime getConsumedAt() {
        return consumedAt;
    }

    public void setConsumedAt(LocalDateTime consumedAt) {
        this.consumedAt = consumedAt;
    }

    public LocalDateTime getSupersededAt() {
        return supersededAt;
    }

    public void setSupersededAt(LocalDateTime supersededAt) {
        this.supersededAt = supersededAt;
    }
}
