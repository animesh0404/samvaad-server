package com.samvaad.samvaad_server.auth.dto;

import java.util.UUID;

public record LoginResponseDto(
        String accessToken,
        String refreshToken,
        long expiresIn,
        UUID sessionId
) {}
