package com.samvaad.samvaad_server.security;

import com.samvaad.samvaad_server.user.UserRole;

import java.util.UUID;

public record AuthenticatedUser(UUID userId, UserRole role, UUID sessionId) {
}