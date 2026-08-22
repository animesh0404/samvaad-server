package com.samvaad.samvaad_server.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class UserDto {

    private UUID userId;

    @NotBlank
    @Size(min = 3, max = 32)
    @Pattern(regexp = "[a-zA-Z0-9_]+")
    private String username;

    public UserDto() { }

    public UserDto(UUID userId, String username) {
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