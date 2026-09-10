package com.samvaad.samvaad_server.auth.token;

import com.samvaad.samvaad_server.user.User;

import java.util.UUID;

public interface TokenService {

    String generateAccessToken(User user, UUID sessionId);

    String generateRefreshToken();

    String hashRefreshToken(String rawRefreshToken);

    long getAccessTokenValiditySeconds();

    long getRefreshTokenValidityDays();
}
