package com.samvaad.samvaad_server.user.userprofile;

import com.samvaad.samvaad_server.security.AuthenticatedUser;
import com.samvaad.samvaad_server.user.UserRole;

import java.util.UUID;

public final class ProfileAccessPolicy {

    private ProfileAccessPolicy() {
    }

    public static boolean canRead(AuthenticatedUser caller, UUID targetUserId) {
        return caller.userId().equals(targetUserId)
                || caller.role() == UserRole.ADMIN;
    }

    public static boolean canWrite(AuthenticatedUser caller, UUID targetUserId) {
        return caller.userId().equals(targetUserId);
    }
}