package com.samvaad.samvaad_server.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "samvaad.bootstrap.admin")
public record BootstrapAdminProperties(String username, String password, String email) {

    public BootstrapAdminProperties {
        if (username == null) {
            username = "";
        }
        if (password == null) {
            password = "";
        }
        if (email == null || email.isBlank()) {
            email = null;
        }
    }

    public boolean hasCredentials() {
        return !username.isBlank() && !password.isBlank();
    }
}