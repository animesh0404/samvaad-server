package com.samvaad.samvaad_server.user.userprofile;

import java.util.UUID;

public class UserProfileNotFoundException extends RuntimeException {

    public UserProfileNotFoundException(UUID userId) {
        super("User profile not found: " + userId);
    }
}