package com.samvaad.samvaad_server.e2ee.dto;

import java.util.UUID;

/**
 * Recipient-side entry in the public device directory: the public bundle a
 * sender needs for asynchronous session establishment. Shows only whether a
 * one-time prekey is available, never the pool count or bodies.
 */
public class RecipientDeviceDto {

    private UUID deviceId;
    private int registrationId;
    private String deviceIdentityPublicKey;
    private int signedPrekeyId;
    private String signedPrekey;
    private String signedPrekeySignature;
    private boolean hasAvailableOneTimePrekey;

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
}
