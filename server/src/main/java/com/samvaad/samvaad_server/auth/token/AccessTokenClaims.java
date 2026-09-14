package com.samvaad.samvaad_server.auth.token;

import java.util.UUID;

public record AccessTokenClaims(UUID userId, UUID sessionId) {
}