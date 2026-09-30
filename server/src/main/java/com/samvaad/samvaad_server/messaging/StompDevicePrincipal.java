package com.samvaad.samvaad_server.messaging;

import com.samvaad.samvaad_server.user.UserRole;

import java.util.UUID;

/**
 * Authenticated identity of one STOMP/WebSocket connection.
 *
 * <p>One connection belongs to exactly one authenticated E2EE device. The
 * {@code deviceId} is derived server-side from the authenticated session's
 * device binding ({@code Session.deviceId}) and is never taken from
 * client-supplied data. This principal is STOMP-specific: HTTP
 * authentication keeps using {@code AuthenticatedUser} so WebSocket state
 * does not leak into unrelated HTTP code.
 */
public record StompDevicePrincipal(UUID userId, UserRole role, UUID sessionId, UUID deviceId) {
}
