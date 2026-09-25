package com.samvaad.samvaad_server.e2ee.device;

import com.samvaad.samvaad_server.audit.AuditableEntity;
import com.samvaad.samvaad_server.session.ClientPlatform;
import com.samvaad.samvaad_server.session.RevocationReason;
import com.samvaad.samvaad_server.user.User;
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

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Server-side record of an independent cryptographic E2EE device (ADR 0018).
 *
 * The record holds only PUBLIC cryptographic material: the device identity
 * public key, the public signed prekey and its signature alongside the
 * one-time-prekey pool, enrollment/lifecycle status, and device-management
 * metadata. Private cryptographic material belongs exclusively to the client
 * device and must never cross the API/persistence boundary; this entity has
 * no field capable of holding it.
 */
@Entity
@Table(name = "e2ee_devices")
public class E2eeDevice extends AuditableEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "device_id")
    private UUID deviceId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "registration_id", nullable = false)
    private int registrationId;

    @Column(name = "device_identity_public_key", nullable = false)
    private byte[] deviceIdentityPublicKey;

    @Column(name = "signed_prekey_id", nullable = false)
    private int signedPrekeyId;

    @Column(name = "signed_prekey", nullable = false)
    private byte[] signedPrekey;

    @Column(name = "signed_prekey_signature", nullable = false)
    private byte[] signedPrekeySignature;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private DeviceStatus status = DeviceStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "client_platform", nullable = false, length = 16)
    private ClientPlatform clientPlatform;

    @Column(name = "client_name", length = 255)
    private String clientName;

    @Column(name = "client_version", length = 64)
    private String clientVersion;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "revocation_reason", length = 32)
    private RevocationReason revocationReason;

    @Column(name = "last_active_at")
    private LocalDateTime lastActiveAt;

    public E2eeDevice() {
    }

    public boolean isActive() {
        return status == DeviceStatus.ACTIVE;
    }

    public boolean isRevoked() {
        return status == DeviceStatus.REVOKED;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public int getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(int registrationId) {
        this.registrationId = registrationId;
    }

    public byte[] getDeviceIdentityPublicKey() {
        return deviceIdentityPublicKey;
    }

    public void setDeviceIdentityPublicKey(byte[] deviceIdentityPublicKey) {
        this.deviceIdentityPublicKey = deviceIdentityPublicKey;
    }

    public int getSignedPrekeyId() {
        return signedPrekeyId;
    }

    public void setSignedPrekeyId(int signedPrekeyId) {
        this.signedPrekeyId = signedPrekeyId;
    }

    public byte[] getSignedPrekey() {
        return signedPrekey;
    }

    public void setSignedPrekey(byte[] signedPrekey) {
        this.signedPrekey = signedPrekey;
    }

    public byte[] getSignedPrekeySignature() {
        return signedPrekeySignature;
    }

    public void setSignedPrekeySignature(byte[] signedPrekeySignature) {
        this.signedPrekeySignature = signedPrekeySignature;
    }

    public DeviceStatus getStatus() {
        return status;
    }

    public void setStatus(DeviceStatus status) {
        this.status = status;
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

    public LocalDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(LocalDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
