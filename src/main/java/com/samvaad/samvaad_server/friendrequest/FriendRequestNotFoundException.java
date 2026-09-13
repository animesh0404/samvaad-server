package com.samvaad.samvaad_server.friendrequest;

import java.util.UUID;

public class FriendRequestNotFoundException extends RuntimeException {
    public FriendRequestNotFoundException(UUID requestId) {
        super("Friend request not found: " + requestId);
    }
}
