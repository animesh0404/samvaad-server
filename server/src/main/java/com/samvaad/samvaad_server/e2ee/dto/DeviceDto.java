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
    private String deviceIdentityPublicKey;
    private int signedPrekeyId;
    private DeviceStatus status;
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
