package com.samvaad.samvaad_server.user;

import java.util.UUID;

public class UserLookupDto {

    private UUID userId;

    private String username;

    public UserLookupDto() {
    }

    public UserLookupDto(UUID userId, String username) {
        this.userId = userId;
        this.username = username;
    }

    public UUID getUserId() {
        return userId;
    }

    public void setUserId(UUID userId) {
        this.userId = userId;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
