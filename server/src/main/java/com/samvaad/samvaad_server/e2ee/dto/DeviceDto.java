package com.samvaad.samvaad_server.e2ee.dto;

import com.samvaad.samvaad_server.e2ee.device.DeviceStatus;
import com.samvaad.samvaad_server.session.ClientPlatform;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Owner's view of one enrolled device. Carries the available one-time-prekey
 * count so the device client can replenish below the policy threshold.
 * Exposes public key material only.
 */
public class DeviceDto {

    private UUID deviceId;
    private int registrationId;
    /**
     * Server-assigned Signal integer device id (PART C address semantics:
     * Signal address = owning userId + this integer). Stable for the
     * lifetime of the device row; never reused after revocation.
     */
    private int signalDeviceId;
    private String deviceIdentityPublicKey;
    private int signedPrekeyId;
    private DeviceStatus status;
    /**
     * Owner's own last-resort Kyber (PQXDH) public material. Public bytes
     * only; null for rows enrolled before Kyber support.
     */
    private Integer kyberPrekeyId;
    private String kyberPrekey;
    private String kyberPrekeySignature;
    private ClientPlatform clientPlatform;
    private String clientName;
    private String clientVersion;
    private long availablePrekeys;
    private LocalDateTime createdAt;
    private LocalDateTime lastActiveAt;

    public DeviceDto() {
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public int getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(int registrationId) {
        this.registrationId = registrationId;
    }

    public int getSignalDeviceId() {
        return signalDeviceId;
    }

    public void setSignalDeviceId(int signalDeviceId) {
        this.signalDeviceId = signalDeviceId;
    }

    public String getDeviceIdentityPublicKey() {
        return deviceIdentityPublicKey;
    }

    public void setDeviceIdentityPublicKey(String deviceIdentityPublicKey) {
        this.deviceIdentityPublicKey = deviceIdentityPublicKey;
    }

    public int getSignedPrekeyId() {
        return signedPrekeyId;
    }

    public void setSignedPrekeyId(int signedPrekeyId) {
        this.signedPrekeyId = signedPrekeyId;
    }

    public Integer getKyberPrekeyId() {
        return kyberPrekeyId;
    }

    public void setKyberPrekeyId(Integer kyberPrekeyId) {
        this.kyberPrekeyId = kyberPrekeyId;
    }

    public String getKyberPrekey() {
        return kyberPrekey;
    }

    public void setKyberPrekey(String kyberPrekey) {
        this.kyberPrekey = kyberPrekey;
    }

    public String getKyberPrekeySignature() {
        return kyberPrekeySignature;
    }

    public void setKyberPrekeySignature(String kyberPrekeySignature) {
        this.kyberPrekeySignature = kyberPrekeySignature;
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

    public long getAvailablePrekeys() {
        return availablePrekeys;
    }

    public void setAvailablePrekeys(long availablePrekeys) {
        this.availablePrekeys = availablePrekeys;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getLastActiveAt() {
        return lastActiveAt;
    }

    public void setLastActiveAt(LocalDateTime lastActiveAt) {
        this.lastActiveAt = lastActiveAt;
    }
}
