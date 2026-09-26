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
    /** Server-assigned Signal integer id for address construction (see PART C). */
    private int signalDeviceId;
    private String deviceIdentityPublicKey;
    private int signedPrekeyId;
    private String signedPrekey;
    private String signedPrekeySignature;
    private OneTimePrekeyDto oneTimePrekey;
    /**
     * Last-resort Kyber (PQXDH) public material. Always present for
     * Kyber-capable devices and never consumed by the EC one-time-prekey
     * claim: repeated claims (including requestId replays) return the
     * identical Kyber material.
     */
    private Integer kyberPrekeyId;
    private String kyberPrekey;
    private String kyberPrekeySignature;

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

    public OneTimePrekeyDto getOneTimePrekey() {
        return oneTimePrekey;
    }

    public void setOneTimePrekey(OneTimePrekeyDto oneTimePrekey) {
        this.oneTimePrekey = oneTimePrekey;
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
