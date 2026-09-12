package com.samvaad.samvaad_server.user;

import jakarta.validation.constraints.NotBlank;

public class PasswordUpdateDto {

    @NotBlank
    private String currentPassword;

    @NotBlank
    private String newPassword;

    public PasswordUpdateDto() {
    }

    public PasswordUpdateDto(String currentPassword, String newPassword) {
        this.currentPassword = currentPassword;
        this.newPassword = newPassword;
    }

    public String getCurrentPassword() {
        return currentPassword;
    }

    public void setCurrentPassword(String currentPassword) {
        this.currentPassword = currentPassword;
    }

    public String getNewPassword() {
        return newPassword;
    }

    public void setNewPassword(String newPassword) {
        this.newPassword = newPassword;
    }
}
