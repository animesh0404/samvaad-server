package com.samvaad.samvaad_server.e2ee.dto;

import java.util.UUID;

/**
 * Full bundle for one recipient device returned by a claim. Contains at most
 * one consumed one-time prekey; when the pool is empty the prekey is absent
 * and the sender falls back to the signed prekey.
 */
public class ClaimPrekeyResponseDto {

    private UUID deviceId;
    private int registrationId;
    private String deviceIdentityPublicKey;
    private int signedPrekeyId;
    private String signedPrekey;
    private String signedPrekeySignature;
    private OneTimePrekeyDto oneTimePrekey;

    public ClaimPrekeyResponseDto() {
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

    public OneTimePrekeyDto getOneTimePrekey() {
        return oneTimePrekey;
    }

    public void setOneTimePrekey(OneTimePrekeyDto oneTimePrekey) {
        this.oneTimePrekey = oneTimePrekey;
    }
}
