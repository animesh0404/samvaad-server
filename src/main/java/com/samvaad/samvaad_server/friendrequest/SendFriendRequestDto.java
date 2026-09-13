package com.samvaad.samvaad_server.friendrequest;

import jakarta.validation.constraints.NotBlank;

public class SendFriendRequestDto {

    @NotBlank
    private String username;

    public SendFriendRequestDto() {
    }

    public SendFriendRequestDto(String username) {
        this.username = username;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }
}
