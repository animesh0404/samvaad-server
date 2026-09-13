package com.samvaad.samvaad_server.friendrequest;

public class FriendRequestConflictException extends RuntimeException {
    public FriendRequestConflictException(String message) {
        super(message);
    }
}
