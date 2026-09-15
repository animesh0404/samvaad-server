package com.samvaad.samvaad_server.user;

import java.util.UUID;

public class UserDeletionConflictException extends RuntimeException {
    public UserDeletionConflictException(UUID userId) {
        super("User cannot be deleted due to concurrent activity: " + userId);
    }
}
