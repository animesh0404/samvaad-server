package com.samvaad.samvaad_server.auth.token;

import com.samvaad.samvaad_server.user.User;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class JwtTokenService implements TokenService {

    private static final long ACCESS_TOKEN_VALIDITY_SECONDS = 86400L; // 1 day
    private static final long REFRESH_TOKEN_VALIDITY_DAYS = 30L;       // 30 days

    private final SecretKey signingKey;
    private final SecureRandom secureRandom = new SecureRandom();

    public JwtTokenService(@Value("${samvaad.auth.jwt-secret}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("JWT secret must not be empty");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            throw new IllegalArgumentException("JWT secret must be at least 32 bytes long");
        }
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
    }

    @Override
    public String generateAccessToken(User user, UUID sessionId) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + (ACCESS_TOKEN_VALIDITY_SECONDS * 1000L));

        return Jwts.builder()
                .subject(user.getUserId().toString())
                .claim("sid", sessionId.toString())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    @Override
    public String generateRefreshToken() {
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
    }

    @Override
    public String hashRefreshToken(String rawRefreshToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(rawRefreshToken.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }

    @Override
    public long getAccessTokenValiditySeconds() {
        return ACCESS_TOKEN_VALIDITY_SECONDS;
    }

    @Override
    public long getRefreshTokenValidityDays() {
        return REFRESH_TOKEN_VALIDITY_DAYS;
    }
}
