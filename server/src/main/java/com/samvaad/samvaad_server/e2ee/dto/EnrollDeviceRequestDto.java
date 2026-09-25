package com.samvaad.samvaad_server.e2ee.dto;

import com.samvaad.samvaad_server.session.ClientPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Public device/key material for enrollment (bootstrap, pending, or
 * recovery). All key fields are standard Base64 of opaque public bytes and
 * carry no private material. Key semantics are not validated
 * cryptographically in this slice.
 */
public class EnrollDeviceRequestDto {

    @NotNull(message = "Registration ID is required")
    private Integer registrationId;

    @NotBlank(message = "Device identity public key is required")
    private String deviceIdentityPublicKey;

    @NotNull(message = "Signed prekey ID is required")
    private Integer signedPrekeyId;

    @NotBlank(message = "Signed prekey is required")
    private String signedPrekey;

    @NotBlank(message = "Signed prekey signature is required")
    private String signedPrekeySignature;

    @NotNull(message = "Client platform is required")
    private ClientPlatform clientPlatform;

    private String clientName;

    private String clientVersion;

    public EnrollDeviceRequestDto() {
    }

    public Integer getRegistrationId() {
        return registrationId;
    }

    public void setRegistrationId(Integer registrationId) {
        this.registrationId = registrationId;
    }

    public String getDeviceIdentityPublicKey() {
        return deviceIdentityPublicKey;
    }

    public void setDeviceIdentityPublicKey(String deviceIdentityPublicKey) {
        this.deviceIdentityPublicKey = deviceIdentityPublicKey;
    }

    public Integer getSignedPrekeyId() {
        return signedPrekeyId;
    }

    public void setSignedPrekeyId(Integer signedPrekeyId) {
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
}
