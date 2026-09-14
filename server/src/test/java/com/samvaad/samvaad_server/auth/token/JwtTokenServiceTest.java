package com.samvaad.samvaad_server.auth.token;

import com.samvaad.samvaad_server.auth.exception.InvalidAccessTokenException;
import com.samvaad.samvaad_server.user.User;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class JwtTokenServiceTest {

    private static final String SECRET =
            "this-is-a-valid-test-secret-key-at-least-32-bytes-long-for-hmac-sha256";

    private JwtTokenService tokenService;
    private SecretKey signingKey;

    @BeforeEach
    void setUp() {
        tokenService = new JwtTokenService(SECRET);
        signingKey = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void generatesAccessTokenWithMinimalClaims() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId);
        user.setUsername("animesh");

        String token = tokenService.generateAccessToken(user, sessionId);

        assertNotNull(token);

        Claims claims = Jwts.parser()
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();

        assertEquals(userId.toString(), claims.getSubject());
        assertEquals(sessionId.toString(), claims.get("sid"));
        assertNull(
                claims.get("username"),
                "username must not be included as a JWT claim"
        );
        assertNotNull(claims.getIssuedAt());
        assertNotNull(claims.getExpiration());
    }

    @Test
    void generatesOpaqueRefreshTokenAndValidSha256Hash() {
        String refreshToken1 = tokenService.generateRefreshToken();
        String refreshToken2 = tokenService.generateRefreshToken();

        assertNotNull(refreshToken1);
        assertNotNull(refreshToken2);
        assertNotEquals(refreshToken1, refreshToken2);

        String hash1 = tokenService.hashRefreshToken(refreshToken1);
        String hash1Repeat = tokenService.hashRefreshToken(refreshToken1);
        String hash2 = tokenService.hashRefreshToken(refreshToken2);

        assertEquals(64, hash1.length());
        assertEquals(43, refreshToken1.length());
        assertEquals(hash1, hash1Repeat);
        assertNotEquals(hash1, hash2);
    }

    @Test
    void constructor_shouldThrowExceptionForShortSecret() {
        String shortSecret = "1234567890123456789012345678901"; // 31 bytes

        assertThrows(
                IllegalArgumentException.class,
                () -> new JwtTokenService(shortSecret)
        );
    }

    @Test
    void constructor_shouldNotThrowExceptionForValidSecret() {
        String validSecret = "12345678901234567890123456789012"; // 32 bytes

        assertDoesNotThrow(
                () -> new JwtTokenService(validSecret)
        );
    }

    @Test
    void reportsCorrectTokenLifetimes() {
        assertEquals(86400L, tokenService.getAccessTokenValiditySeconds());
        assertEquals(30L, tokenService.getRefreshTokenValidityDays());
    }

    @Test
    void parsesAccessTokenAndExtractsSubjectAndSessionId() {
        UUID userId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        User user = new User(userId);

        String token = tokenService.generateAccessToken(user, sessionId);
        AccessTokenClaims claims = tokenService.parseAccessToken(token);

        assertEquals(userId, claims.userId());
        assertEquals(sessionId, claims.sessionId());
    }

    @Test
    void rejectsTokenSignedWithADifferentKey() {
        SecretKey foreignKey = Keys.hmacShaKeyFor(
                "another-valid-test-secret-key-at-least-32-bytes-long".getBytes(StandardCharsets.UTF_8));
        String foreignToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 60_000L))
                .signWith(foreignKey)
                .compact();

        assertThrows(InvalidAccessTokenException.class,
                () -> tokenService.parseAccessToken(foreignToken));
    }

    @Test
    void rejectsExpiredToken() {
        String expiredToken = Jwts.builder()
                .subject(UUID.randomUUID().toString())
                .claim("sid", UUID.randomUUID().toString())
                .issuedAt(new Date(System.currentTimeMillis() - 120_000L))
                .expiration(new Date(System.currentTimeMillis() - 60_000L))
                .signWith(signingKey)
                .compact();

        assertThrows(InvalidAccessTokenException.class,
                () -> tokenService.parseAccessToken(expiredToken));
    }

    @Test
    void rejectsMalformedToken() {
        assertThrows(InvalidAccessTokenException.class,
                () -> tokenService.parseAccessToken("not-a-jwt"));
    }
}