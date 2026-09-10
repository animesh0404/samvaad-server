package com.samvaad.samvaad_server.session;

import com.samvaad.samvaad_server.audit.AuditableEntity;
import com.samvaad.samvaad_server.user.User;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sessions")
public class Session extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "session_id")
    private UUID sessionId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 128)
    private String refreshTokenHash;

    @Column(name = "refresh_token_expires_at", nullable = false)
    private LocalDateTime refreshTokenExpiresAt;

    @Column(name = "installation_id", nullable = false, length = 255)
    private String installationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_platform", nullable = false, length = 16)
    private ClientPlatform clientPlatform;

    @Column(name = "client_name", length = 255)
    private String clientName;

    @Column(name = "client_version", length = 64)
    private String clientVersion;

    @Column(name = "last_seen_ip", length = 45)
    private String lastSeenIp;

    @Column(name = "last_seen_user_agent", length = 512)
    private String lastSeenUserAgent;

    @Column(name = "last_authenticated_at", nullable = false)
    private LocalDateTime lastAuthenticatedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 32)
    private RevocationReason revocationReason;

    public Session() {}

    public UUID getSessionId() {
        return sessionId;
    }

    public void setSessionId(UUID sessionId) {
        this.sessionId = sessionId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public String getRefreshTokenHash() {
        return refreshTokenHash;
    }

    public void setRefreshTokenHash(String refreshTokenHash) {
        this.refreshTokenHash = refreshTokenHash;
    }

    public LocalDateTime getRefreshTokenExpiresAt() {
        return refreshTokenExpiresAt;
    }

    public void setRefreshTokenExpiresAt(LocalDateTime refreshTokenExpiresAt) {
        this.refreshTokenExpiresAt = refreshTokenExpiresAt;
    }

    public String getInstallationId() {
        return installationId;
    }

    public void setInstallationId(String installationId) {
        this.installationId = installationId;
    }

    public ClientPlatform getClientPlatform() {
        return clientPlatform;
    }

    public void setClientPlatform(ClientPlatform clientPlatform) {
        this.clientPlatform = clientPlatform;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getClientVersion() {
        return clientVersion;
    }

    public void setClientVersion(String clientVersion) {
        this.clientVersion = clientVersion;
    }

    public String getLastSeenIp() {
        return lastSeenIp;
    }

    public void setLastSeenIp(String lastSeenIp) {
        this.lastSeenIp = lastSeenIp;
    }

    public String getLastSeenUserAgent() {
        return lastSeenUserAgent;
    }

    public void setLastSeenUserAgent(String lastSeenUserAgent) {
        this.lastSeenUserAgent = lastSeenUserAgent;
    }

    public LocalDateTime getLastAuthenticatedAt() {
        return lastAuthenticatedAt;
    }

    public void setLastAuthenticatedAt(LocalDateTime lastAuthenticatedAt) {
        this.lastAuthenticatedAt = lastAuthenticatedAt;
    }

    public LocalDateTime getRevokedAt() {
        return revokedAt;
    }

    public void setRevokedAt(LocalDateTime revokedAt) {
        this.revokedAt = revokedAt;
    }

    public RevocationReason getRevocationReason() {
        return revocationReason;
    }

    public void setRevocationReason(RevocationReason revocationReason) {
        this.revocationReason = revocationReason;
    }
}
