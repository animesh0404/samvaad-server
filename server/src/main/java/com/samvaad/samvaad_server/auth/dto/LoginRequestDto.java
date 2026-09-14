package com.samvaad.samvaad_server.auth.dto;

import com.samvaad.samvaad_server.session.ClientPlatform;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class LoginRequestDto {

    @NotBlank(message = "Identifier must not be blank")
    private String identifier;

    @NotBlank(message = "Password must not be blank")
    private String password;

    // Optional client/device metadata. Null when the client has no installation identity.
    private String installationId;

    @NotNull(message = "Client platform is required")
    private ClientPlatform clientPlatform;

    private String clientName;

    private String clientVersion;

    public LoginRequestDto() {}

    public LoginRequestDto(
            String identifier,
            String password,
            String installationId,
            ClientPlatform clientPlatform,
            String clientName,
            String clientVersion) {
        this.identifier = identifier;
        this.password = password;
        this.installationId = installationId;
        this.clientPlatform = clientPlatform;
        this.clientName = clientName;
        this.clientVersion = clientVersion;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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
}
