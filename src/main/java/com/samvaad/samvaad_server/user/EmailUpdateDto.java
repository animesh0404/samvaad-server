package com.samvaad.samvaad_server.user;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public class EmailUpdateDto {

    @NotBlank
    @Email
    @Size(max = 320)
    private String email;

    public EmailUpdateDto() {
    }

    public EmailUpdateDto(String email) {
        this.email = email;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}
