package com.samvaad.samvaad_server.auth.event;

import com.samvaad.samvaad_server.session.ClientPlatform;

import java.time.Instant;
import java.util.UUID;

public record LoginBlockedDueToSessionLimitEvent(
        UUID userId,
        Instant attemptedAt,
        String installationId,
        ClientPlatform clientPlatform,
        String clientName,
        String clientVersion,
        String ipAddress,
        String userAgent
) {}
