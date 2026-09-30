package com.samvaad.samvaad_server.e2ee.dto;

import java.util.UUID;

import com.samvaad.samvaad_server.e2ee.device.DeviceRole;

/**
 * Recipient-side entry in the public device directory: the public bundle a
 * sender needs for asynchronous session establishment. Shows only whether a
 * one-time prekey is available, never the pool count or bodies.
 */
public class RecipientDeviceDto {

    private UUID deviceId;
    private int registrationId;
    /** Server-assigned Signal integer id for address construction (see PART C). */
    private int signalDeviceId;
    private String deviceIdentityPublicKey;
    private int signedPrekeyId;
    private String signedPrekey;
    private String signedPrekeySignature;
    private boolean hasAvailableOneTimePrekey;
    /**
     * Server-assigned authority role (ADR 0025). Read-only directory
     * metadata; senders use it to distinguish the history-authoritative
     * PRIMARY from COMPANIONS.
     */
    private DeviceRole deviceRole;
    /**
     * Last-resort Kyber (PQXDH) public material. Null only for device rows
     * enrolled before Kyber support; such rows cannot serve PQXDH bundles
     * until the owner replaces the material.
     */
    private Integer kyberPrekeyId;
    private String kyberPrekey;
    private String kyberPrekeySignature;

    public RecipientDeviceDto() {
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

    public String getSignedPrekey() {
        return signedPrekey;
    }

    public void setSignedPrekey(String signedPrekey) {
        this.signedPrekey = signedPrekey;
    }

    public String getSignedPrekeySignature() {
        return signedPrekeySignature;
    }

    public void setSignedPrekeySignature(String signedPrekeySignature) {
        this.signedPrekeySignature = signedPrekeySignature;
    }

    public boolean isHasAvailableOneTimePrekey() {
        return hasAvailableOneTimePrekey;
    }

    public void setHasAvailableOneTimePrekey(boolean hasAvailableOneTimePrekey) {
        this.hasAvailableOneTimePrekey = hasAvailableOneTimePrekey;
    }

    public DeviceRole getDeviceRole() {
        return deviceRole;
    }

    public void setDeviceRole(DeviceRole deviceRole) {
        this.deviceRole = deviceRole;
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
}
