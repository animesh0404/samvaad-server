package com.samvaad.samvaad_server.user;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public class CreateUserRequestDto {

    @NotBlank
    @Size(min = 3, max = 32)
    @Pattern(regexp = "[a-zA-Z0-9_]+")
    private String username;

    @NotBlank
    private String password;

    private String email;

    public CreateUserRequestDto() {
    }

    public CreateUserRequestDto(String username, String password, String email) {
        this.username = username;
        this.password = password;
        this.email = email;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}